package agents_engine.core

import agents_engine.generation.LenientJsonParser
import agents_engine.model.ImagePart
import agents_engine.model.InlineToolCallParser
import agents_engine.model.LlmMessage
import agents_engine.model.TokenUsage
import agents_engine.model.ToolCall
import agents_engine.internal.toJsonString

/** Minimal JSON codec for [SessionSnapshot] — reuses the existing escaper, arg encoder, and lenient parser. */
internal object SnapshotJson {
    fun encode(s: SessionSnapshot): String = buildString {
        append("{")
        append(""""requestId":${s.requestId.toJsonString()},""")
        append(""""sessionId":${s.sessionId?.toJsonString() ?: "null"},""")
        append(""""manifestHash":${s.manifestHash?.toJsonString() ?: "null"},""")
        // #2488 — pendingInterruptCallId rides on the wire so a process-
        // restart resume still knows which call to synthesise the result for.
        append(""""pendingInterruptCallId":${s.pendingInterruptCallId?.toJsonString() ?: "null"},""")
        append(""""turns":${s.turns},"toolCalls":${s.toolCalls},"toolCallLimit":${s.toolCallLimit},""")
        append(""""tokens":${encodeTokens(s.tokensUsed)},""")
        append(""""memory":{""")
        append(s.memory.entries.joinToString(",") { (k, v) -> "${k.toJsonString()}:${v.toJsonString()}" })
        append("},")
        append(""""messages":[""")
        append(s.messages.joinToString(",") { encodeMessage(it) })
        append("]}")
    }

    private fun encodeTokens(t: TokenUsage?): String =
        if (t == null) "null"
        else """{"prompt":${t.promptTokens},"completion":${t.completionTokens},""" +
            """"cached":${t.cachedInputTokens ?: "null"},""" +
            // #2867 — cacheWriteTokens was missing from snapshot encode pre-#2867;
            // cost audits and cumulative billing drifted across resume.
            """"cacheWrite":${t.cacheWriteTokens ?: "null"},""" +
            """"reasoning":${t.reasoningTokens ?: "null"},""" +
            """"provider":${t.provider.toJsonString()},"model":${t.model.toJsonString()}}"""

    private fun encodeMessage(m: LlmMessage): String = buildString {
        append("""{"role":${m.role.toJsonString()},"content":${m.content.toJsonString()}""")
        m.toolCalls?.let { calls ->
            append(""","toolCalls":[""")
            append(calls.joinToString(",") { tc ->
                """{"name":${tc.name.toJsonString()},"arguments":${InlineToolCallParser.argsToJson(tc.arguments)}}"""
            })
            append("]")
        }
        // #2866 — persist vision attachments so file-backed resume can rehydrate
        // the LlmMessage byte-for-byte. Pre-#2866 the encoder silently dropped
        // `images`, breaking SSE-style apps that snapshot mid-conversation.
        // Base64 directly inside the snapshot (rather than ContentRef.hash with
        // a re-fetch on resume) keeps the snapshot self-contained — no
        // BlobStore dependency at restore time.
        m.images?.let { imgs ->
            append(""","images":[""")
            append(imgs.joinToString(",") { part ->
                """{"base64":${part.base64.toJsonString()},"mime":${part.wireMime.value.toJsonString()}}"""
            })
            append("]")
        }
        append("}")
    }

    fun decode(json: String): SessionSnapshot {
        val root = LenientJsonParser.parse(json) as? Map<*, *> ?: error("malformed snapshot JSON")
        return SessionSnapshot(
            messages = (root["messages"] as? List<*>).orEmpty().mapNotNull { decodeMessage(it) },
            turns = (root["turns"] as? Number)?.toInt() ?: 0,
            toolCalls = (root["toolCalls"] as? Number)?.toInt() ?: 0,
            toolCallLimit = (root["toolCallLimit"] as? Number)?.toInt() ?: 0,
            tokensUsed = decodeTokens(root["tokens"] as? Map<*, *>),
            memory = (root["memory"] as? Map<*, *>).orEmpty().entries
                .associate { (k, v) -> k.toString() to v.toString() },
            requestId = root["requestId"]?.toString() ?: "",
            sessionId = root["sessionId"] as? String,
            manifestHash = root["manifestHash"] as? String,
            pendingInterruptCallId = root["pendingInterruptCallId"] as? String,
        )
    }

    private fun decodeMessage(raw: Any?): LlmMessage? {
        val m = raw as? Map<*, *> ?: return null
        val toolCalls = (m["toolCalls"] as? List<*>)?.mapNotNull { tcRaw ->
            val tc = tcRaw as? Map<*, *> ?: return@mapNotNull null
            val name = tc["name"] as? String ?: return@mapNotNull null
            @Suppress("UNCHECKED_CAST")
            val args = (tc["arguments"] as? Map<*, *>).orEmpty()
                .entries.associate { (k, v) -> k.toString() to v } as Map<String, Any?>
            ToolCall(name = name, arguments = args)
        }
        // #2866 — rehydrate vision attachments. Mime strings that don't match
        // a known [ImagePart.WireMime] variant skip with a null mapping
        // (defensive — the encoder only writes the closed set, but a
        // hand-edited or future-extended snapshot shouldn't crash resume).
        val images = (m["images"] as? List<*>)?.mapNotNull { imgRaw ->
            val img = imgRaw as? Map<*, *> ?: return@mapNotNull null
            val base64 = img["base64"] as? String ?: return@mapNotNull null
            val mimeStr = img["mime"] as? String ?: return@mapNotNull null
            val wireMime = decodeWireMime(mimeStr) ?: return@mapNotNull null
            ImagePart(base64 = base64, wireMime = wireMime)
        }
        return LlmMessage(
            role = m["role"]?.toString() ?: "user",
            content = m["content"]?.toString() ?: "",
            toolCalls = toolCalls,
            images = images,
        )
    }

    private fun decodeWireMime(value: String): ImagePart.WireMime? = when (value) {
        ImagePart.WireMime.Png.value -> ImagePart.WireMime.Png
        ImagePart.WireMime.Jpeg.value -> ImagePart.WireMime.Jpeg
        ImagePart.WireMime.Gif.value -> ImagePart.WireMime.Gif
        ImagePart.WireMime.Webp.value -> ImagePart.WireMime.Webp
        else -> null
    }

    private fun decodeTokens(t: Map<*, *>?): TokenUsage? {
        if (t == null) return null
        val prompt = (t["prompt"] as? Number)?.toInt() ?: return null
        val completion = (t["completion"] as? Number)?.toInt() ?: return null
        return TokenUsage(
            promptTokens = prompt,
            completionTokens = completion,
            cachedInputTokens = (t["cached"] as? Number)?.toInt(),
            // #2867 — cacheWrite added to the wire shape; back-compat with
            // pre-#2867 snapshots is `null` (key absent → cast returns null).
            cacheWriteTokens = (t["cacheWrite"] as? Number)?.toInt(),
            provider = t["provider"]?.toString() ?: "unknown",
            model = t["model"]?.toString() ?: "unknown",
            reasoningTokens = (t["reasoning"] as? Number)?.toInt(),
        )
    }
}
