package agents_engine.core

import agents_engine.generation.LenientJsonParser
import agents_engine.model.InlineToolCallParser
import agents_engine.model.LlmMessage
import agents_engine.model.TokenUsage
import agents_engine.model.ToolCall
import agents_engine.model.toJsonString
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

/**
 * #2416 / #2386 — snapshot/resume (v1 spike).
 *
 * The design hinge: an agent's resumable state is its **message history +
 * loop counters** (LLM turns are stateless — each call re-sends the whole
 * message list). So "resume" means re-enter the agentic loop seeded with a
 * saved [SessionSnapshot], NOT suspend/serialise a coroutine. `executeAgentic`
 * checkpoints a snapshot at each turn boundary (via its `onTurnCheckpoint`
 * hook) and can be seeded with one (`resumeFrom`).
 */

/** A component whose state can be captured and restored. */
interface Snapshotable<S> {
    fun snapshot(): S
    fun restore(state: S)
}

/**
 * The resumable state of one agent invocation, captured at a turn boundary
 * (after tools complete — never mid-tool). Serialises through plain JSON; no
 * new serializer dependency. `manifestHash` is carried for the Phase-2 restore
 * guard (#2386) — not yet enforced.
 */
data class SessionSnapshot(
    val messages: List<LlmMessage>,
    val turns: Int,
    val toolCalls: Int,
    val toolCallLimit: Int,
    val tokensUsed: TokenUsage?,
    val memory: Map<String, String>,
    val requestId: String,
    val sessionId: String?,
    val manifestHash: String?,
)

/** Persistence backend for [SessionSnapshot], keyed by session id. */
interface SnapshotStore {
    fun save(key: String, snapshot: SessionSnapshot)
    fun load(key: String): SessionSnapshot?
    fun delete(key: String)
}

/** In-process store — tests and single-JVM resume. */
class InMemorySnapshotStore : SnapshotStore {
    private val map = ConcurrentHashMap<String, SessionSnapshot>()
    override fun save(key: String, snapshot: SessionSnapshot) { map[key] = snapshot }
    override fun load(key: String): SessionSnapshot? = map[key]
    override fun delete(key: String) { map.remove(key) }
}

/**
 * On-disk store: one JSON file per key. Writes go to a temp file then
 * atomic-rename, so a crash mid-write can never corrupt the live snapshot —
 * you lose at most the in-flight write, keeping the last good one.
 * (v1: keys are used as filenames; assumes filesystem-safe session ids.)
 */
class FileSnapshotStore(private val dir: Path) : SnapshotStore {
    override fun save(key: String, snapshot: SessionSnapshot) {
        Files.createDirectories(dir)
        val target = dir.resolve("$key.json")
        val tmp = dir.resolve("$key.json.tmp")
        Files.writeString(tmp, SnapshotJson.encode(snapshot))
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    override fun load(key: String): SessionSnapshot? {
        val target = dir.resolve("$key.json")
        return if (Files.exists(target)) SnapshotJson.decode(Files.readString(target)) else null
    }

    override fun delete(key: String) { Files.deleteIfExists(dir.resolve("$key.json")) }
}

/** Minimal JSON codec for [SessionSnapshot] — reuses the existing escaper, arg encoder, and lenient parser. */
internal object SnapshotJson {
    fun encode(s: SessionSnapshot): String = buildString {
        append("{")
        append(""""requestId":${s.requestId.toJsonString()},""")
        append(""""sessionId":${s.sessionId?.toJsonString() ?: "null"},""")
        append(""""manifestHash":${s.manifestHash?.toJsonString() ?: "null"},""")
        append(""""turns":${s.turns},"toolCalls":${s.toolCalls},"toolCallLimit":${s.toolCallLimit},""")
        append(""""tokens":${encodeTokens(s.tokensUsed)},""")
        append(""""memory":{""")
        append(s.memory.entries.joinToString(",") { (k, v) -> "${k.toJsonString()}:${v.toJsonString()}" })
        append("},")
        append(""""messages":[""")
        append(s.messages.joinToString(",") { encodeMessage(it) })
        append("]}")
    }

    private fun encodeTokens(t: TokenUsage?): String =
        if (t == null) "null"
        else """{"prompt":${t.promptTokens},"completion":${t.completionTokens},""" +
            """"cached":${t.cachedInputTokens ?: "null"},"reasoning":${t.reasoningTokens ?: "null"},""" +
            """"provider":${t.provider.toJsonString()},"model":${t.model.toJsonString()}}"""

    private fun encodeMessage(m: LlmMessage): String = buildString {
        append("""{"role":${m.role.toJsonString()},"content":${m.content.toJsonString()}""")
        m.toolCalls?.let { calls ->
            append(""","toolCalls":[""")
            append(calls.joinToString(",") { tc ->
                """{"name":${tc.name.toJsonString()},"arguments":${InlineToolCallParser.argsToJson(tc.arguments)}}"""
            })
            append("]")
        }
        append("}")
    }

    fun decode(json: String): SessionSnapshot {
        val root = LenientJsonParser.parse(json) as? Map<*, *> ?: error("malformed snapshot JSON")
        return SessionSnapshot(
            messages = (root["messages"] as? List<*>).orEmpty().mapNotNull { decodeMessage(it) },
            turns = (root["turns"] as? Number)?.toInt() ?: 0,
            toolCalls = (root["toolCalls"] as? Number)?.toInt() ?: 0,
            toolCallLimit = (root["toolCallLimit"] as? Number)?.toInt() ?: 0,
            tokensUsed = decodeTokens(root["tokens"] as? Map<*, *>),
            memory = (root["memory"] as? Map<*, *>).orEmpty().entries
                .associate { (k, v) -> k.toString() to v.toString() },
            requestId = root["requestId"]?.toString() ?: "",
            sessionId = root["sessionId"] as? String,
            manifestHash = root["manifestHash"] as? String,
        )
    }

    private fun decodeMessage(raw: Any?): LlmMessage? {
        val m = raw as? Map<*, *> ?: return null
        val toolCalls = (m["toolCalls"] as? List<*>)?.mapNotNull { tcRaw ->
            val tc = tcRaw as? Map<*, *> ?: return@mapNotNull null
            val name = tc["name"] as? String ?: return@mapNotNull null
            @Suppress("UNCHECKED_CAST")
            val args = (tc["arguments"] as? Map<*, *>).orEmpty()
                .entries.associate { (k, v) -> k.toString() to v } as Map<String, Any?>
            ToolCall(name = name, arguments = args)
        }
        return LlmMessage(
            role = m["role"]?.toString() ?: "user",
            content = m["content"]?.toString() ?: "",
            toolCalls = toolCalls,
        )
    }

    private fun decodeTokens(t: Map<*, *>?): TokenUsage? {
        if (t == null) return null
        val prompt = (t["prompt"] as? Number)?.toInt() ?: return null
        val completion = (t["completion"] as? Number)?.toInt() ?: return null
        return TokenUsage(
            promptTokens = prompt,
            completionTokens = completion,
            cachedInputTokens = (t["cached"] as? Number)?.toInt(),
            provider = t["provider"]?.toString() ?: "unknown",
            model = t["model"]?.toString() ?: "unknown",
            reasoningTokens = (t["reasoning"] as? Number)?.toInt(),
        )
    }
}
