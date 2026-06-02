package agents_engine.model

/**
 * Logical role of the content a [CacheHint] applies to. The framework tags each cacheable segment
 * with one of these so adapters route vendor-specific decisions on a semantic axis rather than
 * guessing from message role/index.
 */
sealed interface CacheSegment {
    /** The system / instruction prefix. Byte-stable across turns when the agent build is fixed. */
    data object SystemPrompt : CacheSegment

    /**
     * The tool-definitions block. KSP-generated (#1703) and deterministic, so caching it is safe by
     * default.
     */
    data object ToolDefs : CacheSegment

    /**
     * Conversation history. Marked when [CacheConfig.cacheConversation] is [CacheConversation.Rolling]
     * — adapters place a rolling breakpoint at each turn end so the growing prefix continues to hit.
     */
    data object Conversation : CacheSegment

    /**
     * A user-declared cacheable content block (e.g. a large retrieved document or instruction set).
     * [id] is opaque to the framework — provided for traceability and per-vendor routing keys.
     */
    data class Custom(val id: String) : CacheSegment
}
