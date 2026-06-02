package agents_engine.runtime

/**
 * In-place spinner shown while the agent is invoking. Each frame is rewritten
 * over the prior one with a carriage return; on completion the line is
 * cleared and the agent's output is printed in its place.
 */
data class Spinner(
    val frames: List<String>,
    val intervalMs: Long = 150L,
) {
    val isEmpty: Boolean get() = frames.isEmpty()

    companion object {
        /** ASCII cat with rotating face — fired during inference. */
        val CAT = Spinner(listOf(
            ">^_^<  thinking",
            ">^.^<  thinking.",
            ">-_-<  thinking..",
            ">^.^<  thinking...",
        ))

        /** No-op — disables the spinner. */
        val NONE = Spinner(emptyList())
    }
}
