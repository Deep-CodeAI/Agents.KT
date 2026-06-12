import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

plugins {
    kotlin("jvm") version "2.4.0"
    `maven-publish`
    signing
    id("info.solidsoft.pitest") version "1.19.0"
    // #2807 — detekt static analysis. Catches the categories the manual
    // audit under epic #2790 surfaced (magic numbers, dead code, over-
    // broad catch, long methods, high complexity, nested blocks). The
    // detekt-baseline.xml freezes the current violation count so the
    // build stays green on existing code; new violations fail.
    id("io.gitlab.arturbosch.detekt") version "1.23.8"
}

group = "ai.deep-code"
version = "0.7.25-SNAPSHOT"

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
    compileOnly("org.jetbrains.kotlin:kotlin-reflect:2.4.0")
    // Tests still drive both the generated and reflection paths.
    testImplementation("org.jetbrains.kotlin:kotlin-reflect:2.4.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("org.jline:jline:4.1.3")
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")

    // #2885 (epic #2882) — custom detekt rules that gate tool executor bodies.
    detektPlugins(project(":agents-kt-detekt"))

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

// #2807 — detekt config. The baseline freezes existing violations so
// the build stays green on this PR; new code must pass clean. The
// rule set is intentionally narrow at first — the audit (#2790)
// flagged exactly these categories; broader rules can be enabled in
// follow-up tickets as the codebase converges.
detekt {
    toolVersion = "1.23.8"
    config.setFrom(rootProject.file("detekt.yml"))
    buildUponDefaultConfig = true
    baseline = rootProject.file("detekt-baseline.xml")
    autoCorrect = false
}

tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
    jvmTarget = "21"
    reports {
        html.required.set(true)
        xml.required.set(true)
        sarif.required.set(false)
        md.required.set(false)
    }
}

tasks.withType<io.gitlab.arturbosch.detekt.DetektCreateBaselineTask>().configureEach {
    jvmTarget = "21"
}

// #2806 — single source of truth for the runtime-visible Agents.KT version.
// `BuildInfo.version` reads `Implementation-Version` from this manifest at
// runtime, falling back to "dev" when the class is loaded from a non-sealed
// classpath (tests, IDE runs against build/classes). Three separate const
// vals in McpServer / McpClient / McpRunner were drifting against the real
// project.version — this stamps the truth into the jar.
tasks.jar {
    manifest {
        attributes(
            "Implementation-Title" to "agents-kt",
            "Implementation-Version" to project.version.toString(),
        )
    }
}

tasks.test {
    useJUnitPlatform {
        // `live-cloud-api` tests (DeepSeek / Anthropic / OpenAI direct against
        // hosted APIs) stay in default `:test` so provider regressions are
        // caught alongside unit tests. They `assumeTrue(key != null)` to skip
        // cleanly when an API key is absent.
        //
        // `live-llm` covers everything *also* talking to Ollama Cloud
        // (`ollama.com`), which has been empirically flaky enough — EOF,
        // 500s, budget-exceeded, intermittent wrong outputs — that running
        // these on every `:test` produces too much noise. They stay opt-in
        // via `:integrationTest` / `testAll`.
        //
        // `live-mcp` requires an out-of-process MCP server; `interactive`
        // requires a human at the console.
        excludeTags("live-llm", "live-mcp", "interactive")
    }
}

// Show the full exception (message + stack) for failing tests, so a CI failure
// carries its assertion message — e.g. the bwrap stderr behind a linux_only
// ProcessSandbox failure — instead of a bare `AssertionFailedError`.
tasks.withType<Test>().configureEach {
    testLogging {
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        events("failed")
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
    // PIT DEFAULTS minus VOID_METHOD_CALLS. The dropped mutator targets
    // void-method invocations, which in Kotlin bytecode mostly produces
    // equivalent-mutant noise on compiler-synthesized calls:
    //   - `Intrinsics::checkNotNullParameter`  (Kotlin non-null param checks)
    //   - `Intrinsics::checkNotNullExpressionValue`  (Kotlin !! safety)
    //   - `InlineMarker::finallyStart` / `finallyEnd`  (inline-function markers)
    //   - `CollectionsKt::throwIndexOverflow`  (forEachIndexed overflow guard)
    // These are language-level guarantees; removing them doesn't change
    // observable behavior for legal inputs. Per #889's "don't chase
    // equivalent mutants" note, dropping the mutator gives the cleanest
    // signal-to-noise improvement without writing more tests.
    // Trade-off: also drops ~5 legitimate-but-rare mutants on real void
    // calls (e.g. removed `skipWs()`). Worth it — those are mostly
    // partially-redundant with adjacent calls anyway. Revisit when adding
    // an `arcmutate-kotlin-equivalence-filter` plugin for per-call-target
    // filtering.
    //
    // NULL_RETURNS is KEPT but produces known equivalent-mutant noise on
    // `suspend fun ... ): Unit` methods. The Kotlin compiler lowers such
    // functions to JVM `Object foo(Continuation)` (returns either
    // `kotlin.Unit` or `COROUTINE_SUSPENDED`), so PIT applies NULL_RETURNS
    // and the mutated `return null` is observationally indistinguishable
    // from `return Unit` for any caller. Concrete impact (PIT 2026-05-19):
    // 26 SURVIVED+NO_COVERAGE in `ClaudeClient.dispatchSseEvent` are this
    // pattern, plus ~7 more in `parseSseStream`/`lambda$*$dispatch`.
    // Don't chase these — same convention as the lambda$N inline-attribution
    // and VOID_METHOD_CALLS noise. Dropping NULL_RETURNS entirely would
    // lose real coverage on object-returning functions (`parseResponse`,
    // `materializeSnapshot`, etc), which is a worse trade.
    mutators.set(setOf(
        "CONDITIONALS_BOUNDARY",
        "INCREMENTS",
        "INVERT_NEGS",
        "MATH",
        "NEGATE_CONDITIONALS",
        "EMPTY_RETURNS",
        "FALSE_RETURNS",
        "TRUE_RETURNS",
        "NULL_RETURNS",
        "PRIMITIVE_RETURNS",
    ))
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
                // #2885 — the agents-kt-detekt module brings new detekt-api /
                // detekt-test deps not used anywhere else, so its classpaths must
                // be exercised here or their checksums never get written.
                ":agents-kt-detekt:compileKotlin",
                ":agents-kt-detekt:compileTestKotlin",
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

// #1720 — single entry point for "run everything before pushing":
//   - root :test (unit + `live-cloud-api` — DeepSeek / Anthropic / OpenAI
//     hosted APIs; assume-skip when key absent)
//   - every subproject :test (KSP processor, no-reflect smoke, observability
//     bridge, OTel / LangSmith / Langfuse adapters, permission manifest)
//   - :integrationTest (live-llm — Ollama-Cloud-dependent slice; flaky
//     enough that we keep it out of default `:test` but still gate releases
//     on it via this aggregator)
//   - :mcpIntegrationTest (live-mcp — needs MCP_REDMINE_URL)
//
// CI keeps using `check`, which is unit-only — the live tasks need infra CI
// doesn't have. testAll is for the developer who wants one command for the
// full gate before release-cut.
tasks.register("testAll") {
    description = "Runs every test task across every subproject — unit + live-cloud-api in :test, KSP, no-reflect smoke, all 0.6.0 modules, live-llm (Ollama), live-mcp."
    group = "verification"
    dependsOn(
        ":test",
        ":agents-kt-ksp:test",
        ":agents-kt-no-reflect-test:test",
        ":agents-kt-manifest:test",
        ":agents-kt-cli:test",
        ":agents-kt-detekt:test",
        ":agents-kt-observability:test",
        ":agents-kt-otel:test",
        ":agents-kt-langsmith:test",
        ":agents-kt-langfuse:test",
        ":integrationTest",
        ":mcpIntegrationTest",
    )
}

// #2873 — release guard. The README advertises the dependency coordinate
// `ai.deep-code:agents-kt:<version>`; if that drifts from the actual Gradle
// project version we ship a README pointing at the wrong (or non-existent)
// artifact — the exact drift an external 0.7.0 review flagged. Wired into
// `check`, so CI fails the moment the two disagree.
tasks.register("checkReadmeVersion") {
    description = "Fails if README.md's agents-kt dependency version differs from the project version."
    group = "verification"
    val readmeFile = rootProject.file("README.md")
    val projectVersion = project.version.toString()
    inputs.file(readmeFile)
    inputs.property("version", projectVersion)
    doLast {
        val declared = Regex("""ai\.deep-code:agents-kt:([0-9]+\.[0-9]+\.[0-9]+[^"]*)""")
            .find(readmeFile.readText())?.groupValues?.get(1)
        requireNotNull(declared) {
            "No `ai.deep-code:agents-kt:<version>` dependency snippet found in README.md."
        }
        if (projectVersion.endsWith("-SNAPSHOT")) {
            // Unreleased main: the README must keep advertising the last *published*
            // release — a plain version strictly below the snapshot base. Exact
            // lockstep resumes at release time (runbook step 6).
            check(!declared.contains("-SNAPSHOT")) {
                "README.md advertises agents-kt:$declared — never advertise a -SNAPSHOT; " +
                    "keep the last published release in the snippet until runbook step 6."
            }
            val base = projectVersion.removeSuffix("-SNAPSHOT").split('.').map { it.toInt() }
            val advertised = declared.split('.').map { it.toInt() }
            val cmp = advertised.zip(base).map { (a, b) -> a.compareTo(b) }
                .firstOrNull { it != 0 } ?: advertised.size.compareTo(base.size)
            check(cmp < 0) {
                "README.md advertises agents-kt:$declared but main is $projectVersion. " +
                    "On a -SNAPSHOT main the README must advertise the last published release " +
                    "(below ${base.joinToString(".")}), per docs/RELEASE_RUNBOOK.md."
            }
        } else {
            check(declared == projectVersion) {
                "README.md advertises agents-kt:$declared but the project version is $projectVersion. " +
                    "Keep the README dependency snippet in sync with the Gradle version (#2873)."
            }
        }
    }
}

tasks.named("check") { dependsOn("checkReadmeVersion") }

// #3084 (de-slop #3083): the README/Gradle drift guard (`checkReadmeVersion`) can't catch the
// drift that actually bit us — advertising a version that isn't on Central yet. This task HEADs
// the Central artifact URL for the *current* project version and fails if it isn't resolvable.
// Deliberately NOT wired into `check`: it needs network and would (correctly) fail during normal
// dev on an unreleased version. Run it manually as the last pre-announce gate:
//   ./gradlew checkPublishedVersion
// See docs/RELEASE_RUNBOOK.md for where it sits in the release order.
tasks.register("checkPublishedVersion") {
    description = "Fails unless the current project version is resolvable on Maven Central (manual, pre-announce)."
    group = "verification"
    val projectVersion = project.version.toString()
    // Override for staging/mirror checks: -PcentralBaseUrl=https://repo1.maven.org/maven2
    val baseUrl = (findProperty("centralBaseUrl") as String?)?.trimEnd('/')
        ?: "https://repo1.maven.org/maven2"
    val coordinates = listOf("agents-kt", "agents-kt-ksp")
    doLast {
        val missing = coordinates.filterNot { artifact ->
            val url = "$baseUrl/ai/deep-code/$artifact/$projectVersion/$artifact-$projectVersion.pom"
            val conn = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
                requestMethod = "HEAD"
                connectTimeout = 10_000
                readTimeout = 10_000
                instanceFollowRedirects = true
            }
            val code = try { conn.responseCode } catch (e: IOException) { -1 } finally { conn.disconnect() }
            (code == 200).also { ok ->
                logger.lifecycle("checkPublishedVersion: $artifact:$projectVersion -> ${if (ok) "published" else "MISSING (HTTP $code)"}")
            }
        }
        check(missing.isEmpty()) {
            "Version $projectVersion is not resolvable on Central for: ${missing.joinToString()}. " +
                "Do NOT advertise or announce $projectVersion until every artifact returns HTTP 200 " +
                "from $baseUrl — publish the bundle first (docs/RELEASE_RUNBOOK.md, #3084)."
        }
    }
}
// #3089 (de-slop #3083) — detekt baseline burndown. The baseline grandfathers existing
// violations; without a ceiling it can silently grow, turning "0 new smells" into a lie.
// Snapshot the count and fail if it ever increases — new violations get fixed, not appended.
// Ratchet DETEKT_BASELINE_CEILING down (never up) as the count drops.
val detektBaselineCeiling = 423
tasks.register("checkDetektBaseline") {
    description = "Fails if detekt-baseline.xml grows beyond the recorded ceiling ($detektBaselineCeiling)."
    group = "verification"
    val baselineFile = rootProject.file("detekt-baseline.xml")
    inputs.file(baselineFile)
    inputs.property("ceiling", detektBaselineCeiling)
    doLast {
        val count = baselineFile.readText().split("</ID>").size - 1
        logger.lifecycle("checkDetektBaseline: $count / $detektBaselineCeiling baselined violations")
        check(count <= detektBaselineCeiling) {
            "detekt-baseline.xml has grown to $count entries (ceiling $detektBaselineCeiling). " +
                "Fix new violations instead of baselining them (#3089). The baseline must only shrink."
        }
    }
}
tasks.named("check") { dependsOn("checkDetektBaseline") }

// #3199 (SRP / one-type-per-file) — fail if a main-source .kt file declares more than one
// top-level type, unless it's on the allowlist. The allowlist is a ratchet that may only shrink:
// the guard also fails on a STALE entry (a listed file that no longer violates) so splits can't be
// done without removing the file from the list. Mirrors checkReadmeVersion / checkDetektBaseline.
val oneTypePerFileAllowlist = rootProject.file("config/one-type-per-file-allowlist.txt")
tasks.register("checkOneTypePerFile") {
    description = "Fails if a main-source .kt file declares >1 top-level type and isn't allowlisted (#3199)."
    group = "verification"
    inputs.file(oneTypePerFileAllowlist)
    val srcTree = rootProject.fileTree(rootProject.projectDir) {
        include("src/main/kotlin/**/*.kt", "agents-kt-*/src/main/kotlin/**/*.kt")
        exclude("**/build/**")
    }
    inputs.files(srcTree)
    doLast {
        // Matches the shell heuristic used to seed the allowlist:
        //   ^([a-z]+ )*(class|interface|object)<space>   (top-level, any visibility; nested decls are indented).
        val typeDecl = Regex("^([a-z]+ )*(class|interface|object) ")
        val rootPath = rootProject.projectDir.toPath()
        val violating = srcTree.files
            .filter { f -> f.readLines().count { typeDecl.containsMatchIn(it) } >= 2 }
            .map { rootPath.relativize(it.toPath()).toString().replace('\\', '/') }
            .toSortedSet()
        val allowed = oneTypePerFileAllowlist.readLines()
            .map { it.substringBefore('#').trim() }
            .filter { it.isNotEmpty() }
            .toSortedSet()

        val newViolations = violating - allowed
        val stale = allowed - violating
        val problems = buildList {
            if (newViolations.isNotEmpty()) {
                add(
                    "These files declare >1 top-level type and are NOT allowlisted — split them into one " +
                        "type per file (#3199), or, for a cohesive sealed ADT, add the path to " +
                        "${oneTypePerFileAllowlist.name} with a '# sealed-ADT: keep' reason:\n  " +
                        newViolations.joinToString("\n  ")
                )
            }
            if (stale.isNotEmpty()) {
                add(
                    "These allowlist entries no longer declare >1 top-level type — remove them " +
                        "(the allowlist may only shrink):\n  " + stale.joinToString("\n  ")
                )
            }
        }
        check(problems.isEmpty()) { "checkOneTypePerFile (#3199):\n\n" + problems.joinToString("\n\n") }
        logger.lifecycle("checkOneTypePerFile: ${violating.size} multi-type files, all allowlisted (${allowed.size} entries)")
    }
}
tasks.named("check") { dependsOn("checkOneTypePerFile") }

// #3089 — an explicit, named security gate. These deterministic security/enforcement tests
// already run inside the default `:test` + module tests; this task makes the security-critical
// subset addressable on its own, so CI (and a future macOS Seatbelt job) can target it directly
// and it can't be silently dropped. OS-specific confinement tests (Seatbelt = mac, bwrap/firejail
// = linux) skip cleanly off-platform via @EnabledOnOs / assumeTrue.
tasks.register<Test>("securityTest") {
    description = "Deterministic security suite: sandbox confinement, tool-policy enforcement, manifest guard, arg-size cap."
    group = "verification"
    useJUnitPlatform()
    classpath = sourceSets.test.get().runtimeClasspath
    testClassesDirs = sourceSets.test.get().output.classesDirs
    filter {
        includeTestsMatching("agents_engine.sandbox.*")                 // ProcessSandbox write-confinement
        includeTestsMatching("agents_engine.core.ToolPolicy*")          // declared-policy enforcement (#1916)
        includeTestsMatching("agents_engine.core.SnapshotManifestGuardTest")
        includeTestsMatching("agents_engine.model.MaxToolArgsBytesTest") // arg-size cap (#2888)
    }
}

// Aggregate gate spanning modules: the runtime security tests above, the audit-ledger
// tamper-evidence (observability), and the static tool-body rule (detekt module + the detekt
// run itself). This is the required security gate referenced in TESTING.md.
tasks.register("securityCheck") {
    description = "Required security gate: runtime enforcement + audit-ledger tamper-evidence + static tool-body rules."
    group = "verification"
    dependsOn("securityTest")
    dependsOn(":agents-kt-observability:securityTest")
    dependsOn(":agents-kt-detekt:test")
    dependsOn("detekt")
}
tasks.register<Test>("integrationTest") {
    description = "Runs live-llm integration tests (Ollama / Ollama Cloud). Hosted-API live tests run in default :test under live-cloud-api."
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

// #2892 — the Linux ProcessSandbox backend (bwrap / firejail) needs a real Linux
// kernel, so its integration tests are tagged `linux_only` (+ @EnabledOnOs(OS.LINUX)).
// They run directly on Linux / CI and auto-skip on macOS, where there is no Linux
// kernel to confine against (CI on a native Ubuntu runner is the verifier).
tasks.register<Test>("linuxSandboxTest") {
    description = "Runs @Tag(\"linux_only\") sandbox tests (Linux kernel required; auto-skips on macOS)."
    group = "verification"
    useJUnitPlatform {
        includeTags("linux_only")
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

// #1837 — run the InternalsAgent MCP server. Exposes every framework
// source file's adjunct as an MCP tool. Default port 8765; override via
// `--args="<port>"`. See docs/internals-agent.md for IDE wiring.
tasks.register<JavaExec>("runInternalsAgent") {
    description = "Run the InternalsAgent MCP server (default port 8765)"
    group = "application"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("agents_engine.runtime.internals.MainKt")
}

// #1837 — guardrail. Every `src/main/resources/internals-agent/**/*.md`
// must begin with a YAML-style frontmatter block carrying a non-blank
// `description:` line. The runtime InternalsAgent's classpath scanner
// fails fast at agent construction if any adjunct lacks frontmatter, but
// that's a runtime failure — we want CI to catch it BEFORE the change
// ships. This task is the build-time gate. Wired into `check` so every
// `./gradlew check` (and therefore every CI run) validates the layout.
tasks.register("validateInternalsAdjuncts") {
    description = "Validates that every internals-agent/*.md has the required `description:` frontmatter"
    group = "verification"
    val adjunctRoot = layout.projectDirectory.dir("src/main/resources/internals-agent")
    inputs.dir(adjunctRoot).withPathSensitivity(org.gradle.api.tasks.PathSensitivity.RELATIVE)
    // No outputs — task is pure validation. Mark as up-to-date when inputs unchanged.
    outputs.upToDateWhen { true }
    doLast {
        val root = adjunctRoot.asFile
        if (!root.exists()) {
            logger.lifecycle("No internals-agent directory at ${root.path} — nothing to validate.")
            return@doLast
        }
        val violations = mutableListOf<String>()
        root.walkTopDown()
            .filter { it.isFile && it.extension == "md" }
            .forEach { md ->
                val rel = md.relativeTo(root).invariantSeparatorsPath
                val content = md.readText(Charsets.UTF_8)
                when {
                    !content.startsWith("---\n") ->
                        violations += "$rel: missing leading `---` frontmatter block"

                    content.indexOf("\n---\n", startIndex = 4) < 0 ->
                        violations += "$rel: unterminated frontmatter block (no closing `---`)"

                    else -> {
                        val end = content.indexOf("\n---\n", startIndex = 4)
                        val frontmatter = content.substring(4, end)
                        val descLine = frontmatter.lineSequence()
                            .firstOrNull { it.startsWith("description:") }
                        when {
                            descLine == null ->
                                violations += "$rel: frontmatter missing `description:` line"

                            descLine.removePrefix("description:").trim().isEmpty() ->
                                violations += "$rel: `description:` line is blank"
                        }
                    }
                }
            }
        if (violations.isNotEmpty()) {
            val report = violations.joinToString("\n  - ", prefix = "  - ")
            throw GradleException(
                "Internals-agent adjunct validation failed (${violations.size} issue(s)):\n$report\n\n" +
                    "Every internals-agent/*.md must start with:\n" +
                    "  ---\n" +
                    "  description: <one-line tool description shown to the IDE LLM>\n" +
                    "  ---\n\n" +
                    "  <markdown body>"
            )
        }
        val count = root.walkTopDown().count { it.isFile && it.extension == "md" }
        logger.lifecycle("Validated $count internals-agent adjunct(s) — all have `description:` frontmatter.")
    }
}

tasks.named("check") { dependsOn("validateInternalsAdjuncts") }

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

        // #1927 — secondary distribution channel. NOT a Maven Central replacement.
        // Use for: CI snapshots (Central doesn't accept them from outside OSSRH),
        // PR-preview builds, Sonatype-outage redundancy, authenticated early-access
        // for collaborators without hosting our own Nexus.
        //
        // Auth: GITHUB_ACTOR / GITHUB_TOKEN are auto-provisioned inside GitHub
        // Actions runs for same-repo packages — no PAT needed. For local
        // publishing, set gpr.user / gpr.key in ~/.gradle/gradle.properties
        // (NOT this repo's gradle.properties). See PUBLISHING.md for the
        // consumer-side wiring + when to use which channel.
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/Deep-CodeAI/Agents.KT")
            credentials {
                username = findProperty("gpr.user") as String? ?: System.getenv("GITHUB_ACTOR")
                password = findProperty("gpr.key") as String? ?: System.getenv("GITHUB_TOKEN")
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
