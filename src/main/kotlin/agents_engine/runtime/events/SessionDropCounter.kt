package agents_engine.runtime.events

import java.util.concurrent.atomic.AtomicLong
import java.util.logging.Logger

/**
 * #4496 — aggregated accounting for events dropped on a session's non-suspending emitter path.
 *
 * The inner emitter is `(AgentEvent<*>) -> Unit`, so it forwards via `channel.trySend`; when the
 * consumer lags behind a fast producer the 64-slot buffer fills and events drop. #2806 made each
 * drop a separate WARNING — under a burst (a token-streaming loop ahead of a slow collector) that
 * is one log line per lost event, which buries the signal in its own noise. This counter replaces
 * the per-event spam with one summary line at session close, and the live count is exposed as
 * `AgentSession.droppedEvents` so callers can *assert* on event-loss instead of scraping logs.
 *
 * Thread-safe ([AtomicLong] / `@Volatile`): the emitter may be invoked from racing branches
 * (`parallel` / `forum` legs) while a consumer reads [droppedEvents] from its own coroutine.
 * Terminal `Completed` / `Failed` events use suspending `send` and are never counted here.
 */
internal class SessionDropCounter(
    private val logger: Logger,
    /** Human description of the owning session for the summary line, e.g. `agent='x', sessionId='…'`. */
    private val sessionDescription: String,
) {
    private val count = AtomicLong(0)

    @Volatile
    private var firstDroppedType: String? = null

    /** Total events dropped so far — live, monotonic. */
    val droppedEvents: Long get() = count.get()

    /** Record one dropped event of [eventType] (a simple class name, for the summary line). */
    fun recordDrop(eventType: String) {
        if (count.getAndIncrement() == 0L) {
            firstDroppedType = eventType
        }
    }

    /** One summary WARNING at session close when anything was dropped; silent otherwise. */
    fun logSummaryAtClose() {
        val dropped = count.get()
        if (dropped > 0L) {
            logger.warning(
                "$dropped event(s) dropped during the session ($sessionDescription; first dropped: " +
                    "${firstDroppedType ?: "?"}) — the consumer was slower than the producer. Collect " +
                    "`events` promptly (or add suspension points in fast producers); terminal " +
                    "Completed/Failed events are never dropped. See AgentSession.droppedEvents."
            )
        }
    }
}
