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
            "org.bouncycastle:bcprov-jdk18on:1.84",
            "org.bouncycastle:bcpg-jdk18on:1.84",
            "org.bouncycastle:bcpkix-jdk18on:1.84",
            "org.bouncycastle:bcutil-jdk18on:1.84",
        )
    }
}

dependencies {
    api(project(":"))
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}

// #3089 (de-slop #3083) — the audit-ledger tamper-evidence slice of the root `securityCheck`
// aggregate gate. Runs the Merkle-chain verify() tests on their own so the security gate can
// target them directly.
tasks.register<Test>("securityTest") {
    description = "Runs the tamper-evident audit-ledger (ToolAuditLedger) tests."
    group = "verification"
    useJUnitPlatform()
    classpath = sourceSets.test.get().runtimeClasspath
    testClassesDirs = sourceSets.test.get().output.classesDirs
    filter { includeTestsMatching("agents_engine.observability.ToolAuditLedgerTest") }
}
