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

dependencies {
    // #4505 — the speech SPI (SpeechToTextClient), Content/BlobStore live in core.
    api(project(":"))

    // NOTE: the actual whisper.cpp JNI binding (io.github.givimad:whisper-jni) is
    // NOT a dependency of this module. It is loaded through the `WhisperBackend`
    // seam by the consumer, so this module stays free of native artifacts and the
    // weights it would need. See README.md for the ~15-line binding.

    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}
