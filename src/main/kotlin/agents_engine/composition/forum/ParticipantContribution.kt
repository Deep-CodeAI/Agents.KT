package agents_engine.composition.forum

/**
 * One participant's contribution to a forum deliberation.
 * `output` is `Any?` because participants are heterogeneously typed (`Agent<IN, *>`).
 */
data class ParticipantContribution(
    val agentName: String,
    val output: Any?,
)
