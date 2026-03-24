plugins {
    kotlin("jvm") version "2.1.0"
    `maven-publish`
    signing
}

group = "ai.deep-code"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation(kotlin("reflect"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}

java {
    withSourcesJar()
    withJavadocJar()
}

publishing {
    publications {
        create<MavenPublication>("mavenCentral") {
            from(components["java"])

            artifactId = "agents-kt"

            pom {
                name.set("Agents.KT")
                description.set("Typed Kotlin DSL framework for AI agent systems")
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
