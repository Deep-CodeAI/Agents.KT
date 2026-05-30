[← Back to README](../README.md)

# Multimodal content

First three pieces of the 0.8 multimodal epic ship today. The rest of the epic (provider adapters, KSP routing, manifest-anchored capability checks) is staged on top of this foundation.

## What ships

- **`sealed interface Content`** (#2466) — `Text`, `Image`, `Audio`, `Video`, `Document`. Each non-text variant carries a `ContentRef` + a typed mime (`ImageMime`, `AudioMime`, `VideoMime`, `DocMime`). No `String` mime anywhere in the public API.
- **`ContentRef` + `BlobStore`** (#2467) — content-addressed reference (SHA-256 hex + size + wire mime). `InMemoryBlobStore` for tests, `FileBlobStore(dir)` for on-disk persistence that survives process restart.
- **`ToolResult(parts: List<Content>)`** (#2469) — tools can return mixed content. JSONL audit exporter records per-part modality + ref summary; **no blob bytes ever enter the audit row**.

## Design hinges

1. **Modality + format live in the type, never in a String.** Adding a new mime is a new variant; mistyping it is a compile error.
2. **Content-addressed payload, not inlined bytes.** `Content` carries a `ContentRef`, blobs live in a `BlobStore`. Snapshot files stay small. Audit rows stay small.
3. **No `data class` holding `ByteArray`.** Kotlin data-class equals/hashCode treat arrays by identity, not content — would break every downstream consumer's assumption. The ref pattern sidesteps it entirely.

## Quick start

```kotlin
import agents_engine.content.*

val store = FileBlobStore(Path.of("snapshots", "blobs"))

val screenshotAgent = agent<String, String>("screenshot") {
    model { ollama("test") }
    tools {
        tool("capture", "Capture a screenshot of the URL") { args ->
            val bytes = captureBytes(args["url"] as String)
            val ref = store.put(bytes, ImageMime.Png.wireMime)
            ToolResult(
                Content.Text("Captured ${ref.sizeBytes}B image."),
                Content.Image(ref, ImageMime.Png),
            )
        }
    }
    skills { skill<String, String>("respond", "") { tools("capture") } }
}
```

## `Content` variants

```kotlin
sealed interface Content {
    data class Text(val text: String) : Content
    data class Image(val ref: ContentRef, val mime: ImageMime) : Content
    data class Audio(val ref: ContentRef, val mime: AudioMime) : Content
    data class Video(val ref: ContentRef, val mime: VideoMime) : Content
    data class Document(val ref: ContentRef, val mime: DocMime) : Content
}

val Content.modality: String   // "text" | "image" | "audio" | "video" | "document"
```

Stage 1 (this release): **Image + Document** wired end-to-end through the audit pipeline and the agentic loop's tool-result rendering. **Audio + Video** modelled now and exercised through provider adapters in Stage 2.

### Closed mime types

```kotlin
sealed interface ImageMime { Png, Jpeg, Gif, Webp }
sealed interface AudioMime { Mp3, Wav, Flac, Ogg }
sealed interface VideoMime { Mp4, WebM, Mov }
sealed interface DocMime   { Pdf, Docx, Markdown, Html, PlainText }
```

Each variant exposes `wireMime: String` for adapter serialisation. The public API never accepts `String` mime — extending is adding a variant.

## `ContentRef` + `BlobStore`

```kotlin
data class ContentRef(val hash: String, val sizeBytes: Long, val wireMime: String)

interface BlobStore {
    fun put(bytes: ByteArray, wireMime: String): ContentRef
    fun get(ref: ContentRef): ByteArray?
    fun open(ref: ContentRef): InputStream?
    fun exists(ref: ContentRef): Boolean
    fun delete(ref: ContentRef)
}

class InMemoryBlobStore : BlobStore
class FileBlobStore(dir: Path) : BlobStore
```

**Hash:** SHA-256 hex. Same family as manifest hash (#1912) and snapshot filename hash (#2753) — single hash algorithm across the audit surface.

**Idempotent put:** putting the same bytes twice returns the same `ContentRef`. `FileBlobStore` writes the file once and is a no-op on the second put — pinned by a test.

**Persistence:** `FileBlobStore` survives process restart. A fresh instance on the same directory sees blobs from prior puts. Atomic via tmp + rename, matching the `FileSnapshotStore` pattern (#2753).

**Custom backends:** an internal artifact registry, S3, GCS, etc. implement `BlobStore` and plug in via the same interface.

## `ToolResult` — multimodal tool returns

```kotlin
tool("ocr", "Extract text + return source") { args ->
    val text = ocrText(args["pdf"] as String)
    val sourceRef = store.put(args["pdf-bytes"] as ByteArray, DocMime.Pdf.wireMime)
    ToolResult(
        Content.Text(text),
        Content.Document(sourceRef, DocMime.Pdf),
    )
}
```

Tools can return `ToolResult` instead of a String. The agentic loop renders it for the LLM's tool-result message in v1:

```
Extracted spec text.
[document: application/pdf] (a3f9b2c4...12, 524288B)
```

Provider-specific multipart rendering — the model actually seeing the image / document — lands in **#2470** (Provider normalization adapters, deferred).

`untrustedOutput = true` still wraps the rendered text summary in the JSON envelope (#642 composes with #2469).

## Audit discipline

`JsonlAuditExporter` (#1914) gains a new `outputParts` column. For `ToolResult` returns:

```json
{
  "...": "...",
  "outputType": "agents_engine.content.ToolResult",
  "outputParts": [
    "text:inline:18:text/plain",
    "image:a3f9b2c41205:524288:image/png"
  ],
  "...": "..."
}
```

Format: `<modality>:<hash-prefix>:<sizeBytes>:<wireMime>` per part. Hash prefix is the first 12 hex chars — enough to disambiguate in audit grep, short enough to keep audit rows compact.

**Critical:** blob bytes never enter the audit row. The `ContentRef` is the auditable surface. Pinned by a dedicated test that asserts the PNG magic byte sequence does not appear anywhere in the audit JSON.

The same discipline applies (when wired) to the OTel / LangSmith / Langfuse bridges — sibling tickets will plumb `outputParts` onto span events / run events / observations.

## Snapshot composition

`Content` carrying a `ContentRef` (not bytes) means `SessionSnapshot` (#2386 / #2754) stays small regardless of how much image / audio / video flowed through the agent. The snapshot serialises the ref; the blob lives in the `BlobStore`. A snapshot resume against the same `BlobStore` dereferences the ref normally.

Pairs with the #2754 manifest-hash restore guard: resume across an agent rebuild that changed tools (including the `BlobStore` wiring) fails closed unless the caller opts in.

## Vision input — talking to the model (#2470 slice a)

The foundation above answers "how do tools return mixed content?" The follow-on slice answers "how does the agent send an image to a vision-capable model?"

`LlmMessage` gains an optional `images: List<ImagePart>` field. Adapters translate it per provider:

```kotlin
import agents_engine.model.LlmMessage
import agents_engine.model.ImagePart

val png: ByteArray = Files.readAllBytes(Path.of("screenshot.png"))
val client = OllamaClient(model = "qwen3-vl:8b", temperature = 0.0)

client.chat(listOf(
    LlmMessage(
        role = "user",
        content = "How many windows in this picture?",
        images = listOf(ImagePart(
            base64 = Base64.getEncoder().encodeToString(png),
            wireMime = ImagePart.WireMime.Png,
        )),
    ),
))
```

`ImagePart` carries the base64 payload + a closed `WireMime` sealed type (`Png`, `Jpeg`, `Gif`, `Webp`). `String` mime is not accepted in the public ctor — same closed-mime discipline as `Content.Image`. Caller base64-encodes upfront so the adapter can splat the payload straight onto the wire without re-encoding per provider.

| Provider | User-message shape |
|---|---|
| Ollama | `{role:"user", content:"text", images:["<b64>", ...]}` |
| Claude | `{role:"user", content:[{type:"text"}, {type:"image", source:{type:"base64", media_type:"image/png", data:"<b64>"}}, ...]}` |
| OpenAI | `{role:"user", content:[{type:"text"}, {type:"image_url", image_url:{url:"data:image/png;base64,<b64>"}}, ...]}` |
| DeepSeek | inherits OpenAI; current DeepSeek models lack vision and silently ignore the field |

**Back-compat:** `images = null` (or empty) produces byte-identical wire shape to pre-#2470. Pinned by dedicated tests.

**Role-gating:** images are emitted only on `role = "user"` messages. System / assistant / tool messages with non-null `images` ignore the field on the wire — no provider's API carries images on those roles.

### Programmatic image fixtures

`VisionFixtures` (in `src/test`) ships two reproducible PNGs rendered via `BufferedImage`:

- `threeSquaresPng()` — 256×256, three colored squares (red/blue/green) on white with thick black outlines. Used for "count the squares" eval against cheap vision models.
- `housePng()` — 256×256 cartoon house: triangle roof + body + door + two windows. Used for "what is this?" eval.

No external assets, no binary blobs in the repo — every byte is reproducible across machines and CI runs.

### Live tests

`VisionLiveTest.kt` runs the two fixtures against all three vision-capable providers, with cost discipline (256×256 PNG ~5KB, `temperature = 0`, `maxTokens = 80`, single-turn):

| Provider | Default model | How to run |
|---|---|---|
| Ollama | `qwen3-vl:8b` | `./gradlew integrationTest --tests "*VisionLiveTest*"` (tagged `live-llm`) |
| Claude | `claude-haiku-4-5` | `./gradlew test --tests "*VisionLiveTest*"` (tagged `live-cloud-api`; `assumeTrue` skips when no key) |
| OpenAI | `gpt-4o-mini` | same |

Models overridable via env (`AGENTSKT_TEST_OLLAMA_VISION_MODEL`, `AGENTSKT_TEST_CLAUDE_VISION_MODEL`, `AGENTSKT_TEST_OPENAI_VISION_MODEL`).

Assertion shape is loose: the test passes if the model's text response mentions one of a small acceptable keyword set (`3` / `three` for counting; `house` / `home` / `cottage` / `building` / `cabin` / `barn` for the house). Goal is "did the image reach the model and elicit a sensible reply" — not exact phrasing.

## Agent attachments — typed `Content.Image` at the invoke surface (#2470 slice b)

Slice a wires the per-provider wire format on `LlmMessage.images`. Slice b puts a clean user-facing API on top: the caller passes typed `Content.Image` (carrying a `ContentRef`) at the agent's invoke surface; the runtime dereferences against an injected `BlobStore`, base64-encodes once, and attaches `ImagePart` to the first user message.

```kotlin
import agents_engine.content.Content
import agents_engine.content.ImageMime
import agents_engine.content.FileBlobStore

val store = FileBlobStore(Path.of("snapshots/blobs"))

val agent = agent<String, String>("vision") {
    model { ollama("qwen3-vl:8b") }
    blobStore(store)
    skills { skill<String, String>("describe", "") { tools() } }
}

val ref = store.put(pngBytes, ImageMime.Png.wireMime)
val reply = agent.invokeWithAttachments(
    "What is in this image?",
    attachments = listOf(Content.Image(ref, ImageMime.Png)),
)
```

### What the runtime guarantees

- **First user message only.** Attachments ride on the initial user turn. Multi-turn vision is composable but each invocation owns its own first-turn attachments.
- **Closed-mime mapping.** `ImageMime → ImagePart.WireMime` for all four variants — `Png`, `Jpeg`, `Gif`, `Webp`. No String conversions anywhere.
- **Fail-fast on misconfiguration.** Passing attachments to an agent with no `blobStore` configured errors fast at invoke time with `Agent '<name>' has attachments but no blobStore`. A ref pointing at a missing blob errors fast with the ref's hash prefix in the message for forensics.
- **Skip non-image variants.** `Content.Text` / `Content.Document` / `Content.Audio` / `Content.Video` are silently skipped in v1 — slice c will wire Document via provider doc-input adapters, audio/video as part of Stage 2.
- **Back-compat.** `agent.invokeSuspend(input)` (without attachments) stays byte-identical on the wire. The attachments path is purely additive — opt in via the new `invokeWithAttachments` / `invokeSuspendWithAttachments` entry points.
- **Snapshot/resume composition.** On resume the saved user turn already carries the original `LlmMessage.images`; the runtime ignores the `attachments` argument because the conversation was restored intact.

### Suspending vs blocking

| Entry point | Use when |
|---|---|
| `agent.invokeSuspendWithAttachments(input, attachments)` | Inside coroutine scopes — composition operators, structured concurrency. |
| `agent.invokeWithAttachments(input, attachments)` | Outside coroutine scopes — quick scripts, REPL, blocking glue. Thin `runBlocking` shim. |

Mirrors the existing `invokeSuspend` / `invoke` split.

### Live tests

`AgentVisionLiveTest.kt` runs the two `VisionFixtures` (`threeSquaresPng()` + `housePng()`) through the agent surface on all three vision-capable providers. Same cost discipline as slice a — 256×256 PNG, `temperature = 0`, `maxTokens = 80`, single-turn. Tagged `live-llm` (Ollama) / `live-cloud-api` (Claude + OpenAI); `assumeTrue` skips per-provider when no key.

The slice-b live tests complement the slice-a tests: the slice-a `VisionLiveTest` exercises the raw `ModelClient`; slice-b's `AgentVisionLiveTest` exercises the full agent loop including BlobStore deref.

## What's still coming (rest of #2465)

- **#2468** Compile-time modality routing — `Agent<Image, X>` becomes a real type; cross-modality miswiring is a compile error. Multi-part `@Generable` inputs via KSP.
- **#2470 slice c** Document/Audio/Video provider-input adapters — currently only images flow through the wire; Document/Audio/Video Content variants are skipped on the attachment path.
- **#2471** Manifest-anchored modality capability — declared per-agent modalities recorded in the permission manifest, validated against provider capabilities at build time.
- **#2472** Multimodal memory — `MemoryBank` entries carry `ContentRef` for image/audio/video state.
- **#2473** Testing fixtures + snapshot + mutation coverage.

Stage 2 (Audio + Video) lights up when a concrete use case lands.

## Related docs

- [`docs/permission-manifest.md`](permission-manifest.md) — the manifest-hash family the BlobStore reuses.
- [`docs/observability.md`](observability.md) — the audit exporter that now carries `outputParts`.
- [`docs/hitl.md`](hitl.md) — `Content` will appear in human-approval bodies once Stage 2 lands.

Sources: `agents_engine/content/Content.kt`, `agents_engine/content/ContentRef.kt`, `agents_engine/content/ToolResult.kt`, audit wiring in `agents-kt-observability/.../JsonlAuditExporter.kt`.

Tests: `ContentAndRefTest.kt`, `ToolResultIntegrationTest.kt`, JsonlAuditExporterTest's "multimodal ToolResult writes outputParts" case.
