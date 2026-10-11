package eta.build;

import org.gradle.api.GradleException;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.util.*;

/** Exact-artifact patch; unrelated coordinator callers retain the original method. */
final class ComposeTransparencyPatch implements Opcodes {
    static final String COORD = "androidx/compose/ui/node/NodeCoordinator";
    static final String NODE = "androidx/compose/ui/semantics/SemanticsNode";
    static final String OWNER = "androidx/compose/ui/semantics/SemanticsOwnerKt";
    static final String SORT = "androidx/compose/ui/semantics/SemanticsSortKt";
    static final String CACHE = "androidx/compose/ui/semantics/EtaSemanticsTransparencyCache";
    static final String ENTRY = "eta$isTransparent", BODY = "eta$isTransparent$body";
    static final String SUBTREE = "subtreeSortedByGeometryGrouping";
    static final String UNCOVERED = "getAllUncoveredSemanticsNodesToIntObjectMap";

    static byte[] patch(byte[] input, String name) {
        ClassNode c = new ClassNode(ASM9); new ClassReader(input).accept(c, 0);
        if (name.equals(COORD)) coordinator(c);
        else if (name.equals(NODE)) {
            int sites = 0;
            for (MethodNode m : c.methods) for (AbstractInsnNode i : m.instructions) {
                if (i instanceof MethodInsnNode call && call.owner.equals(COORD) && call.name.equals("isTransparent")) {
                    require(m.name.equals("isTransparent$ui") && call.desc.equals("()Z"), "semantic transparency site");
                    call.name = ENTRY; sites++;
                }
            }
            require(sites == 1, "semantic transparency count");
        } else if (name.equals(SORT)) {
            // Algorithm recursion shares a query; predicate reentry still opens a distinct scope.
            int recursion = 0;
            for (MethodNode m : c.methods) for (AbstractInsnNode i : m.instructions) {
                if (i instanceof MethodInsnNode call && call.owner.equals(SORT) && call.name.equals(SUBTREE)) {
                    require(m.name.equals("geometryDepthFirstSearch"), "subtree recursion");
                    call.name = "eta$" + SUBTREE + "$body"; recursion++;
                }
            }
            require(recursion == 1, "subtree recursion count");
            wrap(c, SUBTREE);
        } else if (name.equals(OWNER)) wrap(c, UNCOVERED);
        else throw new GradleException("Unexpected transparency target " + name);
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS); c.accept(w); return w.toByteArray();
    }
    private static void coordinator(ClassNode c) {
        MethodNode original = c.methods.stream().filter(m -> m.name.equals("isTransparent") && m.desc.equals("()Z")).findFirst().orElseThrow();
        MethodNode body = new MethodNode(ASM9, ACC_PRIVATE | ACC_SYNTHETIC, BODY, "()Z", null, null);
        original.accept(body);
        body.name = BODY; body.access = ACC_PRIVATE | ACC_SYNTHETIC;
        int recursive = 0;
        for (AbstractInsnNode i : body.instructions) if (i instanceof MethodInsnNode call && call.owner.equals(COORD) && call.name.equals("isTransparent")) {
            call.name = ENTRY; recursive++;
        }
        require(recursive == 1, "transparency recursion");
        Map<String, Integer> writes = new TreeMap<>();
        for (MethodNode m : c.methods) for (AbstractInsnNode i : m.instructions.toArray()) {
            if (i instanceof FieldInsnNode f && f.getOpcode() == PUTFIELD && f.owner.equals(COORD)
                    && Set.of("layer", "lastLayerAlpha", "wrappedBy").contains(f.name)) {
                writes.merge(f.name, 1, Integer::sum);
                // Constructor's new object cannot already be a cache key or ancestor.
                if (!m.name.equals("<init>")) m.instructions.insert(f,
                    new MethodInsnNode(INVOKESTATIC, CACHE, "invalidate", "()V", false));
            }
        }
        require(writes.equals(Map.of("layer", 3, "lastLayerAlpha", 2, "wrappedBy", 1)), "dependency writes " + writes);
        c.methods.add(body);
        MethodNode v = new MethodNode(ASM9, ACC_PUBLIC | ACC_FINAL | ACC_SYNTHETIC, ENTRY, "()Z", null, null);
        Label scoped = new Label(), miss = new Label();
        v.visitCode();
        v.visitMethodInsn(INVOKESTATIC, CACHE, "active", "()Z", false); v.visitJumpInsn(IFNE, scoped);
        v.visitVarInsn(ALOAD, 0); v.visitMethodInsn(INVOKEVIRTUAL, COORD, "isTransparent", "()Z", false); v.visitInsn(IRETURN);
        v.visitLabel(scoped); v.visitFrame(F_SAME, 0, null, 0, null);
        v.visitVarInsn(ALOAD, 0); v.visitMethodInsn(INVOKESTATIC, CACHE, "peek", "(Ljava/lang/Object;)Ljava/lang/Boolean;", false);
        v.visitVarInsn(ASTORE, 1); v.visitVarInsn(ALOAD, 1); v.visitJumpInsn(IFNULL, miss);
        v.visitVarInsn(ALOAD, 1); v.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Boolean", "booleanValue", "()Z", false); v.visitInsn(IRETURN);
        v.visitLabel(miss); v.visitFrame(F_APPEND, 1, new Object[]{"java/lang/Boolean"}, 0, null);
        v.visitVarInsn(ALOAD, 0); v.visitVarInsn(ALOAD, 0);
        v.visitMethodInsn(INVOKESPECIAL, COORD, BODY, "()Z", false);
        v.visitMethodInsn(INVOKESTATIC, CACHE, "put", "(Ljava/lang/Object;Z)Z", false); v.visitInsn(IRETURN);
        v.visitMaxs(2, 2); v.visitEnd(); c.methods.add(v);
    }
    private static void wrap(ClassNode c, String name) {
        List<MethodNode> matches = c.methods.stream().filter(m -> m.name.equals(name)).toList();
        require(matches.size() == 1, "query entry " + name);
        MethodNode body = matches.get(0);
        require((body.access & ACC_STATIC) != 0 && Type.getReturnType(body.desc).getSort() == Type.OBJECT, "query shape");
        MethodNode v = new MethodNode(ASM9, body.access, name, body.desc, body.signature, body.exceptions.toArray(new String[0]));
        v.visibleAnnotations = body.visibleAnnotations; v.invisibleAnnotations = body.invisibleAnnotations;
        v.visibleParameterAnnotations = body.visibleParameterAnnotations; v.invisibleParameterAnnotations = body.invisibleParameterAnnotations;
        body.name = "eta$" + name + "$body"; body.access = ACC_PRIVATE | ACC_STATIC | ACC_SYNTHETIC;
        Label start = new Label(), end = new Label(), handler = new Label();
        v.visitCode(); v.visitMethodInsn(INVOKESTATIC, CACHE, "begin", "()V", false); v.visitLabel(start);
        int slot = 0;
        for (Type arg : Type.getArgumentTypes(v.desc)) { v.visitVarInsn(arg.getOpcode(ILOAD), slot); slot += arg.getSize(); }
        v.visitMethodInsn(INVOKESTATIC, c.name, body.name, body.desc, false); v.visitVarInsn(ASTORE, slot);
        v.visitLabel(end); v.visitMethodInsn(INVOKESTATIC, CACHE, "end", "()V", false);
        v.visitVarInsn(ALOAD, slot); v.visitInsn(ARETURN);
        v.visitLabel(handler); v.visitFrame(F_SAME1, 0, null, 1, new Object[]{"java/lang/Throwable"});
        v.visitVarInsn(ASTORE, slot); v.visitMethodInsn(INVOKESTATIC, CACHE, "end", "()V", false);
        v.visitVarInsn(ALOAD, slot); v.visitInsn(ATHROW);
        v.visitTryCatchBlock(start, end, handler, null); v.visitMaxs(slot, slot + 1); v.visitEnd(); c.methods.add(v);
    }
    static void verify(Map<String, byte[]> classes) {
        for (String name : List.of(COORD, NODE, OWNER, SORT)) {
            byte[] bytes = classes.get(name + ".class"); require(bytes != null, "missing " + name);
            ClassNode c = new ClassNode(ASM9); new ClassReader(bytes).accept(c, 0);
            if (name.equals(COORD)) {
                require(c.methods.stream().anyMatch(m -> m.name.equals(ENTRY)), "missing memo entry");
                int invalidations = 0;
                for (MethodNode m : c.methods) for (AbstractInsnNode i : m.instructions)
                    if (i instanceof MethodInsnNode call && call.owner.equals(CACHE) && call.name.equals("invalidate")) invalidations++;
                require(invalidations == 5, "invalidation sites");
            } else if (name.equals(NODE)) {
                int calls = 0;
                for (MethodNode m : c.methods) for (AbstractInsnNode i : m.instructions)
                    if (i instanceof MethodInsnNode call && call.owner.equals(COORD) && call.name.equals(ENTRY)) calls++;
                require(calls == 1, "memo wiring");
            } else {
                String method = name.equals(SORT) ? SUBTREE : UNCOVERED;
                require(c.methods.stream().anyMatch(m -> m.name.equals("eta$" + method + "$body")), "query body");
                MethodNode wrapper = c.methods.stream().filter(m -> m.name.equals(method)).findFirst().orElseThrow();
                require(wrapper.tryCatchBlocks.size() == 1, "query cleanup");
            }
        }
    }
    private static void require(boolean value, String message) {
        if (!value) throw new GradleException("Compose transparency patch shape mismatch: " + message);
    }
}
