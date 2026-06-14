package agents_engine.composition.forum

import agents_engine.generation.LenientJsonParser

/**
 * #4514 — a parsed inline `forum_return` call. Wrapping the value (rather than returning it raw)
 * keeps a `null` return value distinct from "the verdict was not an inline forum_return".
 */
internal class InlineForumReturn(val value: Any?)

/**
 * #4514 — a captain may emit the `forum_return` tool **call as inline JSON text** rather than as a
 * real tool call (e.g. `{"name":"forum_return","arguments":{"value":108}}` — what `qwen3-vl` emitted
 * live), so no `ForumReturnException` fires and the raw JSON would leak as the forum result. Detect
 * it here — by either the inline `"tool"` key or the OpenAI `"name"` key — and extract the value the
 * same way the `forum_return` executor does. Returns null when [verdict] is not an inline
 * `forum_return` (a plain answer, or some other tool's JSON, passes through untouched).
 */
internal fun parseInlineForumReturn(verdict: Any?): InlineForumReturn? {
    val text = (verdict as? String)?.trim()?.takeIf { it.startsWith("{") } ?: return null
    val obj = runCatching { LenientJsonParser.parse(text) }.getOrNull() as? Map<*, *> ?: return null
    val name = (obj["tool"] ?: obj["name"]) as? String
    if (name != "forum_return") return null
    val args = (obj["arguments"] ?: obj["parameters"]) as? Map<*, *> ?: return null
    val value = when {
        "value" in args -> args["value"]
        args.isEmpty() -> ""
        args.size == 1 -> args.values.first()
        else -> args
    }
    return InlineForumReturn(value)
}
