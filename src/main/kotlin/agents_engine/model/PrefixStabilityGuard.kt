package agents_engine.model

import agents_engine.core.Agent
import java.security.MessageDigest
import java.util.WeakHashMap
import java.util.logging.Logger

/**
 * `agents_engine/model/PrefixStabilityGuard.kt` — the silent-cache-killer
 * detector (#2657, part of the #2655 prompt-caching epic).
 *
 * **The problem.** A vendor cache hit requires a byte-identical prefix
 * across calls (within TTL, above a min size). The vendor does not error
 * on a miss — it just charges full price. So a single millis timestamp
 * interpolated into the system prompt, a per-call request UUID, or a
 * non-deterministic tool ordering will silently turn every call into a
 * cache miss with no signal to the deployer.
 *
 * **What the guard does.**
 * - Hashes the content of every cache-hinted [LlmMessage] as the agentic
 *   loop builds the request.
 * - Remembers per-agent / per-segment hashes between invocations. When
 *   the hash for the same segment changes between two consecutive
 *   invocations of the same `Agent` instance, emits a warning ("cacheable
 *   segment changed between invocations") so the deployer sees it.
 * - On first sighting, runs a pattern probe over the segment content
 *   looking for the obvious silent killers — millisecond timestamps,
 *   ISO-8601 datetimes, full UUIDs — and warns immediately.
 *
 * **Why a separate guard rather than just hashing the manifest.** Manifest
 * hashes cover capability shape, not prompt content; a deployer can keep
 * the same tool set and still silently break caching by templating a
 * timestamp into the system prompt. This guard sits between the prompt
 * builder and the wire so it catches that case.
 *
 * **Off when caching is off.** A message without a [CacheHint] is
 * ignored — if the deployer turned caching off, there is no cache to
 * bust and the guard stays silent even on otherwise-suspicious content.
 *
 * **Concurrency.** Backing store is a [WeakHashMap] keyed by `Agent`
 * identity. Reads + writes are synchronized; per-segment maps are read
 * with their own lock. A frequently-invoked agent only allocates the
 * per-segment map once.
 */
internal object PrefixStabilityGuard {

    /** First N bytes of SHA-256 rendered as hex. Enough to distinguish accidental drift. */
    private const val SEGMENT_HASH_BYTES = 16

    /**
     * Per-`Agent` map from segment key to last-observed content hash.
     * `WeakHashMap` so the guard's state doesn't keep agents alive past
     * their natural lifecycle (e.g. test agents going out of scope).
     */
    private val seenHashes: MutableMap<Agent<*, *>, MutableMap<String, String>> = WeakHashMap()

    /**
     * Inspect a cache-hinted [LlmMessage]. No-op when the message has no
     * cache hint. Emits a `WARNING` log when:
     * - The same segment's content hash differs from the previously-seen
     *   one for the same [Agent] (the unstable-prefix case).
     * - On first sighting, the content matches a known per-call-variance
     *   pattern (timestamp / UUID).
     */
    fun observe(agent: Agent<*, *>, message: LlmMessage) {
        val hint = message.cacheHint ?: return
        val key = segmentKey(hint.segment)
        val hash = sha256Prefix(message.content)

        val priorMap = synchronized(seenHashes) {
            seenHashes.getOrPut(agent) { mutableMapOf() }
        }
        val prior = synchronized(priorMap) { priorMap[key] }
        if (prior != null && prior != hash) {
            LOGGER.warning(
                "Cacheable segment [$key] for agent \"${agent.name}\" changed between invocations " +
                    "(prior=$prior, current=$hash). Vendor cache will MISS until the prefix is stable again. " +
                    "Check for timestamps, UUIDs, request ids, or non-deterministic tool/section ordering " +
                    "in the segment content."
            )
        }
        synchronized(priorMap) { priorMap[key] = hash }

        // First-sighting variance probe — the value of catching this on
        // call #1 is high: a deployer who has stabilizable content can fix
        // it before paying for a single non-cached run.
        if (prior == null) {
            varianceProbe(key, message.content, agent.name)
        }
    }

    /**
     * Test/lifecycle helper — forget all stored hashes for an [agent].
     * Use in tests that build a fresh agent and want a clean run, or
     * after an agent's prompt / tool catalog deliberately changes
     * (capability rev) and the deployer wants to suppress the first
     * "changed" warning on the next call.
     */
    fun reset(agent: Agent<*, *>) {
        synchronized(seenHashes) { seenHashes.remove(agent) }
    }

    private fun segmentKey(seg: CacheSegment): String = when (seg) {
        CacheSegment.SystemPrompt -> "SystemPrompt"
        CacheSegment.ToolDefs -> "ToolDefs"
        CacheSegment.Conversation -> "Conversation"
        is CacheSegment.Custom -> "Custom:${seg.id}"
    }

    private fun sha256Prefix(content: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(content.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(SEGMENT_HASH_BYTES * 2)
        for (i in 0 until SEGMENT_HASH_BYTES) {
            sb.append(HEX[(bytes[i].toInt() ushr 4) and 0x0f])
            sb.append(HEX[bytes[i].toInt() and 0x0f])
        }
        return sb.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()

    // 13-digit Unix-millis tokens: vanishingly rare in legitimate static
    // content; almost always a `System.currentTimeMillis()` leak.
    private val UNIX_MILLIS = Regex("\\b\\d{13}\\b")

    // ISO-8601 datetime (date + 'T' + time). Avoids false positives on bare
    // dates which are common in static content.
    private val ISO_8601 = Regex("\\b\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}")

    // Canonical UUID — too specific to occur in legitimate static content
    // by accident. If a UUID shows up in a system prompt, it's almost
    // always per-call.
    private val UUID_PATTERN = Regex(
        "\\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\b"
    )

    private fun varianceProbe(key: String, content: String, agentName: String) {
        UNIX_MILLIS.find(content)?.let { m ->
            LOGGER.warning(
                "Cacheable segment [$key] for agent \"$agentName\" contains what looks like a Unix-millis " +
                    "timestamp (\"${m.value}\"). Per-call timestamps in cached segments silently kill caching. " +
                    "Move the timestamp out of the cached prefix or render it from the user turn."
            )
            return
        }
        ISO_8601.find(content)?.let { m ->
            LOGGER.warning(
                "Cacheable segment [$key] for agent \"$agentName\" contains what looks like an ISO-8601 " +
                    "timestamp (\"${m.value}\"). Per-call timestamps in cached segments silently kill caching. " +
                    "Move the timestamp out of the cached prefix or render it from the user turn."
            )
            return
        }
        UUID_PATTERN.find(content)?.let { m ->
            LOGGER.warning(
                "Cacheable segment [$key] for agent \"$agentName\" contains what looks like a UUID " +
                    "(\"${m.value}\"). Per-call UUIDs in cached segments silently kill caching. " +
                    "Move the UUID out of the cached prefix or use a stable identifier."
            )
        }
    }

    private val LOGGER: Logger = Logger.getLogger(PrefixStabilityGuard::class.java.name)
}
