package agents_engine.model

data class LlmMessage(
    val role: String,
    val content: String,
    val toolCalls: List<ToolCall>? = null,
    /**
     * #2656 — optional vendor-neutral cache hint. When non-null, the
     * agentic loop is signalling that this message ends a cacheable
     * group; the adapter translates to its provider's mechanism
     * (Anthropic `cache_control` breakpoint, Gemini handle boundary,
     * etc.). Adapters that don't support caching ignore the hint —
     * caching is a latency / cost optimisation, not a correctness
     * condition. Defaults to null so pre-#2656 call sites are
     * unchanged on the wire.
     */
    val cacheHint: CacheHint? = null,
    /**
     * #2470 — optional vision input. When non-null and the role is
     * `"user"`, adapters translate each [ImagePart] into the provider's
     * native image payload alongside [content]:
     *
     *   - Ollama (e.g. qwen3-vl:8b) — `images: [<base64>, ...]` array
     *     on the user message; [content] stays the text prompt.
     *   - Anthropic Claude — `content: [{type:"text",...},
     *     {type:"image", source:{type:"base64", media_type:"image/png",
     *     data:"<base64>"}}, ...]`.
     *   - OpenAI — `content: [{type:"text",...},
     *     {type:"image_url", image_url:{url:"data:image/png;base64,
     *     <base64>"}}, ...]`.
     *
     * Null = no vision parts; wire shape is byte-identical to pre-#2470.
     * Vision works on the FIRST user turn (most common case for "describe
     * this image" prompts); subsequent user-turn images compose naturally
     * if the model supports multi-turn vision.
     *
     * Non-user roles ignore this field — system / assistant / tool
     * messages don't carry images in any provider's API.
     */
    val images: List<ImagePart>? = null,
)
