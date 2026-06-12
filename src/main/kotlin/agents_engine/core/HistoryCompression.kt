package agents_engine.core

/**
 * `agents_engine/core/HistoryCompression.kt` — #3865 Phase 1. The
 * before-turn compression pass behind `agent { historyCompression { … } }`:
 * when the trigger fires, the middle of the conversation is replaced with
 * one deterministic digest message, leading system messages and the most
 * recent turns stay untouched, and the swap rides the existing
 * `onBeforeTurn` → `Decision.ProceedWith` seam (the loop replaces its
 * history permanently, so compression happens once, not per turn).
 *
 * Failure policy: a summarizer exception skips compression for that turn —
 * a long history is degraded service, a killed run is an outage.
 */

internal fun compressHistory(
    messages: List<ChatMessage>,
    config: HistoryCompressionConfig,
    onCompressed: (HistoryCompressionResult) -> Unit,
): Decision<List<ChatMessage>> {
    if (!config.triggerWhen(messages)) return Decision.Proceed

    // Leading system messages are pinned — they carry the prompt contract.
    val headCount = messages.takeWhile { it.role == "system" }.size

    // Preserve the most recent N, extended backward so the preserved window
    // never starts with an orphaned tool result (its assistant tool_call
    // would be gone — providers reject that shape).
    var tailStart = (messages.size - config.preserveRecent).coerceAtLeast(headCount)
    while (tailStart > headCount && tailStart < messages.size && messages[tailStart].role == "tool") {
        tailStart--
    }

    val middle = messages.subList(headCount, tailStart)
    if (middle.size < MIN_COMPRESSIBLE_MESSAGES) return Decision.Proceed

    val digest = try {
        config.summarizer(middle)
    } catch (_: Exception) {
        // Degrade, don't fail: proceed with the uncompressed history.
        return Decision.Proceed
    }

    val summaryMessage = ChatMessage(
        role = "user",
        content = "[History summary — replaces ${middle.size} earlier messages]\n$digest",
    )
    val replacement = messages.subList(0, headCount) + summaryMessage + messages.subList(tailStart, messages.size)
    onCompressed(
        HistoryCompressionResult(
            replacedCount = middle.size,
            preservedCount = messages.size - middle.size,
            digest = digest,
        ),
    )
    return Decision.ProceedWith(replacement)
}

/**
 * Default summarizer: deterministic extractive digest — one line per
 * message (role + truncated content + tool-call names), capped. No LLM
 * call, so compression is replayable and free.
 */
internal fun extractiveDigest(messages: List<ChatMessage>): String =
    messages.take(DIGEST_MAX_LINES).joinToString("\n") { message ->
        val tools = message.toolCalls?.takeIf { it.isNotEmpty() }
            ?.joinToString(prefix = " [tools: ", postfix = "]") { it.name }
            .orEmpty()
        val content = message.content.replace('\n', ' ').trim()
        val truncated = if (content.length > DIGEST_LINE_CHARS) {
            content.take(DIGEST_LINE_CHARS) + "…"
        } else {
            content
        }
        "- ${message.role}: $truncated$tools"
    } + if (messages.size > DIGEST_MAX_LINES) "\n- … and ${messages.size - DIGEST_MAX_LINES} more messages" else ""

private const val MIN_COMPRESSIBLE_MESSAGES = 2
private const val DIGEST_MAX_LINES = 60
private const val DIGEST_LINE_CHARS = 160
