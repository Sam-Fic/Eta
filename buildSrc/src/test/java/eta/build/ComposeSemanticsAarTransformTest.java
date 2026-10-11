package eta.build;

import org.junit.Test;
import static org.junit.Assert.*;
import java.nio.file.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

public class ComposeSemanticsAarTransformTest {
    private byte[] official() throws IOException {
        String path = System.getProperty("eta.compose.semantics.originalAar");
        assertNotNull("Official AAR fixture is required, never skip transform tests", path);
        return Files.readAllBytes(Path.of(path));
    }
    @Test public void exactArtifactChangesOnlyTargetClassesAndAddsScopedBridge() throws Exception {
        byte[] source = official();
        Map<String, byte[]> before = unzip(ComposeSemanticsAarTransform.originalClasses(source));
        byte[] patched = ComposeSemanticsAarTransform.patchExact(source);
        ComposeSemanticsAarTransform.assertPatched(patched);
        Map<String, byte[]> after = unzip(unzip(patched).get("classes.jar"));
        Set<String> targets = Set.of("SemanticsSortKt.class", "LtrBoundsComparator.class", "RtlBoundsComparator.class", "SemanticsNode.class", "SemanticsOwnerKt.class", "NodeCoordinator.class", "AndroidComposeViewAccessibilityDelegateCompat_androidKt.class");
        int calls = 0, bodies = 0, wrappers = 0;
        for (Map.Entry<String, byte[]> e : before.entrySet()) {
            if (!targets.contains(e.getKey().substring(e.getKey().lastIndexOf('/') + 1))) {
                assertArrayEquals(e.getKey(), e.getValue(), after.get(e.getKey())); continue;
            }
            ClassNode c = new ClassNode(); new ClassReader(after.get(e.getKey())).accept(c, 0);
            for (MethodNode m : c.methods) {
                if (m.name.equals("eta$sortByGeometryGroupings$body")) bodies++;
                if (m.name.equals("sortByGeometryGroupings")) {
                    wrappers++; assertEquals(1, m.tryCatchBlocks.size());
                    assertNull(m.tryCatchBlocks.get(0).type);
                }
                for (AbstractInsnNode a : m.instructions) {
                    if (a instanceof MethodInsnNode call && call.name.equals("eta$boundsInWindow")) calls++;
                }
            }
        }
        assertEquals(7, calls); assertEquals(1, bodies); assertEquals(1, wrappers);
        assertEquals(before.size() + 6, after.size());
        assertArrayEquals(patched, ComposeSemanticsAarTransform.patchExact(source));
    }
    @Test public void runtimeClassesMayOmitResourcesButMustRetainThePatch() throws Exception {
        byte[] source = official();
        Map<String, byte[]> patched = unzip(unzip(ComposeSemanticsAarTransform.patchExact(source)).get("classes.jar"));
        patched.keySet().removeIf(name -> !name.endsWith(".class"));
        ComposeSemanticsAarTransform.assertConsumerClasses(zip(patched));
        Map<String, byte[]> original = unzip(ComposeSemanticsAarTransform.originalClasses(source));
        String sort = "androidx/compose/ui/semantics/SemanticsSortKt.class";
        patched.put(sort, original.get(sort));
        assertThrows(org.gradle.api.GradleException.class,
            () -> ComposeSemanticsAarTransform.assertConsumerClasses(zip(patched)));
    }
    @Test public void everyTransparencyConsumerTargetMustRetainItsPatch() throws Exception {
        byte[] source = official();
        Map<String, byte[]> original = unzip(ComposeSemanticsAarTransform.originalClasses(source));
        Map<String, byte[]> patched = unzip(unzip(ComposeSemanticsAarTransform.patchExact(source)).get("classes.jar"));
        for (String target : List.of(ComposeTransparencyPatch.COORD, ComposeTransparencyPatch.NODE, ComposeTransparencyPatch.OWNER, ComposeTraversalFocusPatch.TARGET)) {
            Map<String, byte[]> broken = new LinkedHashMap<>(patched);
            broken.put(target + ".class", original.get(target + ".class"));
            assertThrows(org.gradle.api.GradleException.class,
                    () -> ComposeSemanticsAarTransform.assertConsumerClasses(zip(broken)));
        }
    }
    private static byte[] zip(Map<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                out.putNextEntry(new ZipEntry(e.getKey())); out.write(e.getValue()); out.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
    @Test public void upgradedComposeRequiresReview() {
        ComposeSemanticsPlugin.requireUiVersion("androidx.compose.ui", "ui-android", "1.12.0-rc01");
        assertThrows(org.gradle.api.GradleException.class,
            () -> ComposeSemanticsPlugin.requireUiVersion("androidx.compose.ui", "ui-android", "1.12.0"));
    }
    @Test public void differentArtifactIsRejected() throws Exception {
        byte[] bytes = official(); bytes[100] ^= 1;
        assertThrows(org.gradle.api.GradleException.class, () -> ComposeSemanticsAarTransform.patchExact(bytes));
    }
    private static Map<String, byte[]> unzip(byte[] bytes) throws IOException {
        Map<String, byte[]> map = new LinkedHashMap<>();
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            for (ZipEntry e; (e = z.getNextEntry()) != null;) map.put(e.getName(), z.readAllBytes());
        }
        return map;
    }
}
