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
            // Security bump — jackson-databind arrives transitively via langchain4j-core at
            // 2.21.3; force it (and the tightly-coupled jackson-core) to 2.21.5, which clears
            // the 7 open advisories on the 2.21.x line: array-subtype allowlist bypass
            // (GHSA-rmj7-2vxq-3g9f), generic-parameter PTV bypass (GHSA-j3rv-43j4-c7qm),
            // the @JsonView / @JsonIgnoreProperties / renamed-@JsonIgnore-setter cases
            // (GHSA-rcqc-6cw3-h962, -9fxm-vc8v-hj55, -5hh8-q8hv-fr38, -hgj6-7826-r7m5), and
            // InetSocketAddress eager-DNS SSRF (GHSA-5jmj-h7xm-6q6v, fixed only in 2.21.5).
            "com.fasterxml.jackson.core:jackson-databind:2.21.5",
            "com.fasterxml.jackson.core:jackson-core:2.21.5",
        )
    }
}

dependencies {
    api(project(":agents-kt-rag"))
    // #3863 — locked to a stable minor; LangChain4j evolves its store API
    // independently, bump deliberately.
    api("dev.langchain4j:langchain4j-core:1.16.3")

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    // InMemoryEmbeddingStore for hermetic adapter tests.
    testImplementation("dev.langchain4j:langchain4j:1.16.3")
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}
