plugins {
    kotlin("jvm")
    `java-gradle-plugin`
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
    api(project(":"))
    testImplementation(kotlin("test"))
    testImplementation(gradleTestKit())
}

kotlin {
    jvmToolchain(21)
}

gradlePlugin {
    plugins {
        create("agentsKtManifest") {
            id = "ai.deep-code.agents-kt.manifest"
            implementationClass = "agents_engine.manifest.gradle.AgentsKtManifestPlugin"
        }
    }
}

tasks.test {
    useJUnitPlatform()
}
