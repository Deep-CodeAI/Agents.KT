plugins {
    kotlin("jvm") version "2.3.21"
    `maven-publish`
    signing
    id("info.solidsoft.pitest") version "1.19.0"
}

group = "ai.deep-code"
version = "0.2.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-reflect:2.3.21")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform {
        excludeTags("live-llm", "live-mcp")
    }
}

// Mutation testing — perturbs the source (flips operators, swaps return values,
// removes statements) and re-runs the suite. Surviving mutants identify code paths
// the tests touch but don't actually verify. See #836.
//
// Run: `./gradlew pitest`. HTML report: build/reports/pitest/index.html
// Uses the default `test` task (which already excludes live-llm / live-mcp tags).
pitest {
    junit5PluginVersion.set("1.2.1")
    targetClasses.set(setOf("agents_engine.*"))
    targetTests.set(setOf("agents_engine.*"))
    threads.set(Runtime.getRuntime().availableProcessors())
    outputFormats.set(setOf("HTML", "XML"))
    timestampedReports.set(false)
    // Match the default `test` task: skip tests that need a live Ollama or MCP server.
    excludedGroups.set(setOf("live-llm", "live-mcp"))
}

tasks.register<Test>("integrationTest") {
    description = "Runs integration tests that require a live LLM (Ollama)"
    group = "verification"
    useJUnitPlatform {
        includeTags("live-llm")
    }
    classpath = sourceSets.test.get().runtimeClasspath
    testClassesDirs = sourceSets.test.get().output.classesDirs
}

tasks.register<Test>("mcpIntegrationTest") {
    description = "Runs integration tests that require a live MCP server (set MCP_REDMINE_URL)"
    group = "verification"
    useJUnitPlatform {
        includeTags("live-mcp")
    }
    classpath = sourceSets.test.get().runtimeClasspath
    testClassesDirs = sourceSets.test.get().output.classesDirs
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
