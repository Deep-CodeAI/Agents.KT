plugins {
    kotlin("jvm")
    `maven-publish`
    signing
}

group = "ai.deep-code"
version = rootProject.version

repositories {
    mavenCentral()
}

dependencyLocking {
    lockAllConfigurations()
}

// #1695 — Mirror the BouncyCastle pin from the root build (#883 lineage).
// The Kotlin Gradle plugin pulls BC transitively into
// kotlinBouncyCastleConfiguration for jar signing; without these pins the
// ksp module's submitted dependency graph reads 1.80, which keeps the four
// Dependabot advisories alive even though the published jars are BC-free
// at runtime. See v0.4.3 release notes.
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
    // KSP processor API. KSP2 (2.x) is decoupled from the bundled Kotlin
    // compiler version, so the same KSP release works across a range of
    // Kotlin versions. See https://github.com/google/ksp.
    implementation("com.google.devtools.ksp:symbol-processing-api:2.3.9")

    // Read annotations defined in the runtime library (e.g. @Generable).
    // compileOnly — never end up on the consumer's runtime classpath; the
    // consumer already has the runtime jar via their own implementation(...).
    compileOnly(project(":"))

    // Explicit BC 1.84 nodes so Dependabot sees the pin (#1695). compileOnly
    // does NOT propagate to consumers — runtimeClasspath stays BC-free.
    compileOnly("org.bouncycastle:bcprov-jdk18on:1.84")
    compileOnly("org.bouncycastle:bcpg-jdk18on:1.84")
    compileOnly("org.bouncycastle:bcpkix-jdk18on:1.84")
    compileOnly("org.bouncycastle:bcutil-jdk18on:1.84")

    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
}

java {
    withSourcesJar()
    withJavadocJar()
}

publishing {
    publications {
        create<MavenPublication>("mavenCentral") {
            from(components["java"])

            artifactId = "agents-kt-ksp"

            pom {
                name.set("Agents.KT KSP processor")
                description.set("Compile-time KSP processor for Agents.KT — validates @Generable shape and (in later releases) generates JSON Schema + lenient parser code.")
                url.set("https://github.com/Deep-CodeAI/Agents.KT")

                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }

                developers {
                    developer {
                        id.set("kskobeltsyn")
                        name.set("Konstantin Skobeltsyn")
                        email.set("konstantin@deep-code.ai")
                    }
                }

                scm {
                    url.set("https://github.com/Deep-CodeAI/Agents.KT")
                    connection.set("scm:git:git://github.com/Deep-CodeAI/Agents.KT.git")
                    developerConnection.set("scm:git:ssh://git@github.com/Deep-CodeAI/Agents.KT.git")
                }
            }
        }
    }

    repositories {
        maven {
            name = "sonatype"
            url = uri("https://ossrh-staging-api.central.sonatype.com/service/local/staging/deploy/maven2/")
            credentials {
                username = findProperty("sonatypeUsername") as String? ?: ""
                password = findProperty("sonatypePassword") as String? ?: ""
            }
        }
    }
}

signing {
    val signingKey = findProperty("signing.key") as String?
    val signingPassword = findProperty("signing.password") as String?
    if (signingKey != null) {
        useInMemoryPgpKeys(signingKey, signingPassword ?: "")
    }
    sign(publishing.publications["mavenCentral"])
}

tasks.withType<Sign>().configureEach {
    onlyIf { findProperty("signing.key") != null }
}
