package eta.build;

import org.gradle.api.*;
import org.gradle.api.artifacts.*;
import org.gradle.api.artifacts.component.*;
import org.gradle.api.attributes.Attribute;
import org.gradle.api.file.*;
import org.gradle.api.tasks.*;
import org.gradle.api.tasks.compile.JavaCompile;
import org.gradle.api.tasks.testing.Test;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Gate the real consuming classpaths before AGP extracts classes or dexes them. */
public final class ComposeSemanticsPlugin implements Plugin<Project> {
    private static final String VERSION = ComposeSemanticsAarTransform.VERSION;
    private static final String ORIGINAL = "etaComposeSemanticsOriginal";
    private static final String ANCHOR = "androidx/compose/ui/semantics/SemanticsSortKt.class";
    private static final Attribute<String> TYPE = Attribute.of("artifactType", String.class);
    private static final Attribute<Boolean> PATCHED = Attribute.of("eta.compose.semantics.patched", Boolean.class);
    private static final List<String> CHAINS = List.of("debugCompileClasspath", "debugRuntimeClasspath",
            "releaseCompileClasspath", "releaseRuntimeClasspath", "debugUnitTestCompileClasspath", "debugUnitTestRuntimeClasspath");

    @Override public void apply(Project project) {
        project.getDependencies().getArtifactTypes().maybeCreate("aar").getAttributes().attribute(PATCHED, false);
        project.getDependencies().registerTransform(ComposeSemanticsAarTransform.class, spec -> {
            spec.getFrom().attribute(TYPE, "aar").attribute(PATCHED, false);
            spec.getTo().attribute(TYPE, "aar").attribute(PATCHED, true);
        });
        Configuration original = project.getConfigurations().create(ORIGINAL, c -> {
            c.setCanBeConsumed(false); c.setCanBeResolved(true); c.setTransitive(false);
            c.getAttributes().attribute(TYPE, "aar").attribute(PATCHED, false);
        });
        original.getDependencies().add(project.getDependencies().create("androidx.compose.ui:ui-android:" + VERSION + "@aar"));
        project.getConfigurations().configureEach(c -> {
            if (ORIGINAL.equals(c.getName())) return;
            if (c.isCanBeResolved() || CHAINS.contains(c.getName())) c.getAttributes().attribute(PATCHED, true);
            c.getIncoming().beforeResolve(incoming -> {
                if (!Boolean.TRUE.equals(c.getAttributes().getAttribute(PATCHED)))
                    throw new GradleException(c.getName() + " must request patched Compose artifacts");
            });
            c.getIncoming().afterResolve(incoming -> incoming.getResolutionResult().getAllComponents().forEach(component -> {
                if (isUi(component.getId())) {
                    ModuleComponentIdentifier id = (ModuleComponentIdentifier) component.getId();
                    requireUiVersion(id.getGroup(), id.getModule(), id.getVersion());
                }
            }));
        });
        TaskProvider<OriginalClasses> baseline = project.getTasks().register("prepareComposeSemanticsBaseline", OriginalClasses.class, task -> {
            task.getOriginalAar().from(original);
            task.getOutputJar().convention(project.getLayout().getBuildDirectory().file("compose-semantics/original-classes.jar"));
        });
        TaskProvider<VerifyWiring> verify = project.getTasks().register("verifyComposeSemanticsCache", VerifyWiring.class);
        project.afterEvaluate(ignored -> {
            for (String name : CHAINS) {
                Configuration c = project.getConfigurations().getByName(name);
                if (!c.isCanBeResolved() || !Boolean.TRUE.equals(c.getAttributes().getAttribute(PATCHED)))
                    throw new GradleException(name + " is not a patched classpath");
                FileCollection aar = c.getIncoming().artifactView(view -> {
                    view.componentFilter(ComposeSemanticsPlugin::isUi); view.attributes(a -> a.attribute(TYPE, "aar"));
                }).getFiles();
                FileCollection classes = c.getIncoming().artifactView(view -> {
                    view.componentFilter(ComposeSemanticsPlugin::isUi); view.attributes(a -> a.attribute(TYPE, "android-classes-jar"));
                }).getFiles();
                verify.configure(task -> task.getChains().add(new Chain(name, aar, classes)));
            }
        });
        project.getTasks().matching(t -> t.getName().equals("preBuild")).configureEach(t -> t.dependsOn(verify));
        project.getTasks().withType(JavaCompile.class).configureEach(t -> {
            t.dependsOn(verify); t.doFirst(task -> checkConsumer(((JavaCompile) task).getClasspath(), task.getPath()));
        });
        project.getTasks().withType(Test.class).configureEach(t -> {
            var jar = baseline.flatMap(OriginalClasses::getOutputJar);
            t.dependsOn(verify, baseline);
            t.getInputs().file(jar).withPropertyName("composeSemanticsOriginalClasses").withPathSensitivity(PathSensitivity.NONE);
            t.systemProperty("eta.compose.semantics.original", jar.get().getAsFile().getAbsolutePath());
            t.doFirst(task -> checkConsumer(((Test) task).getClasspath(), task.getPath()));
        });
    }
    public static void requireUiVersion(String group, String name, String version) {
        if (group.equals("androidx.compose.ui") && (name.equals("ui") || name.equals("ui-android")) && !VERSION.equals(version))
            throw new GradleException("Compose version changed: " + version + "; review patch, do not force version");
    }
    private static boolean isUi(ComponentIdentifier id) {
        return id instanceof ModuleComponentIdentifier m && m.getGroup().equals("androidx.compose.ui")
                && (m.getModule().equals("ui") || m.getModule().equals("ui-android"));
    }
    @CacheableTask public abstract static class OriginalClasses extends DefaultTask {
        @InputFiles @PathSensitive(PathSensitivity.NONE) public abstract ConfigurableFileCollection getOriginalAar();
        @OutputFile public abstract RegularFileProperty getOutputJar();
        @Input public String getComposeVersion() { return VERSION; }
        @TaskAction public void extract() throws IOException {
            byte[] jar = ComposeSemanticsAarTransform.originalClasses(Files.readAllBytes(getOriginalAar().getSingleFile().toPath()));
            Path output = getOutputJar().get().getAsFile().toPath(); Files.createDirectories(output.getParent()); Files.write(output, jar);
        }
    }
    public static final class Chain {
        private final String name; private final FileCollection aar, classes;
        Chain(String name, FileCollection aar, FileCollection classes) { this.name = name; this.aar = aar; this.classes = classes; }
        @Input public String getName() { return name; }
        @InputFiles @PathSensitive(PathSensitivity.NONE) public FileCollection getAar() { return aar; }
        @Classpath public FileCollection getClasses() { return classes; }
    }
    public abstract static class VerifyWiring extends DefaultTask {
        private final List<Chain> chains = new ArrayList<>();
        @Nested public List<Chain> getChains() { return chains; }
        @TaskAction public void verify() throws IOException {
            if (chains.size() != CHAINS.size()) throw new GradleException("Six classpaths are required");
            for (Chain c : chains) {
                byte[] aar = Files.readAllBytes(c.aar.getSingleFile().toPath());
                ComposeSemanticsAarTransform.assertPatched(aar);
                byte[] actual = Files.readAllBytes(c.classes.getSingleFile().toPath()); assertClasses(actual);
                Map<String, byte[]> a = classEntries(entry(aar, "classes.jar")), b = classEntries(actual);
                verifyClassEntries(a, b, c.name);
                getLogger().lifecycle("Verified patched Compose classes: {}", c.name);
            }
        }
    }
    static void verifyClassEntries(Map<String, byte[]> expected, Map<String, byte[]> actual, String label) {
        for (String name : expected.keySet()) {
            if (!Arrays.equals(expected.get(name), actual.get(name)))
                throw new GradleException("AGP class bytes differ: " + label + "/" + name);
        }
        // AGP adds the library's generated resource classes to compile classpaths.
        for (String name : actual.keySet()) {
            if (expected.containsKey(name)) continue;
            if (!name.equals("androidx/compose/ui/R.class")
                    && !name.matches("androidx/compose/ui/R\\$[A-Za-z_][A-Za-z_0-9]*\\.class"))
                throw new GradleException("Unexpected AGP class: " + label + "/" + name);
        }
    }
    private static byte[] entry(byte[] zip, String name) throws IOException {
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry e; (e = in.getNextEntry()) != null;) if (e.getName().equals(name)) return in.readAllBytes();
        }
        throw new IOException("Missing entry " + name);
    }
    private static Map<String, byte[]> classEntries(byte[] bytes) throws IOException {
        Map<String, byte[]> map = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            for (ZipEntry e; (e = in.getNextEntry()) != null;) if (e.getName().endsWith(".class")) map.put(e.getName(), in.readAllBytes());
        }
        return map;
    }
    private static void assertClasses(byte[] jar) {
        ComposeSemanticsAarTransform.assertConsumerClasses(jar);
    }
    private static void checkConsumer(FileCollection files, String label) {
        int owners = 0;
        try {
            for (File f : files) {
                if (f.isDirectory() && Files.isRegularFile(f.toPath().resolve(ANCHOR)))
                    throw new GradleException("Unexpected directory Compose dependency: " + label);
                if (!f.isFile() || !f.getName().endsWith(".jar")) continue;
                try (ZipFile zip = new ZipFile(f)) {
                    if (zip.getEntry(ANCHOR) != null) { owners++; assertClasses(Files.readAllBytes(f.toPath())); }
                }
            }
            if (owners != 1) throw new GradleException(label + " loaded " + owners + " Compose owners");
        } catch (IOException e) { throw new GradleException("Cannot verify consumer " + label, e); }
    }
}
