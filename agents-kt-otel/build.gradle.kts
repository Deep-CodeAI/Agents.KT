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

configurations.all {
    resolutionStrategy {
        force(
            "org.bouncycastle:bcprov-jdk18on:1.85",
            "org.bouncycastle:bcpg-jdk18on:1.85",
            "org.bouncycastle:bcpkix-jdk18on:1.85",
            "org.bouncycastle:bcutil-jdk18on:1.85",
        )
    }
}

dependencies {
    api(project(":agents-kt-observability"))
    // #2387 — 1.62.0 patches CVE in W3C Baggage propagation (unbounded
    // memory + CPU on oversized headers). Per-propagator caps at 8,192
    // bytes / 64 entries. Affects 1.51.0..1.61.0; no API changes touch us.
    api("io.opentelemetry:opentelemetry-api:1.63.0")

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation("io.opentelemetry:opentelemetry-sdk-trace:1.63.0")
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}
