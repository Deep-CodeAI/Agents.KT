package agents_engine.content

/**
 * `agents_engine/content/ToolResult.kt` — multimodal tool return value
 * (#2469, part of the #2465 multimodal epic).
 *
 * Tools have historically returned `Any?` — typically a `String`. When
 * a tool wants to return mixed content (a screenshot tool returns
 * description text + an image; OCR returns extracted text + the
 * source PDF reference; a browser-capture tool returns the page
 * markup + a screenshot), it can return a [ToolResult] carrying a
 * list of typed [Content] parts.
 *
 * ```kotlin
 * tool("screenshot", "Take a screenshot of the page") { args ->
 *     val bytes = takeScreenshot(args["url"] as String)
 *     val ref = blobStore.put(bytes, ImageMime.Png.wireMime)
 *     ToolResult(
 *         Content.Text("Screenshot captured."),
 *         Content.Image(ref, ImageMime.Png),
 *     )
 * }
 * ```
 *
 * **Audit discipline.** Observability bridges that surface tool results
 * detect `ToolResult` and emit per-part metadata: the part's
 * [Content.modality] and the [ContentRef] (hash + size + mime).
 * **Blob bytes never enter the audit log.** Text parts inline their
 * content as before (text is small, structural, and already part of
 * the audit story).
 *
 * **Provider rendering.** Translating a `ToolResult` into the next
 * LLM turn's tool-result message is the provider adapter's job
 * (sibling #2470, deferred). For now, when a tool returns a
 * `ToolResult`, the agentic loop renders the text parts to the
 * model and notes the non-text parts as "[modality: <type>]"
 * placeholders. Vision-capable adapters fill these in end-to-end
 * when #2470 ships.
 *
 * **Composition with existing surfaces:**
 *
 * - [ToolResult] is just another `Any?` the tool executor returns —
 *   no `ToolDef` signature change. Existing tools that return
 *   strings keep working byte-for-byte.
 * - Snapshot/resume (#2386 / #2754) serialises through plain JSON
 *   and refs travel with the snapshot; blobs live in the
 *   [BlobStore]. A resumed snapshot dereferences refs against the
 *   same store.
 * - `untrustedOutput` (#642) still applies — wrap the rendered text
 *   summary of a multi-part result in the untrusted envelope when
 *   the tool declares it.
 */
data class ToolResult(val parts: List<Content>) {
    constructor(vararg parts: Content) : this(parts.toList())

    /**
     * Convenience: extract the text parts as a single concatenated
     * string. Useful when a tool returns mixed content but the model
     * primarily consumes the textual summary; non-text parts surface
     * via the audit + the provider adapter rendering.
     */
    val textSummary: String
        get() = parts.filterIsInstance<Content.Text>().joinToString("\n") { it.text }

    init { require(parts.isNotEmpty()) { "ToolResult requires at least one Content part." } }
}

/**
 * Render a [ToolResult] into the placeholder text the agentic loop
 * uses for the tool-result LLM message in v1 (#2470 will replace
 * this with provider-specific multipart rendering). Text parts
 * inline verbatim; non-text parts surface as `[modality: <type>]
 * (<hash:short>, <size>B)`.
 *
 * Audit bridges and the JSONL exporter call [ToolResult.parts]
 * directly for per-part metadata writeouts; this placeholder is
 * only the in-context model rendering.
 */
internal fun renderToolResultPlaceholder(result: ToolResult): String =
    result.parts.joinToString("\n") { part ->
        when (part) {
            is Content.Text -> part.text
            is Content.Image -> "[image: ${part.mime.wireMime}] (${part.ref.hash.take(12)}, ${part.ref.sizeBytes}B)"
            is Content.Audio -> "[audio: ${part.mime.wireMime}] (${part.ref.hash.take(12)}, ${part.ref.sizeBytes}B)"
            is Content.Video -> "[video: ${part.mime.wireMime}] (${part.ref.hash.take(12)}, ${part.ref.sizeBytes}B)"
            is Content.Document -> "[document: ${part.mime.wireMime}] (${part.ref.hash.take(12)}, ${part.ref.sizeBytes}B)"
        }
    }
