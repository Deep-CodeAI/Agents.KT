plugins {
    kotlin("jvm")
    application
}

group = "ai.deep-code"
version = rootProject.version

repositories {
    mavenCentral()
}

dependencyLocking {
    lockAllConfigurations()
}

dependencies {
    // Brings the manifest model + the Gradle-free ManifestEntrypointLoader, and
    // transitively the core agents-kt runtime the loader reflects into.
    implementation(project(":agents-kt-manifest"))
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
}

application {
    applicationName = "agents-kt"
    mainClass.set("agents_engine.cli.ManifestCliKt")
}

// Stamp Implementation-Version so `agents-kt --version` reports the real version
// (mirrors the core jar; falls back to "dev" when unpackaged). See ManifestCli.
tasks.jar {
    manifest {
        attributes("Implementation-Version" to project.version)
    }
}

tasks.test {
    useJUnitPlatform()
}
