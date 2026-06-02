package agents_engine.core

import agents_engine.content.Content

/**
 * Per-invocation execution parameters for [Agent.invokeSuspendForSession] (#3088, de-slop #3083).
 *
 * Before this, the optional knobs below accreted as a long named-parameter list directly on the
 * suspend entry point — the "main suspend execution route takes a long list of parameters" an
 * external review flagged. Bundling them into one value object keeps the entry point readable and
 * gives every invocation seam (resume/HITL, attachments, prompt-override, streaming) a single
 * cohesive thing to pass and thread into `executeAgentic`.
 *
 * Every field defaults to the plain fresh-invocation behavior, so `RunRequest()` is the no-op
 * request that [Agent.invokeSuspend] uses — the non-streaming path is byte-for-byte unchanged.
 */
data class RunRequest(
    /**
     * #1747 — optional system-prompt override for this invocation only (used by the `wrap`
     * operator). When non-null, replaces `Agent.prompt` as the effective system prompt without
     * mutating the agent's baked-in prompt.
     */
    val promptOverride: String? = null,
    /**
     * #2749 — optional seed for snapshot/resume. When non-null, the agentic loop starts from this
     * snapshot's messages + counters (and restores memory) instead of a fresh conversation.
     */
    val resumeFrom: SessionSnapshot? = null,
    /**
     * #2749 / #2488 — optional per-turn checkpoint callback. Fires at each turn boundary, when an
     * `onBudgetExceeded` handler returns `Checkpoint`, and once more at the interrupt site before
     * throwing `AgentInterruptException`.
     */
    val onTurnCheckpoint: ((SessionSnapshot) -> Unit)? = null,
    /**
     * #2488 — typed resume input for a HITL interrupt. When [resumeFrom] carries a pending
     * interrupt, the runtime synthesises a tool-result message from this value before resuming.
     */
    val resumeWith: Any? = null,
    /**
     * #2754 — opt out of the snapshot manifest-hash restore guard. False (default) refuses to
     * resume a snapshot whose manifest differs from the current agent's.
     */
    val allowManifestMismatch: Boolean = false,
    /**
     * #2470 slice b — attachments to ride on the FIRST user message. The runtime dereferences each
     * `Content.Image` against [Agent.blobStore] and renders it into an `ImagePart`; non-image
     * variants are deferred. Null = no attachments; the wire shape is unchanged.
     */
    val attachments: List<Content>? = null,
)
