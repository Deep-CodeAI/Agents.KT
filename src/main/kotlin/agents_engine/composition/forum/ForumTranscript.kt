package agents_engine.composition.forum

/**
 * The collected state a `transcriptCaptain` receives: the original forum input
 * plus each participant's output, in registration order. The deliberation pattern.
 */
data class ForumTranscript<IN>(
    val originalInput: IN,
    val contributions: List<ParticipantContribution>,
)
