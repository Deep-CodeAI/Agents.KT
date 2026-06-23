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
    // AP2 (PRD §12.10) spike = the 1+1=3 composition: the root project carries A2A (AgentCard) + x402
    // (X402Client/Account/SpendPolicy); :agents-kt-identity carries the JOSE/JWS Verifiable-Credential
    // verifier a mandate reuses. No new third-party deps — nimbus comes transitively via identity.
    api(project(":"))
    api(project(":agents-kt-identity"))

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}
