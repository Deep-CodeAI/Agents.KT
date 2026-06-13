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
    // Pure JDK at runtime — no external deps. Core is test-only, for the in-JVM
    // round-trip (WhisperSttClient / QwenTtsClient hit this server).
    testImplementation(kotlin("test"))
    testImplementation(project(":"))
}

kotlin {
    jvmToolchain(21)
}

application {
    applicationName = "agents-kt-speech-server"
    mainClass.set("agents_engine.speechserver.SpeechServerMainKt")
}

tasks.test {
    useJUnitPlatform()
}
