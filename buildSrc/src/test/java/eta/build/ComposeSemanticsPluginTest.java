package eta.build;

import java.util.LinkedHashMap;
import java.util.Map;
import org.gradle.api.GradleException;
import org.junit.Test;
import static org.junit.Assert.assertThrows;

public class ComposeSemanticsPluginTest {
    private static final String SORT = "androidx/compose/ui/semantics/SemanticsSortKt.class";
    private static final Map<String, byte[]> EXPECTED = Map.of(SORT, new byte[]{1, 2});

    @Test public void acceptsUnchangedRuntimeAndGeneratedResourceClasses() {
        ComposeSemanticsPlugin.verifyClassEntries(EXPECTED, EXPECTED, "runtime");
        Map<String, byte[]> compile = new LinkedHashMap<>(EXPECTED);
        compile.put("androidx/compose/ui/R.class", new byte[]{3});
        compile.put("androidx/compose/ui/R$string.class", new byte[]{4});
        ComposeSemanticsPlugin.verifyClassEntries(EXPECTED, compile, "compile");
    }

    @Test public void rejectsMissingOrChangedPatchedClasses() {
        assertThrows(GradleException.class, () ->
            ComposeSemanticsPlugin.verifyClassEntries(EXPECTED, Map.of(), "missing"));
        assertThrows(GradleException.class, () ->
            ComposeSemanticsPlugin.verifyClassEntries(EXPECTED, Map.of(SORT, new byte[]{1, 3}), "changed"));
    }

    @Test public void rejectsOtherAddedClasses() {
        for (String extra : new String[]{"other/R.class", "androidx/compose/ui/R$nested/Injected.class",
                "androidx/compose/ui/semantics/Injected.class"}) {
            Map<String, byte[]> actual = new LinkedHashMap<>(EXPECTED);
            actual.put(extra, new byte[]{5});
            assertThrows(extra, GradleException.class, () ->
                ComposeSemanticsPlugin.verifyClassEntries(EXPECTED, actual, "extra"));
        }
    }

    @Test public void acceptsIdenticalConsumerCopiesWithGeneratedResources() {
        ComposeSemanticsPlugin.verifyConsumerCopies(EXPECTED, EXPECTED, "identical");
        Map<String, byte[]> compile = new LinkedHashMap<>(EXPECTED);
        compile.put("androidx/compose/ui/R$string.class", new byte[]{3});
        ComposeSemanticsPlugin.verifyConsumerCopies(compile, EXPECTED, "compile-runtime");
        ComposeSemanticsPlugin.verifyConsumerCopies(EXPECTED, compile, "runtime-compile");
    }

    @Test public void rejectsConflictingConsumerCopiesInEitherOrder() {
        for (Map<String, byte[]> other : java.util.List.of(
                Map.of(SORT, new byte[]{9}),
                Map.of(SORT, new byte[]{1, 2}, "unexpected/Injected.class", new byte[]{4}))) {
            assertThrows(GradleException.class, () ->
                    ComposeSemanticsPlugin.verifyConsumerCopies(EXPECTED, other, "conflict"));
            assertThrows(GradleException.class, () ->
                    ComposeSemanticsPlugin.verifyConsumerCopies(other, EXPECTED, "conflict-reversed"));
        }
    }
}
