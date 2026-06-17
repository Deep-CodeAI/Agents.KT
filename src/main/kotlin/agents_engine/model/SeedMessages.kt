package agents_engine.model

import agents_engine.core.Agent
import agents_engine.generation.toLlmInput

// #2804 — first N hex chars of a content-addressed blob hash used when rendering
// multipart tool-result / attachment placeholders. 12-char convention, matching
// MANIFEST_HASH_PREFIX_LEN in AgenticLoop.kt.
private const val BLOB_HASH_PREFIX_LEN = 12

/**
 * `agents_engine/model/SeedMessages.kt` — #2791 — seeds the initial message list for a *fresh*
 * (non-resume) [executeAgentic] invocation: the system prompt, any custom cacheable segments, and
 * the user message carrying the serialized [input] plus dereferenced image attachments. The resume
 * path ([restoreFromSnapshot]) is the symmetric counterpart; the loop picks one.
 *
 * Cache hints (#2656) are vendor-neutral — each adapter translates them to its own mechanism
 * (Anthropic `cache_control`, Gemini handle boundary, OpenAI/DeepSeek/Ollama prefix caching) and
 * ignores them when unsupported. [PrefixStabilityGuard] (#2657) warns the deployer when a cacheable
 * segment's content drifts between invocations of the same agent (a silent vendor-cache miss).
 */
internal fun <IN> seedMessages(
    agent: Agent<IN, *>,
    input: IN,
    attachments: List<agents_engine.content.Content>?,
    systemContent: String,
    messages: MutableList<LlmMessage>,
) {
    val cache = agent.cacheConfig
    val systemHint = if (cache.enabled && (cache.cacheSystemPrompt || cache.cacheToolDefs)) {
        CacheHint(segment = CacheSegment.SystemPrompt, ttl = cache.ttl)
    } else null

    if (systemContent.isNotBlank()) {
        val systemMsg = LlmMessage("system", systemContent, cacheHint = systemHint)
        messages.add(systemMsg)
        // #2657 — prefix-stability guard. No-op when systemHint is null (caching disabled).
        PrefixStabilityGuard.observe(agent, systemMsg)
    }
    // Custom cacheable segments — large retrieved docs / instruction sets declared via
    // `caching { cacheable("id") { content } }`. Each is its own "system"-role message with its own
    // Custom hint. Content is preserved even when caching is disabled (the DSL declared prompt
    // content, not just a cache directive); only the hint drops.
    for (seg in cache.customSegments) {
        val hint = if (cache.enabled) {
            CacheHint(segment = CacheSegment.Custom(seg.id), ttl = seg.ttl ?: cache.ttl)
        } else null
        val customMsg = LlmMessage("system", seg.content, cacheHint = hint)
        messages.add(customMsg)
        PrefixStabilityGuard.observe(agent, customMsg)
    }
    messages.add(LlmMessage("user", toLlmInput(input), images = resolveAttachedImages(agent, attachments)))
}

/**
 * #2470 slice b — dereference each `Content.Image` attachment against the agent's [BlobStore],
 * base64-encode once, and return the [ImagePart] list to ride along on the first user message; the
 * slice-a per-provider adapters translate that to the right wire shape. Non-image content
 * (Document / Audio / Video) is skipped in v1. Returns null for an empty / image-less list (fast path).
 */
private fun resolveAttachedImages(
    agent: Agent<*, *>,
    attachments: List<agents_engine.content.Content>?,
): List<ImagePart>? {
    if (attachments.isNullOrEmpty()) return null
    val store = agent.blobStore
    require(store != null) {
        "Agent '${agent.name}' has attachments but no blobStore — call `blobStore(store)` " +
            "inside the agent { } block so Content.Image refs can be dereferenced."
    }
    return attachments.mapNotNull { content ->
        when (content) {
            is agents_engine.content.Content.Image -> {
                val bytes = store.get(content.ref)
                    ?: error(
                        "BlobStore on agent '${agent.name}' has no entry for ContentRef(" +
                            "hash=${content.ref.hash.take(BLOB_HASH_PREFIX_LEN)}…, size=${content.ref.sizeBytes}); " +
                            "did the store get rewired or the blob purged?",
                    )
                ImagePart(
                    base64 = java.util.Base64.getEncoder().encodeToString(bytes),
                    wireMime = when (content.mime) {
                        agents_engine.content.ImageMime.Png -> ImagePart.WireMime.Png
                        agents_engine.content.ImageMime.Jpeg -> ImagePart.WireMime.Jpeg
                        agents_engine.content.ImageMime.Gif -> ImagePart.WireMime.Gif
                        agents_engine.content.ImageMime.Webp -> ImagePart.WireMime.Webp
                    },
                )
            }
            // Not an image — skip in v1. Slice c (provider doc/audio/video paths) covers the rest.
            is agents_engine.content.Content.Text,
            is agents_engine.content.Content.Audio,
            is agents_engine.content.Content.Video,
            is agents_engine.content.Content.Document,
            -> null
        }
    }.takeIf { it.isNotEmpty() }
}
