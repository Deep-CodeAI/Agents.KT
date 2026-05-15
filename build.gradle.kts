plugins {
    kotlin("jvm") version "2.3.21"
    `maven-publish`
    signing
    id("info.solidsoft.pitest") version "1.19.0"
}

group = "ai.deep-code"
version = "0.4.6"

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
    // #1718 (v0.4.6): kotlin-reflect is now compileOnly for real. Every
    // remaining `kotlin.reflect.full.*` callsite identified in #1707 has
    // been wrapped via `ReflectionFallback.withReflection { ... }` or
    // replaced with the cache-aware `hasGenerableAnnotation()` probe.
    // Consumers without kotlin-reflect on their runtime classpath:
    //   - With KSP applied: full functionality (schema/description/construct
    //     reads come from generated constants; @Generable detection comes
    //     from the generated cache).
    //   - Without KSP: graceful degradation — `hasGenerableAnnotation`
    //     returns false, skill auto-descriptions return empty, branch
    //     exhaustiveness check is skipped, toLlmInput falls back to
    //     toString. Agent still runs; LLM output quality may suffer.
    //
    // Proof: `agents-kt-no-reflect-test` subproject — a consumer-shaped
    // smoke test whose classpath explicitly excludes kotlin-reflect.
    // The test asserts kotlin.reflect.full.KClasses is NOT loadable, then
    // exercises agent construction + typed-tool dispatch. Failure regresses
    // the contract.
    compileOnly("org.jetbrains.kotlin:kotlin-reflect:2.3.21")
    // Tests still drive both the generated and reflection paths.
    testImplementation("org.jetbrains.kotlin:kotlin-reflect:2.3.21")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")

    // #1695 — Dependabot's submitted dependency graph reads requested
    // versions, not resolved. The `force(...)` block above pins to 1.84 (a
    // patched release with no known CVEs per OSV + GHSA), but dependabot
    // still sees the Kotlin Gradle plugin's transitive request for 1.80 and
    // alerts on the 1.80-range vulnerabilities. Declaring 1.84 explicitly at
    // the project level — via `compileOnly`, which does NOT ship to
    // consumers and does NOT add to the runtime jar — gives dependabot an
    // explicit 1.84 node in the graph so it stops flagging the resolved-away
    // 1.80 vulnerabilities.
    compileOnly("org.bouncycastle:bcprov-jdk18on:1.84")
    compileOnly("org.bouncycastle:bcpg-jdk18on:1.84")
    compileOnly("org.bouncycastle:bcpkix-jdk18on:1.84")
    compileOnly("org.bouncycastle:bcutil-jdk18on:1.84")
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

// #982 — chat with a planner→executor Pipeline through the REPL. Same JavaExec
// pattern as `interactiveLiveShow` but exercises the
// LiveShow.from(pipeline: Pipeline<String, *>) overload.
tasks.register<JavaExec>("interactivePipeline") {
    description = "Manually drive a LiveShow REPL with a planner→executor Pipeline"
    group = "verification"
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("agents_engine.runtime.InteractivePipelineDemoKt")
    standardInput = System.`in`
}

// #984 — full swarm demo. Three sibling agents (fib / factor / exit) live as
// SEPARATE JAR files in build/tmp/jars_swarm_demo/, each with its own
// META-INF/services descriptor. The captain main is packaged inside fib.jar.
// At runtime, ServiceLoader walks the JARs on the classpath and finds all
// three providers — the same path a production swarm uses when JARs are
// dropped into a folder.
val swarmDemoJarsDir: Provider<Directory> = layout.buildDirectory.dir("tmp/jars_swarm_demo")

// Helper to register one swarm sibling Jar task. Each task pulls only its
// own subpackage's compiled classes plus its per-JAR service descriptor;
// no cross-JAR class sharing.
fun registerSwarmDemoJar(
    taskName: String,
    jarFileName: String,
    classSubpackage: String,
    resourcesPath: String,
) = tasks.register<Jar>(taskName) {
    description = "Pack swarm demo agent classes into $jarFileName"
    group = "build"
    dependsOn("compileTestKotlin")
    archiveFileName.set(jarFileName)
    destinationDirectory.set(swarmDemoJarsDir)
    sourceSets.test.get().output.classesDirs.forEach { classesDir ->
        from(classesDir) {
            include("agents_engine/runtime/swarmdemo/$classSubpackage/**")
        }
    }
    from(resourcesPath)
}

val jarSwarmFib = registerSwarmDemoJar(
    taskName = "jarSwarmFib",
    jarFileName = "fib.jar",
    classSubpackage = "fib",
    resourcesPath = "src/test/swarm-jar-resources/fib",
)
val jarSwarmFactor = registerSwarmDemoJar(
    taskName = "jarSwarmFactor",
    jarFileName = "factor.jar",
    classSubpackage = "factor",
    resourcesPath = "src/test/swarm-jar-resources/factor",
)
val jarSwarmExit = registerSwarmDemoJar(
    taskName = "jarSwarmExit",
    jarFileName = "exit.jar",
    classSubpackage = "exitagent",
    resourcesPath = "src/test/swarm-jar-resources/exit",
)
val jarSwarmRecap = registerSwarmDemoJar(
    taskName = "jarSwarmRecap",
    jarFileName = "recap.jar",
    classSubpackage = "recap",
    resourcesPath = "src/test/swarm-jar-resources/recap",
)

// Stage the framework JAR + every runtime dependency next to the demo
// JARs so the swarm demo is launchable with a pure `java -cp ...` command,
// no Gradle needed. Output goes to build/tmp/jars_swarm_demo_lib/.
tasks.register<Copy>("copySwarmDemoLibs") {
    description = "Stage framework + runtime libs next to the swarm demo JARs"
    group = "build"
    dependsOn("jar")  // produces build/libs/agents-kt-<version>.jar
    from(tasks.named("jar"))
    from(configurations.runtimeClasspath)
    into(layout.buildDirectory.dir("tmp/jars_swarm_demo_lib"))
}

// Aggregate task — builds all sibling demo JARs and stages their runtime deps.
tasks.register("buildSwarmDemoJars") {
    description = "Build the swarm demo JARs (and stage runtime libs) so the demo can be launched with bare `java`"
    group = "build"
    dependsOn(jarSwarmFib, jarSwarmFactor, jarSwarmExit, jarSwarmRecap, "copySwarmDemoLibs")
}

tasks.register<JavaExec>("swarmDemo") {
    description = "Run the swarm demo: captain `fib.jar` absorbs `factor.jar` + `exit.jar` + `recap.jar` siblings"
    group = "verification"
    dependsOn("buildSwarmDemoJars")

    // Classpath = framework runtime + the three sibling JARs ONLY. We
    // deliberately do NOT include sourceSets.test.runtimeClasspath, so
    // ServiceLoader finds providers exclusively from the JARs (proves the
    // real "drop JARs into a folder" path, not the in-test shortcut).
    classpath = files(
        sourceSets.main.get().runtimeClasspath,
        fileTree(swarmDemoJarsDir) { include("*.jar") },
    )
    mainClass.set("agents_engine.runtime.swarmdemo.fib.FibAgentKt")
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
