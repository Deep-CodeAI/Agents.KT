package agents_engine.model

import agents_engine.internal.toJsonString

/**
 * Renders tool results and tool-error messages into the text the agentic loop feeds back to the
 * LLM (#3376 — extracted from `AgenticLoop`'s private helpers so the contracts are unit-testable).
 * Pure functions, no loop state.
 */
internal object ToolResultRendering {

    fun formatEscalatedToolError(toolName: String, result: RepairResult.Escalated): String =
        "ERROR: Tool '$toolName' failed: ${result.reason} " +
            "(severity: ${result.severity}). Please retry with corrected arguments."

    fun formatDeniedToolError(toolName: String, reason: String): String =
        "ERROR: Tool '$toolName' denied by policy: $reason"

    /**
     * Wrap a tool result from an `untrustedOutput = true` tool in a JSON envelope so the LLM can
     * distinguish data from instructions (#642). Routes through the central [toJsonString] escaper
     * (RFC 8259 §7-conformant) so control characters U+0000–U+001F are escaped — invalid JSON for
     * binary/OCR/captured-terminal output otherwise (#2756). Tool name is escaped too.
     */
    fun wrapUntrustedToolResult(toolName: String, result: Any?): String {
        val value = result?.toString() ?: "null"
        return """{"tool":${toolName.toJsonString()},"trusted":false,"value":${value.toJsonString()}}"""
    }

    /**
     * Render a tool's return value into the text the LLM sees as the tool-result message (#2469).
     * For a multimodal `ToolResult`, non-text parts surface as `[modality: <mime>]` placeholders;
     * for non-multimodal returns, `toString()` (or `"null"`) — byte-for-byte the pre-#2469 behaviour.
     */
    fun renderToolResultForLlm(result: Any?): String = when (result) {
        is agents_engine.content.ToolResult -> agents_engine.content.renderToolResultPlaceholder(result)
        null -> "null"
        else -> result.toString()
    }
}
