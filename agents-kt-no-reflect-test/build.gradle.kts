plugins {
    kotlin("jvm")
}

group = "ai.deep-code"
version = rootProject.version

repositories {
    mavenCentral()
}

dependencyLocking {
    lockAllConfigurations()
}

// #1718 — the whole point of this subproject. Exercise the agents-kt
// runtime with kotlin-reflect explicitly absent from the CONSUMER's
// classpaths, proving v0.4.6's claim that the runtime path no longer
// needs it.
//
// Scoped to consumer-shaped classpaths only: compile, runtime, and
// their test counterparts. The Kotlin compiler's own internal
// classpaths (kotlinCompilerClasspath, kotlinKlibCommonizerClasspath,
// kotlinBuildToolsApiClasspath) still need reflect — stripping it
// there breaks the compiler daemon (it uses reflect to read its own
// argument metadata).
//
// This mirrors what a downstream consumer would do in their own Gradle
// build: it doesn't change the compiler's deps, only the deps that
// land in their bytecode + runtime.
listOf(
    "compileClasspath",
    "runtimeClasspath",
    "testCompileClasspath",
    "testRuntimeClasspath",
).forEach { name ->
    configurations.named(name) {
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-reflect")
    }
}

dependencies {
    // Consumer-shaped: depend on the root agents-kt module the same way an
    // external user would (minus the maven coordinate; project() gives us
    // the same artifact graph). If the root accidentally re-introduces a
    // transitive kotlin-reflect, the exclusion above strips it.
    testImplementation(project(":"))
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}
