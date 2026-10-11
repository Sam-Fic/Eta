package eta.build;

import org.gradle.api.GradleException;
import org.gradle.api.artifacts.transform.*;
import org.gradle.api.file.FileSystemLocation;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

@CacheableTransform
public abstract class ComposeSemanticsAarTransform implements TransformAction<TransformParameters.None>, Opcodes {
    public static final String VERSION = "1.12.0-rc01";
    public static final String AAR_SHA = "99cfa4aba67d40b85af610f773142ef4567221c726741fa680822c3be0daff7c";
    public static final String JAR_SHA = "5ebbd3d09bdb44d8681fc2287191a60930905bdea363fa2787318800c8260375";
    private static final String P = "androidx/compose/ui/semantics/";
    private static final String SORT = P + "SemanticsSortKt", NODE = P + "SemanticsNode";
    private static final String RECT = "androidx/compose/ui/geometry/Rect", CACHE = P + "EtaSemanticsBoundsCache";
    private static final String GET = "()L" + RECT + ";", BRIDGE = "(L" + NODE + ";)L" + RECT + ";";
    private static final String DESC = "(L" + NODE + ";Ljava/util/List;Lkotlin/jvm/functions/Function1;Landroidx/collection/IntObjectMap;)Ljava/util/List;";
    private static final String BODY = "eta$sortByGeometryGroupings$body", BOUNDS = "eta$boundsInWindow";
    public static final String MARKER = "META-INF/eta-compose-semantics-cache.properties";
    private static final byte[] MARK = ("scheme=1\nversion=" + VERSION + "\noriginalAarSha256=" + AAR_SHA
            + "\noriginalClassesSha256=" + JAR_SHA + "\nreplacedGetterSites=7\n").getBytes(StandardCharsets.UTF_8);

    @InputArtifact @PathSensitive(PathSensitivity.NONE)
    public abstract Provider<FileSystemLocation> getInputArtifact();

    @Override public void transform(TransformOutputs outputs) {
        File input = getInputArtifact().get().getAsFile();
        try {
            byte[] jar;
            try (ZipFile zip = new ZipFile(input)) {
                ZipEntry entry = zip.getEntry("classes.jar");
                if (entry == null) { outputs.file(input); return; }
                try (InputStream in = zip.getInputStream(entry)) { jar = in.readAllBytes(); }
            }
            boolean target = false;
            try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(jar))) {
                for (ZipEntry entry; (entry = zip.getNextEntry()) != null;) {
                    if (entry.getName().equals(SORT + ".class")) { target = true; break; }
                }
            }
            if (!target) { outputs.file(input); return; }
            Files.write(outputs.file("ui-android-" + VERSION + "-eta-semantics-cache.aar").toPath(),
                    patchExact(Files.readAllBytes(input.toPath())));
        } catch (IOException e) { throw new GradleException("Compose semantics transform failed", e); }
    }
    public static byte[] originalClasses(byte[] aar) {
        requireHash(aar, AAR_SHA);
        byte[] jar = required(unzip(aar), "classes.jar"); requireHash(jar, JAR_SHA); return jar;
    }
    public static byte[] patchExact(byte[] aar) {
        Map<String, byte[]> outer = unzip(aar), classes = unzip(originalClasses(aar));
        classes.put(SORT + ".class", patch(required(classes, SORT + ".class"), true));
        for (String name : new String[]{"LtrBoundsComparator", "RtlBoundsComparator"})
            classes.put(P + name + ".class", patch(required(classes, P + name + ".class"), false));
        for (String suffix : new String[]{"", "$Scope", "$Probe"}) {
            String name = CACHE + suffix + ".class";
            try (InputStream in = ComposeSemanticsAarTransform.class.getClassLoader().getResourceAsStream(name)) {
                if (in == null) throw new GradleException("Missing helper " + name);
                byte[] helper = in.readAllBytes();
                if (new ClassReader(helper).readShort(6) > V1_8) throw new GradleException("Helper must target Java 8");
                classes.put(name, helper);
            } catch (IOException e) { throw new GradleException("Cannot read helper", e); }
        }
        classes.put(MARKER, MARK); outer.put("classes.jar", zip(classes));
        byte[] result = zip(outer); assertPatched(result); return result;
    }
    public static void assertPatched(byte[] aar) {
        Map<String, byte[]> classes = unzip(required(unzip(aar), "classes.jar"));
        if (!Arrays.equals(MARK, classes.get(MARKER))) throw new GradleException("Compose patch marker mismatch");
        required(classes, CACHE + ".class"); required(classes, CACHE + "$Scope.class"); required(classes, CACHE + "$Probe.class");
    }
    static void assertConsumerClasses(byte[] jar) {
        Map<String, byte[]> classes = unzip(jar);
        // AGP strips META-INF resources from runtime jars; check executable classes instead.
        for (String suffix : new String[]{"", "$Scope", "$Probe"}) {
            String name = CACHE + suffix + ".class";
            try (InputStream in = ComposeSemanticsAarTransform.class.getClassLoader().getResourceAsStream(name)) {
                if (in == null || !Arrays.equals(in.readAllBytes(), required(classes, name)))
                    throw new GradleException("Compose helper bytes mismatch: " + name);
            } catch (IOException e) { throw new GradleException("Cannot verify helper", e); }
        }
        int calls = 0, bodies = 0, wrappers = 0, bridges = 0;
        for (String name : new String[]{SORT, P + "LtrBoundsComparator", P + "RtlBoundsComparator"}) {
            ClassNode c = new ClassNode(ASM9);
            new ClassReader(required(classes, name + ".class")).accept(c, 0);
            for (MethodNode method : c.methods) {
                if (method.name.equals(BODY) && method.desc.equals(DESC)) bodies++;
                if (method.name.equals("sortByGeometryGroupings") && method.desc.equals(DESC)) wrappers++;
                if (method.name.equals(BOUNDS) && method.desc.equals(BRIDGE)) bridges++;
                for (AbstractInsnNode instruction : method.instructions) {
                    if (!(instruction instanceof MethodInsnNode call)) continue;
                    if (call.owner.equals(SORT) && call.name.equals(BOUNDS) && call.desc.equals(BRIDGE)) calls++;
                    if (call.owner.equals(NODE) && call.name.equals("getBoundsInWindow") && !method.name.equals(BOUNDS))
                        throw new GradleException("Unpatched Compose bounds getter in consumer");
                }
            }
        }
        if (calls != 7 || bodies != 1 || wrappers != 1 || bridges != 1)
            throw new GradleException("Compose consumer patch shape mismatch");
    }
    private static byte[] patch(byte[] original, boolean sortClass) {
        ClassNode c = new ClassNode(ASM9); new ClassReader(original).accept(c, 0);
        int sortReads = 0, rowReads = 0, compareReads = 0;
        MethodNode body = null;
        for (MethodNode m : c.methods) {
            boolean isSort = sortClass && m.name.equals("sortByGeometryGroupings") && m.desc.equals(DESC);
            boolean isRow = sortClass && m.name.equals("placedEntryRowOverlaps") && m.desc.equals("(Ljava/util/ArrayList;L" + NODE + ";)Z");
            boolean isCompare = !sortClass && m.name.equals("compare") && m.desc.equals("(L" + NODE + ";L" + NODE + ";)I");
            if (isSort) { if (body != null) throw new GradleException("Duplicate sort"); body = m; }
            for (AbstractInsnNode instruction : m.instructions.toArray()) {
                if (!(instruction instanceof MethodInsnNode call) || !call.owner.equals(NODE)
                        || !call.name.equals("getBoundsInWindow") || !call.desc.equals(GET)) continue;
                if (!(isSort || isRow || isCompare) || call.getOpcode() != INVOKEVIRTUAL || call.itf)
                    throw new GradleException("Unexpected Compose getter site");
                if (isSort) sortReads++; else if (isRow) rowReads++; else compareReads++;
                m.instructions.set(call, new MethodInsnNode(INVOKESTATIC, SORT, BOUNDS, BRIDGE, false));
            }
        }
        if (sortClass) {
            if (body == null || sortReads != 1 || rowReads != 2) throw new GradleException("Compose sort shape changed");
            MethodNode wrapper = new MethodNode(ASM9, body.access, body.name, body.desc, body.signature,
                    body.exceptions.toArray(new String[0]));
            wrapper.visibleAnnotations = body.visibleAnnotations; wrapper.invisibleAnnotations = body.invisibleAnnotations;
            wrapper.visibleParameterAnnotations = body.visibleParameterAnnotations;
            wrapper.invisibleParameterAnnotations = body.invisibleParameterAnnotations;
            body.name = BODY; body.access = ACC_PRIVATE | ACC_STATIC | ACC_SYNTHETIC;
            emitWrapper(wrapper); c.methods.add(wrapper);
            MethodNode bridge = new MethodNode(ASM9, ACC_PUBLIC | ACC_STATIC | ACC_SYNTHETIC, BOUNDS, BRIDGE, null, null);
            emitBridge(bridge); c.methods.add(bridge);
        } else if (compareReads != 2) throw new GradleException("Compose comparator shape changed");
        ClassWriter writer = new ClassWriter(0); c.accept(writer); return writer.toByteArray();
    }
    private static void emitWrapper(MethodVisitor v) {
        Label start = new Label(), end = new Label(), handler = new Label();
        v.visitCode(); v.visitMethodInsn(INVOKESTATIC, CACHE, "begin", "()V", false);
        v.visitLabel(start);
        for (int i = 0; i < 4; i++) v.visitVarInsn(ALOAD, i);
        v.visitMethodInsn(INVOKESTATIC, SORT, BODY, DESC, false); v.visitVarInsn(ASTORE, 4);
        v.visitLabel(end); v.visitMethodInsn(INVOKESTATIC, CACHE, "end", "()V", false);
        v.visitVarInsn(ALOAD, 4); v.visitInsn(ARETURN);
        v.visitLabel(handler);
        v.visitFrame(F_SAME1, 0, null, 1, new Object[]{"java/lang/Throwable"});
        v.visitVarInsn(ASTORE, 4); v.visitMethodInsn(INVOKESTATIC, CACHE, "end", "()V", false);
        v.visitVarInsn(ALOAD, 4); v.visitInsn(ATHROW);
        v.visitTryCatchBlock(start, end, handler, null); v.visitMaxs(4, 5); v.visitEnd();
    }
    private static void emitBridge(MethodVisitor v) {
        Label miss = new Label(); v.visitCode();
        v.visitVarInsn(ALOAD, 0); v.visitMethodInsn(INVOKESTATIC, CACHE, "peek", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
        v.visitVarInsn(ASTORE, 1); v.visitVarInsn(ALOAD, 1); v.visitJumpInsn(IFNULL, miss);
        v.visitVarInsn(ALOAD, 1); v.visitTypeInsn(CHECKCAST, RECT); v.visitInsn(ARETURN);
        v.visitLabel(miss); v.visitFrame(F_APPEND, 1, new Object[]{"java/lang/Object"}, 0, null);
        v.visitVarInsn(ALOAD, 0); v.visitMethodInsn(INVOKEVIRTUAL, NODE, "getBoundsInWindow", GET, false); v.visitVarInsn(ASTORE, 1);
        v.visitVarInsn(ALOAD, 0); v.visitVarInsn(ALOAD, 1);
        v.visitMethodInsn(INVOKESTATIC, CACHE, "put", "(Ljava/lang/Object;Ljava/lang/Object;)V", false);
        v.visitVarInsn(ALOAD, 1); v.visitTypeInsn(CHECKCAST, RECT); v.visitInsn(ARETURN);
        v.visitMaxs(2, 2); v.visitEnd();
    }
    private static void requireHash(byte[] bytes, String expected) {
        try {
            if (!HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).equals(expected))
                throw new GradleException("Official Compose artifact changed; patch needs review");
        } catch (java.security.NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
    private static byte[] required(Map<String, byte[]> entries, String name) {
        byte[] bytes = entries.get(name); if (bytes == null) throw new GradleException("Missing " + name); return bytes;
    }
    private static Map<String, byte[]> unzip(byte[] bytes) {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            for (ZipEntry e; (e = in.getNextEntry()) != null;) {
                String name = e.getName(), upper = name.toUpperCase(Locale.ROOT);
                if (upper.startsWith("META-INF/") && (upper.endsWith(".SF") || upper.endsWith(".RSA") || upper.endsWith(".DSA") || upper.endsWith(".EC")))
                    throw new GradleException("Signed input cannot be transformed");
                if (entries.put(name, in.readAllBytes()) != null) throw new GradleException("Duplicate ZIP entry");
            }
        } catch (IOException e) { throw new GradleException("Malformed ZIP", e); }
        return entries;
    }
    private static byte[] zip(Map<String, byte[]> entries) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                ZipEntry entry = new ZipEntry(e.getKey()); entry.setTime(0); out.putNextEntry(entry);
                out.write(e.getValue()); out.closeEntry();
            }
        } catch (IOException e) { throw new GradleException("ZIP output failed", e); }
        return bytes.toByteArray();
    }
}
