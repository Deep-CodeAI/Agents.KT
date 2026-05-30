---
description: Source-file knowledge for agents_engine/content/* — multimodal foundation (#2465 epic, #2466 + #2467 + #2469 shipped). sealed Content { Text, Image(ref, ImageMime), Audio(ref, AudioMime), Video(ref, VideoMime), Document(ref, DocMime) }. Mime types are CLOSED sealed interfaces per modality with wireMime: String accessor — no String mime in the public API. ContentRef(hash, sizeBytes, wireMime) is content-addressed via SHA-256 hex; non-text Content variants hold a ref, never bytes. BlobStore interface + InMemoryBlobStore + FileBlobStore (atomic tmp+rename, survives process restart, dedupe via hash). ToolResult(parts: List<Content>) is the multimodal tool return type — just an Any? value the executor returns. AgenticLoop renders ToolResult as text + per-part placeholders in v1 (provider-specific multipart rendering is #2470, deferred). JsonlAuditExporter records outputParts column with "<modality>:<hash-prefix>:<size>:<mime>" per part; blob bytes never enter the audit row. Composes with snapshot/resume (refs serialize, blobs stay external) and the manifest-hash restore guard (#2754). Audio/Video modelled but Stage 1 wires Image + Document end-to-end. Call when reasoning about multimodal tool returns, content-addressed blob persistence, or audit discipline around binary content.
---

# `agents_engine/content/*` — multimodal foundation

Three cooperating pieces in package `agents_engine.content`:

## `Content` sealed hierarchy (#2466)

```kotlin
sealed interface Content {
    data class Text(val text: String) : Content
    data class Image(val ref: ContentRef, val mime: ImageMime) : Content
    data class Audio(val ref: ContentRef, val mime: AudioMime) : Content
    data class Video(val ref: ContentRef, val mime: VideoMime) : Content
    data class Document(val ref: ContentRef, val mime: DocMime) : Content
}

val Content.modality: String     // stable per-variant name for audit rows
```

Closed mime types per modality — `ImageMime { Png, Jpeg, Gif, Webp }`, `AudioMime { Mp3, Wav, Flac, Ogg }`, `VideoMime { Mp4, WebM, Mov }`, `DocMime { Pdf, Docx, Markdown, Html, PlainText }`. Each exposes `wireMime: String` for adapter serialisation; the public API never accepts `String`.

Stage 1 wires Image + Document end-to-end (the modalities the spec→product loop consumes). Audio + Video modelled now, exercised through provider adapters in Stage 2.

## `ContentRef` + `BlobStore` (#2467)

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

fun computeContentHash(bytes: ByteArray): String   // SHA-256 hex
```

Hash family: SHA-256 hex — matches the manifest hash (#1912) and snapshot filename hash (#2753). Single hash algorithm across the audit surface.

Idempotent put: same bytes → same ref; same file on disk. InMemory uses defensive byte-array copies on put/get to protect against consumer mutation. FileBlobStore writes via tmp + atomic rename and survives process restart.

## `ToolResult` (#2469)

```kotlin
data class ToolResult(val parts: List<Content>) {
    constructor(vararg parts: Content)
    val textSummary: String      // concatenated Text parts only
}
```

Tools can return `ToolResult` instead of `String`. No `ToolDef` signature change — `ToolResult` is just another `Any?`.

AgenticLoop renders multipart returns for the LLM's tool-result message as: text parts inline + `[modality: <wireMime>] (<hash-prefix>, <size>B)` placeholders for non-text parts. Provider-specific multipart rendering (vision-capable Claude/OpenAI/Gemini) is the sibling #2470 ticket.

JsonlAuditExporter detects `ToolResult` returns and writes a new `outputParts: List<String>?` column: one entry per part as `<modality>:<hash-prefix>:<sizeBytes>:<wireMime>` for non-text parts, or `text:inline:<charCount>:text/plain` for text parts. Blob bytes never enter the audit row — pinned by a test.

## Composition

- **Snapshot / resume (#2386 / #2754):** Content carries `ContentRef`, not bytes. Snapshot files stay small; blobs live in the `BlobStore`. Resume against the same store dereferences refs normally. Manifest-hash restore guard applies unchanged.
- **untrustedOutput (#642):** wraps the text-summary rendering. Multimodal results compose with the trust boundary.
- **JSONL audit (#1914):** new column `outputParts` is null for non-multimodal returns — legacy rows unchanged. EXPECTED_FIELDS schema test updated.

## v1 deferrals (carried as sibling tickets in #2465)

- **#2468** Compile-time modality routing — `Agent<Image, X>` typed input, KSP multi-part `@Generable`
- **#2470** Provider normalization adapters (Claude / OpenAI / Gemini / Ollama)
- **#2471** Manifest-anchored modality capability validation at build time
- **#2472** Multimodal memory — `ContentRef`-backed MemoryBank entries
- **#2473** Multimodal testing fixtures + snapshot + mutation coverage

## Related files

- `core/Snapshot.kt` — `SessionSnapshot` carries refs through serialisation; no inlined blobs.
- `model/AgenticLoop.kt` — `renderToolResultForLlm` placeholder rendering for the LLM tool-result message.
- `agents-kt-observability/.../JsonlAuditExporter.kt` — `outputParts` audit-row column + `partsSummary` helper.
- `agents-kt-manifest/.../PermissionManifest.kt` — modality capability declaration TBD (#2471).
