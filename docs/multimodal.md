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

## What's coming (the rest of #2465)

- **#2468** Compile-time modality routing — `Agent<Image, X>` becomes a real type; cross-modality miswiring is a compile error. Multi-part `@Generable` inputs via KSP.
- **#2470** Provider adapters — Claude vision, OpenAI vision, Gemini, Ollama multimodal. Translates `Content → provider-specific payload` at the wire.
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
