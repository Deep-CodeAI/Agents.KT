# Testing Agents.KT

This is the in-repo contributor guide. The full IDE setup / build prerequisites live in the [Building From Source wiki page](https://github.com/Deep-CodeAI/Agents.KT/wiki/Building-From-Source); everything below is what you need once you've cloned and `./gradlew build` works.

## Quick start

```bash
./gradlew test
```

Runs the default unit suite across every Gradle module:

| Task | What it proves |
|---|---|
| `:test` | Root framework unit tests. Excludes `live-llm`, `live-mcp`, and `interactive` tags so no external services are needed. |
| `:agents-kt-ksp:test` | KSP processor — `@Generable` shape validation, schema emitter, `constructFromMap` emitter. |
| `:agents-kt-no-reflect-test:test` | Smoke test that excludes `kotlin-reflect` from its consumer-shaped classpaths and asserts the framework still works. Pins v0.4.6's "`kotlin-reflect` is genuinely optional" contract. |

A clean run is ~10 seconds on an M-series Mac after the daemon is warm.

## Run everything before pushing

```bash
./gradlew testAll
```

Five tasks chained: the three above plus `integrationTest` (live-llm) and `mcpIntegrationTest` (live-mcp). Use this when you're about to push or cut a release. CI does **not** run this — live tests need infra CI doesn't have. See `build.gradle.kts` for the registration.

## Live integration tests

These are tagged so the default suite skips them. Each task `includeTags` exactly its tag.

### `./gradlew integrationTest` — live-llm

Needs a local Ollama at `http://localhost:11434`. Pull the model the tests use:

```bash
ollama pull llama3.2
```

Then run. The tests exercise real prompt → response → tool-call paths. They are flakier than unit tests (model output varies); a single retry on flake is normal.

### `./gradlew mcpIntegrationTest` — live-mcp

Needs the `MCP_REDMINE_URL` environment variable pointing at a running MCP server (typically `http://localhost:8088` for the local demo MCP). The tests exercise the framework's MCP client + server surfaces against a real peer.

```bash
export MCP_REDMINE_URL=http://localhost:8088
./gradlew mcpIntegrationTest
```

Skips silently when the env var is unset.

## Running a single test

`--tests` propagates to every `Test` task in the build. If your test class only exists in one module, scope explicitly:

```bash
# good — only the root suite tries this filter
./gradlew :test --tests "agents_engine.generation.ReflectionFallbackTest"

# bad — fails because the smoke subproject doesn't have this class
./gradlew test --tests "agents_engine.generation.ReflectionFallbackTest"
```

Single test method:

```bash
./gradlew :test --tests "agents_engine.generation.ReflectionFallbackTest.withReflection*KotlinReflection*"
```

Wildcards work; quoting matters because the dash is shell-special in some shells.

## Mutation testing

```bash
./gradlew pitest
```

Pitest flips operators, swaps return values, and removes statements in the source, then re-runs the suite. Surviving mutants identify code paths the tests touch but don't actually verify. Worth running:

- Before a release.
- After landing a non-trivial refactor.
- When you're suspicious that a test "passes too easily."

Report: `build/reports/pitest/index.html`. Threshold is currently advisory; failing mutants don't fail the build, but each one is a question worth answering.

## Writing a test against the framework

Most framework tests don't need a live LLM. The pattern is a stub `ModelClient`:

```kotlin
import agents_engine.model.LlmMessage
import agents_engine.model.LlmResponse
import agents_engine.model.ModelClient
import agents_engine.model.TokenUsage

val stub = ModelClient { messages: List<LlmMessage> ->
    // Inspect messages, return whatever shape your test needs.
    LlmResponse.Text("canned response", TokenUsage(promptTokens = 1, completionTokens = 1))
}
```

`ModelClient` is a `fun interface` so a lambda works. Wire it into an agent the same way a real adapter would:

```kotlin
val agent = agent<String, String>("test-agent") {
    model(stub)
    prompt("You're a helper.")
    skills {
        skill<String, String>("greet") {
            tools()  // agentic skill — driven by the stub above
        }
    }
}
```

Two canonical patterns to crib from:

- **Synchronous unit test** — see `src/test/kotlin/agents_engine/model/ModelClientChatStreamDefaultTest.kt`. Inline stub via `ModelClient { _ -> ... }`, asserts a Flow output.
- **Whole-loop test with a fake provider** — see `src/test/kotlin/agents_engine/model/AgenticLoopTest.kt`. Multi-turn stub that returns different responses per call to exercise tool-call → result → final-text sequences.

### Reflection-fallback paths

If you change anything in `ReflectionFallback` or any wrapped `kotlin.reflect.full.*` callsite, **also** add or update assertions in `agents-kt-no-reflect-test/src/test/kotlin/smoke/`. The main suite has `kotlin-reflect` on its `testImplementation` — it cannot catch a regression where the reflect-absent branch breaks. The smoke subproject is the only place that can.

## Tags

| Tag | Meaning | Default suite | Where to use |
|---|---|---|---|
| `live-llm` | Needs a running Ollama (or another LLM provider in a test that overrides the model). | Excluded | Live integration tests that exercise actual prompt → response. |
| `live-mcp` | Needs `MCP_REDMINE_URL` to point at a running MCP server. | Excluded | Live MCP client/server interop. |
| `interactive` | Needs a TTY (a human typing at the REPL). | Excluded | LiveShow / interactive REPL tests. Not runnable in CI. |

Apply via JUnit Platform:

```kotlin
@org.junit.jupiter.api.Tag("live-llm")
class MyLiveTest {
    @Test fun `talks to a real Ollama`() { ... }
}
```

## Dependency verification

After bumping any dependency, the Gradle wrapper, or a plugin:

```bash
./gradlew updateVerificationMetadata
```

This regenerates `gradle/verification-metadata.xml` against the resolved graph. Review the diff (`git diff gradle/verification-metadata.xml`); only commit if the changes are explainable. Spurious additions mean a transitive dep showed up where you didn't expect it.

Dependency locking is also on: `gradle.lockfile` (and per-subproject lockfiles) pin the resolved versions. To rewrite after a deliberate bump:

```bash
./gradlew dependencies --write-locks
```

## What's NOT here yet

- **AgentUnit testing framework** — the typed mock-LLM + assertion DSL is on the roadmap (README's comparison table calls it out). Until it lands, the "stub `ModelClient` + assert on outputs" pattern above is the recommended approach.
- **Per-adapter live test matrices** — only Ollama has a `live-llm` integration suite. Anthropic / OpenAI live tests are runnable manually with API keys but aren't currently wired into `integrationTest`.

If you add a new subproject, register its test task as a `dependsOn` of `testAll` in the root `build.gradle.kts` so the discoverable single-command entry point stays complete.
