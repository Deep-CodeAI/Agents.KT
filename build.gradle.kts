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

dependencyLocking {
    lockAllConfigurations()
}

// #883 — Dependabot flagged high-severity CVEs in BouncyCastle 1.80, which the
// Kotlin Gradle plugin pulls transitively (kotlinBouncyCastleConfiguration, used
// for JAR signing). We don't declare BC directly. Force 1.84 across every
// resolved configuration so the lockfile and verification metadata pin the
// patched version.
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
        excludeTags("live-llm", "live-mcp", "interactive")
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

// #858 — supply-chain hygiene. After bumping a dependency, Gradle wrapper, or
// plugin, run `./gradlew updateVerificationMetadata` (or `gradlew.bat ...` on
// Windows) to refresh gradle/verification-metadata.xml.
//
// Why a Gradle task instead of a shell script: this works the same on macOS,
// Linux, and Windows. `gradlew` itself is the cross-platform entry point.
//
// What it does: invokes a second `gradlew` process with the right
// --write-verification-metadata flag and the task list that exercises every
// classpath the build actually uses (the bare `help` task only resolves the
// runtime classpath; plugin classpaths, test classpath, and Kotlin compiler
// plugin classpaths get missed).
//
// Sources/javadoc jars are exempted via <trusted-artifacts> in the metadata
// file — they're IDE-only, never on the runtime classpath.
tasks.register("updateVerificationMetadata") {
    description = "Regenerates gradle/verification-metadata.xml after a dependency or Gradle update."
    group = "verification"

    doLast {
        val isWindows = System.getProperty("os.name").lowercase().contains("windows")
        val gradlewCommand = if (isWindows) "gradlew.bat" else "./gradlew"

        val metadataFile = rootProject.file("gradle/verification-metadata.xml")
        require(metadataFile.exists()) {
            "gradle/verification-metadata.xml not found at ${metadataFile.absolutePath}"
        }

        val backup = File.createTempFile("verification-metadata", ".bak")
        metadataFile.copyTo(backup, overwrite = true)
        try {
            println("→ Snapshotted current metadata to ${backup.absolutePath}")
            println("→ Regenerating with --write-verification-metadata sha256")
            println("  (re-resolves the dependency graph; can take a few minutes")
            println("   on a first run after a Gradle update.)")
            println()

            val process = ProcessBuilder(
                gradlewCommand,
                "--write-verification-metadata", "sha256",
                "--refresh-dependencies",
                "help",
                ":dependencies", "--configuration", "runtimeClasspath",
                ":buildEnvironment",
                ":compileKotlin",
                ":compileTestKotlin",
            )
                .directory(rootProject.projectDir)
                .inheritIO()
                .start()
            val exitCode = process.waitFor()
            if (exitCode != 0) {
                throw GradleException("Gradle regeneration exited with code $exitCode")
            }

            // Defensive: confirm the trusted-artifacts block survived. Gradle's
            // merge behavior should preserve <configuration>, but if a future
            // Gradle version regresses we want a loud signal, not a silent
            // weakening of the verification posture.
            val regenerated = metadataFile.readText()
            if (!regenerated.contains("trusted-artifacts")) {
                println()
                println("⚠ <trusted-artifacts> block is missing from the regenerated file.")
                println("  Restoring from backup. Investigate the regeneration step.")
                backup.copyTo(metadataFile, overwrite = true)
                throw GradleException("regeneration stripped trusted-artifacts; aborted")
            }

            println()
            println("─".repeat(60))
            if (regenerated == backup.readText()) {
                println("✓ No changes — verification metadata is up to date.")
            } else {
                println("→ Metadata changed.")
                println()
                println("Review the diff:")
                println("  git diff gradle/verification-metadata.xml")
                println()
                println("If the new entries look reasonable (only artifacts you")
                println("expected to appear, with origin=\"Generated by Gradle\"):")
                println("  git add gradle/verification-metadata.xml")
                println()
                println("If anything looks off:")
                println("  git restore gradle/verification-metadata.xml")
            }
        } finally {
            backup.delete()
        }
    }
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

// #981 — manually drive a LiveShow REPL from your terminal. The Gradle Test
// task does not forward stdin, so we use JavaExec pointing at a main() under
// the test sourceset. The demo class never ships in the published JAR.
//
// Run: `./gradlew interactiveLiveShow --console=plain -q`
//   (`--console=plain` keeps Gradle's progress bar from interleaving with the
//    REPL prompt; `-q` silences task lifecycle noise.)
tasks.register<JavaExec>("interactiveLiveShow") {
    description = "Manually drive a LiveShow REPL with an echo agent"
    group = "verification"
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("agents_engine.runtime.InteractiveLiveShowDemoKt")
    standardInput = System.`in`
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
