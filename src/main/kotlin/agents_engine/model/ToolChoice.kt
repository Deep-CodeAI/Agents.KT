package agents_engine.model

/**
 * `agents_engine/model/ToolChoice.kt` — vendor-neutral tool-choice control
 * (#2479 part 2). Pairs with `agent { toolChoice = ToolChoice.X }`. Per-
 * provider mapping is the adapter's job; the public API stays sealed and
 * provider-agnostic.
 *
 * Wire mapping table:
 * - [Auto] → OpenAI/DeepSeek `"tool_choice":"auto"`; Anthropic
 *   `"tool_choice":{"type":"auto"}`. The provider's own default — the model
 *   may or may not call a tool.
 * - [Required] → OpenAI/DeepSeek `"tool_choice":"required"`; Anthropic
 *   `"tool_choice":{"type":"any"}` (Anthropic spells it "any"). The model
 *   MUST call at least one tool.
 * - [None] → OpenAI/DeepSeek `"tool_choice":"none"`; Anthropic has no
 *   equivalent flag, so adapters drop the `tools` array from the request
 *   entirely for this turn (the model can't call what it doesn't see).
 * - [Specific] → OpenAI/DeepSeek `"tool_choice":{"type":"function",
 *   "function":{"name":...}}`; Anthropic `"tool_choice":{"type":"tool",
 *   "name":...}`. The model must call exactly this named tool.
 *
 * Ollama has no native `tool_choice` field today. Adapters treat
 * non-[Auto] values as a best-effort hint — log a one-shot warning per
 * agent at first use; functionally a no-op. Documented as a known gap;
 * deployers who need hard enforcement on Ollama should use a model with
 * native tool-choice support (e.g. cloud Ollama serving compatible
 * models) or switch providers.
 *
 * Construction-time validation: [Specific.name] must reference a tool
 * registered on the agent (`agent.toolMap`). Names not in that map fail
 * fast at agent construction — same fail-fast philosophy as #631 for
 * skill tool names.
 */
sealed interface ToolChoice {
    /**
     * Provider default — the model decides whether to call a tool. Equivalent
     * to omitting the field (which is what every adapter did pre-#2479-pt2).
     */
    object Auto : ToolChoice

    /**
     * Force the model to call at least one tool this turn. Use when the
     * application has determined that a tool call is the only valid next
     * step — e.g. an agentic skill that must look something up before
     * answering. Anthropic spells this "any"; OpenAI/DeepSeek spell it
     * "required". The provider-neutral name here is [Required] for clarity.
     */
    object Required : ToolChoice

    /**
     * Force the model to produce a final text answer this turn — no tools
     * are exposed to it. Useful as the final turn of a manual loop where
     * the runtime has gathered enough context and wants the model to
     * summarise without calling more tools. Anthropic emulates this by
     * dropping the `tools` field from the request (no enum value exists).
     */
    object None : ToolChoice

    /**
     * Force the model to call exactly this named tool. The name must
     * reference a tool registered on the agent — fail-fast at agent
     * construction otherwise.
     */
    data class Specific(val name: String) : ToolChoice
}
