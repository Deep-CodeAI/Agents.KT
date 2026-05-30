# Agents.KT v0.6.5 — Multimodal goes live + production timeout hotfix

**Release date:** 2026-05-30

0.6.5 is a **feature + hotfix** release on top of 0.6.4: vision input + typed attachments land across every provider, a `Files.load(path, store)` convenience surface for the typed `Content` hierarchy, an end-to-end eval harness, and a focused fix for a production field report on the hardcoded request timeout. The tagline:

> 0.6.5 makes Agents.KT a real multimodal runtime — and makes long Sonnet turns stop dying at 60 seconds.

The product identity is unchanged: **auditable Kotlin agent runtime for regulated JVM teams**.

```kotlin
implementation("ai.deep-code:agents-kt:0.6.5")
implementation("ai.deep-code:agents-kt-ksp:0.6.5")           // optional but recommended
implementation("ai.deep-code:agents-kt-manifest:0.6.5")      // permission manifests
implementation("ai.deep-code:agents-kt-observability:0.6.5") // JSONL audit + ObservabilityBridge
// optional bridges
implementation("ai.deep-code:agents-kt-otel:0.6.5")
implementation("ai.deep-code:agents-kt-langsmith:0.6.5")
implementation("ai.deep-code:agents-kt-langfuse:0.6.5")
```

Drop-in for 0.6.4. No API renames, no removed methods. All new surface is additive; the timeout floor change is the only behavior change and it's strictly more lenient.

---

## What ships in 0.6.5

### Fixed — Hardcoded 60s LLM request timeout (#2850)

A downstream production agent on `claude-opus-4-7` consistently breached the hardcoded 60-second cap on the JDK `HttpClient` during long Sonnet turns (extended thinking, tool-heavy multi-step loops, large outputs). The result was `HttpTimeoutException: request timed out` mid-stream and a torn-down `Flow`. The bug had been present since the JDK-HttpClient rewrite, and there was no DSL knob to raise the cap without forking the adapter.

0.6.5 does two things:

1. **Bumps `DEFAULT_REQUEST_TIMEOUT` from `60.seconds` → `300.seconds`** on `ClaudeClient`, `OpenAiClient`, `OllamaClient`. `DeepSeekClient` inherits from `OpenAiClient.DEFAULT_REQUEST_TIMEOUT`, so it picks up the bump too. `DEFAULT_CONNECT_TIMEOUT` stays at `10.seconds` — healthy networks never spend that long on TCP connect.
2. **Exposes the timeouts on the `model { }` DSL** — `requestTimeout: Duration?` and `connectTimeout: Duration?` on `ModelConfig` and `ModelBuilder`. Null falls back to the adapter's `DEFAULT_*` constants; non-null overrides on every provider through `ModelConfig.requestTimeout` / `connectTimeout` → `AgenticLoop.defaultClientFor()` → adapter ctor. No shared global; per-agent, per-config, per-test.

```kotlin
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

agent<String, String>("solver") {
    model {
        claude("claude-opus-4-7"); apiKey = System.getenv("ANTHROPIC_API_KEY")
        requestTimeout = 10.minutes      // raise the cap for very long turns
        connectTimeout = 5.seconds       // rarely needs tuning; exposed for symmetry
    }
}
```

Additive only — every 0.6.4 caller compiles and runs unchanged. Pinned by `ModelConfigTest` (defaults null on the DSL, overrides flow through, every adapter's `DEFAULT_REQUEST_TIMEOUT` pinned to `300.seconds`).

### Added — Vision input across all providers (#2470 slice a)

`LlmMessage.images: List<ImagePart>? = null` lands as the wire-level vision channel. `ImagePart(base64, wireMime)` carries a closed `WireMime` sealed type (`Png`, `Jpeg`, `Gif`, `Webp`) — `String` mime is intentionally not accepted in the public constructor. Every built-in provider translates vision on `role = "user"` messages to its native wire shape:

- **Ollama** — `{role:"user", content:"text", images:["<b64>", ...]}`. Works with `qwen3-vl:8b`, `llava`, `llama3.2-vision`. Non-vision models silently ignore the field.
- **Claude** — typed content array `[{type:"text"}, {type:"image", source:{type:"base64", media_type:"image/png", data:"<b64>"}}, ...]`. All Claude vision models (Haiku 4.5, Sonnet 4.6, Opus 4.7).
- **OpenAI** — typed content array `[{type:"text"}, {type:"image_url", image_url:{url:"data:image/png;base64,<b64>"}}, ...]`. gpt-4o, gpt-4o-mini, gpt-4-turbo, the o* reasoning models.
- **DeepSeek** — inherits the OpenAI shape; current DeepSeek models lack vision and ignore the field. Shape-tested; no live call.

Role-gated: non-user messages with non-null `images` ignore the field on the wire. Programmatic vision fixtures (`VisionFixtures.threeSquaresPng()`, `housePng()`) render reproducible PNGs via `BufferedImage` + `ImageIO` — byte-for-byte identical across machines and CI, no binary assets in the repo. Live tests run on Ollama (`live-llm` tag, `:integrationTest`), Claude + OpenAI (`live-cloud-api` tag, `:test` with `assumeTrue` skipping when no key) at `temperature = 0`, `maxTokens = 80` — cheap enough to run on every release.

### Added — Typed agent attachments (#2470 slice b)

`agent.invokeWithAttachments(input, attachments)` + the suspending sibling `invokeSuspendWithAttachments` give callers a typed-`Content` entry point above the wire-level `ImagePart` work. The runtime dereferences each `Content.Image`'s ref against the agent's injected `BlobStore`, base64-encodes once, and attaches `ImagePart` to the first user `LlmMessage`. `Agent.blobStore: BlobStore?` + `blobStore(store)` DSL slot are the injection point — optional, null when the agent doesn't take attachments. Passing attachments to an agent with no `blobStore` errors fast at invoke time with a clear message.

Closed mime mapping `ImageMime → ImagePart.WireMime` for all four variants. Non-image variants (`Text`, `Document`, `Audio`, `Video`) flow through the attachment path as no-ops in v1; slice c wires Document via provider doc-input adapters. Empty / all-skipped attachments → null images on the wire (legacy shape preserved). On resume, the `attachments` argument is ignored — the restored conversation already carries the original `LlmMessage.images`. 8 unit cases + 6 live cases pin the typed surface against the same `VisionFixtures` from slice a.

### Added — Document attachments (#2470 slice c)

Document attachment routing for `Content.Document` through every provider's native document API:

- **Anthropic** — PDFs go via `source.type=base64` + `media_type=application/pdf`. Plain text and markdown go via `source.type=text` with the raw decoded bytes (not base64) — Anthropic's documented quirk.
- **OpenAI** — multipart `[{type:"text"}, {type:"file", file:{file_data:"data:<mime>;base64,<b64>", filename:"..."}}, ...]`.
- **Ollama** — text-only documents get inlined into the prompt as fenced content; non-text documents are skipped with a debug log (no native doc-input on Ollama yet).
- **DeepSeek** — same shape as OpenAI.

Live document fixtures stay tiny (sub-2KB PDFs / markdown / plain text) — cost-disciplined the same way vision tests are.

### Added — Files convenience surface

`Files.load(path, store): Content` is the one-line file-loading entry point for the typed `Content` hierarchy. It reads the file, detects modality + mime from filename extension (case-insensitive, no magic-byte sniffing), puts bytes via the `BlobStore`, returns the right `Content` variant. Same `ContentRef.hash` as a manual `store.put`. Throws `UnknownExtensionException` (names the extension + path + full list of known extensions) on unrecognised.

```kotlin
val store = FileBlobStore(Path.of("./blobs"))
val image: Content = Files.load("input/diagram.png", store)
val all:   List<Content> = Files.loadAll(listOf("a.png", "b.pdf", "c.md"), store)
```

Variants: `loadOrNull` (null-on-unknown), `loadAll` (throws on first unknown), `loadAllOrSkip` (silently skips — directory ingestion), `canonicalExtensionFor(content)` (inverse mapping), `knownExtensions: Set<String>` (predicate for callers). Coverage: every `wireMime` on every modality variant has at least one canonical extension. 13 unit tests pin per-extension mapping, hash round-trip, case-insensitivity, and the canonical-extension inverse for all 17 variants.

### Added — Multimodal foundation (#2465 epic, Stage 1)

The typed substrate that the vision/document work sits on:

- **`Content` hierarchy (#2466)** — `sealed interface Content { Text, Image, Audio, Video, Document }` in package `agents_engine.content`. Closed `ImageMime` / `AudioMime` / `VideoMime` / `DocMime` sealed interfaces; `wireMime: String` accessors are the only stringly-typed leaf. Extension property `Content.modality: String` is the audit-stable per-variant name.
- **`ContentRef` + `BlobStore` (#2467)** — content-addressed reference (`hash: String` SHA-256 hex, `sizeBytes: Long`, `wireMime: String`). `InMemoryBlobStore` (defensive byte-array copies) and `FileBlobStore(dir)` (one file per blob, atomic tmp + rename, restart-survivable). Hash family matches the manifest hash and snapshot filename hash — single algorithm across the audit surface.
- **`ToolResult` (#2469)** — `data class ToolResult(parts: List<Content>)` for tools returning mixed content (screenshot tools return text + image; OCR returns extracted text + the source PDF ref). Just another `Any?` the tool executor returns — no `ToolDef` signature change; existing string-returning tools work byte-for-byte. JSONL audit exporter gains an `outputParts` column emitting one entry per part as `<modality>:<hash-prefix>:<sizeBytes>:<wireMime>`; blob bytes never enter the audit row.

### Added — Eval harness (#2491 epic)

End-to-end eval surface for agents:

- **`DeterministicModelClient` (#2492)** — pre-scripted `ModelClient` for byte-deterministic tests. `requests` records every message list the agent built up; exhaustion throws `DeterministicScriptExhausted(callIndex, scriptSize, lastMessages)`.
- **`eval { }` DSL (#2493)** — `agents_engine.testing.eval<IN, OUT>("name") { input(...); expect { ... } }` builds a typed eval case. Three expectation styles: `expect("label") { predicate }`, `expectSnapshot(snapshot = "...")` (pin canonical `toLlmInput(output)` JSON), `expectFieldEquals(field, value)`. `evalSuite("name") { + case; + case }.runAll(agent)` bundles cases; type-homogeneous over the agent type.
- **LLM-as-judge scorer (#2494)** — opt-in `eval { ... judge("tone", rubric) }`. Verdicts surface on `EvalResult.judgeVerdicts: Map<String, JudgeOutcome>`. **Judges never gate pass/fail** — they're advisory; deterministic expects own pass/fail.

See [docs/eval.md](docs/eval.md) for the full surface.

---

## Verification

```bash
./gradlew test    # full suite across all modules
```

Manifest review (audit-time):

```bash
./gradlew :agents-kt-manifest:permissionManifestVerify
```

Vision/document live tests (skip cleanly without keys / Ollama):

```bash
./gradlew test --tests "*VisionLiveTest" --info
./gradlew integrationTest --tests "*VisionLiveTest"   # Ollama-tagged
```

---

## Where to read more

- [`README.md`](README.md) — feature index pointing at `docs/`.
- [`docs/multimodal.md`](docs/multimodal.md) — full `Content` + vision + attachments + documents reference.
- [`docs/model-and-tools.md`](docs/model-and-tools.md) — `model { }` DSL including the new `requestTimeout` / `connectTimeout` knobs.
- [`docs/eval.md`](docs/eval.md) — eval harness reference.
- [`docs/permission-manifest.md`](docs/permission-manifest.md) — manifest semantics and the SHA-256 hash that feeds the restore guard.
- [`docs/threat-model.md`](docs/threat-model.md) — what 0.6 owns vs. what your deployment owns.

---

## Credits

Field report driving the timeout hotfix from a downstream production agent. Multimodal Stage 1 design from the 0.8 spec → product loop. Eval harness shape from the #2491 epic.
