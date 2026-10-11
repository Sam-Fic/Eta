plugins { `java-gradle-plugin` }
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
tasks.named<JavaCompile>("compileJava") { options.release.set(17) }
val semanticsRuntime = sourceSets.create("semanticsRuntime") {
    java.srcDir("src/semanticsRuntime/java")
}
tasks.named<JavaCompile>(semanticsRuntime.compileJavaTaskName) {
    sourceCompatibility = "1.8"
    targetCompatibility = "1.8"
    options.release.set(8)
}
tasks.named<ProcessResources>("processResources") {
    from(semanticsRuntime.output) { include("androidx/compose/ui/semantics/EtaSemantics*Cache*.class") }
}
dependencies {
    implementation("org.ow2.asm:asm:9.8")
    implementation("org.ow2.asm:asm-tree:9.8")
    testImplementation("junit:junit:4.13.2")
    testImplementation(files(semanticsRuntime.output))
}
gradlePlugin {
    plugins {
        create("composeSemanticsCache") {
            id = "eta.compose-semantics-cache"
            implementationClass = "eta.build.ComposeSemanticsPlugin"
        }
    }
}
tasks.withType<Test>().configureEach {
    System.getProperty("eta.compose.semantics.originalAar")?.let {
        inputs.file(it).withPropertyName("composeSemanticsOriginalAar")
            .withPathSensitivity(PathSensitivity.NONE)
        systemProperty("eta.compose.semantics.originalAar", it)
    }
}
