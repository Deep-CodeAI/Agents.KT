package agents_engine.observability

import agents_engine.content.modality
import agents_engine.core.Agent
import agents_engine.core.AgentRuntimeContext
import agents_engine.core.PipelineEvent
import agents_engine.core.observe
import agents_engine.model.TokenUsage
import agents_engine.runtime.events.AgentEvent
import java.io.File
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.StandardOpenOption.CREATE
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.pathString

/**
 * Append-only JSONL audit exporter for agent lifecycle events.
 *
 * The exporter intentionally emits metadata, identifiers, and type names only:
 * tool arguments, tool results, streamed text, generated output, and exception
 * messages are omitted so common secret-bearing values do not enter the audit log.
 */
class JsonlAuditExporter(
    private val path: Path,
    private val rotation: JsonlRotation = JsonlRotation.None,
    private val maxBufferedLines: Int = 1_024,
    private val logger: (message: String, cause: Throwable?) -> Unit = DEFAULT_LOGGER,
    private val clock: Clock = Clock.systemUTC(),
) : AutoCloseable {

    private val bufferedLines = ArrayDeque<String>()
    private var activeDate: LocalDate = currentRotationDate()

    constructor(
        file: File,
        rotation: JsonlRotation = JsonlRotation.None,
        maxBufferedLines: Int = 1_024,
        logger: (message: String, cause: Throwable?) -> Unit = DEFAULT_LOGGER,
        clock: Clock = Clock.systemUTC(),
    ) : this(file.toPath(), rotation, maxBufferedLines, logger, clock)

    fun write(event: PipelineEvent) {
        writeRow(rowFor(event))
    }

    fun write(event: AgentEvent<*>) {
        // #2406 — reasoning is high-volume and potentially sensitive; it is a
        // live-stream-only signal and is deliberately NOT persisted to the
        // PII-safe on-disk audit (one row per reasoning chunk would be noise).
        if (event is AgentEvent.Reasoning) return
        writeRow(rowFor(event))
        if (event is AgentEvent.SkillCompleted || event is AgentEvent.Failed) {
            flushPending()
        }
    }

    override fun close() {
        flushPending()
    }

    private fun writeRow(row: Map<String, Any?>) {
        val line = encodeJson(row)
        if (bufferedLines.isNotEmpty()) {
            buffer(line)
            flushPending()
            return
        }
        if (!tryAppend(line)) {
            buffer(line)
        }
        flushPending()
    }

    private fun flushPending() {
        while (bufferedLines.isNotEmpty()) {
            val line = bufferedLines.first()
            if (!tryAppend(line)) return
            bufferedLines.removeFirst()
        }
    }

    private fun buffer(line: String) {
        if (maxBufferedLines <= 0) {
            log("JSONL audit exporter dropped line because buffering is disabled", null)
            return
        }
        if (bufferedLines.size >= maxBufferedLines) {
            bufferedLines.removeFirst()
            log("JSONL audit exporter dropped oldest buffered line under backpressure", null)
        }
        bufferedLines.addLast(line)
    }

    private fun tryAppend(line: String): Boolean =
        try {
            prepareParent()
            rotateIfNeeded(line)
            Files.writeString(path, line + "\n", UTF_8, CREATE, APPEND)
            true
        } catch (t: Throwable) {
            log("JSONL audit exporter could not append ${path.pathString}", t)
            false
        }

    private fun log(message: String, cause: Throwable?) {
        try {
            logger(message, cause)
        } catch (_: Throwable) {
            // Audit logging must never throw into the agent execution path.
        }
    }

    private fun prepareParent() {
        path.parent?.let { Files.createDirectories(it) }
    }

    private fun rotateIfNeeded(nextLine: String) {
        when (val policy = rotation) {
            JsonlRotation.None -> Unit
            is JsonlRotation.Size -> rotateForSize(policy, nextLine)
            is JsonlRotation.Daily -> rotateForDay(policy)
        }
    }

    private fun rotateForSize(policy: JsonlRotation.Size, nextLine: String) {
        if (policy.maxBytes <= 0) return
        if (!path.exists() || !path.isRegularFile()) return
        val currentSize = Files.size(path)
        if (currentSize <= 0) return
        val nextBytes = (nextLine + "\n").toByteArray(UTF_8).size
        if (currentSize + nextBytes <= policy.maxBytes) return
        rotateNumeric()
    }

    private fun rotateForDay(policy: JsonlRotation.Daily) {
        val today = LocalDate.now(clock.withZone(policy.zoneId))
        if (today == activeDate) return
        if (path.exists() && path.isRegularFile() && Files.size(path) > 0) {
            rotateWithSuffix(activeDate.toString())
        }
        activeDate = today
    }

    private fun rotateNumeric() {
        if (!path.exists() || !path.isRegularFile()) return
        val parent = path.parent ?: Path.of(".")
        val prefix = path.name + "."
        val stream = Files.list(parent)
        val last = try {
            stream.iterator().asSequence()
                .map { it.fileName.toString() }
                .filter { it.startsWith(prefix) }
                .mapNotNull { it.removePrefix(prefix).toIntOrNull() }
                .maxOrNull() ?: 0
        } finally {
            stream.close()
        }
        for (suffix in last downTo 1) {
            val from = path.resolveSibling("${path.name}.$suffix")
            val to = path.resolveSibling("${path.name}.${suffix + 1}")
            if (Files.exists(from)) Files.move(from, to, REPLACE_EXISTING)
        }
        Files.move(path, path.resolveSibling("${path.name}.1"), REPLACE_EXISTING)
    }

    private fun rotateWithSuffix(suffix: String) {
        if (!path.exists() || !path.isRegularFile()) return
        var target = path.resolveSibling("${path.name}.$suffix")
        var counter = 1
        while (Files.exists(target)) {
            target = path.resolveSibling("${path.name}.$suffix.$counter")
            counter++
        }
        Files.move(path, target, REPLACE_EXISTING)
    }

    private fun currentRotationDate(): LocalDate =
        when (val policy = rotation) {
            is JsonlRotation.Daily -> LocalDate.now(clock.withZone(policy.zoneId))
            else -> LocalDate.now(clock.withZone(ZoneOffset.UTC))
        }

    private fun rowFor(event: PipelineEvent): Map<String, Any?> =
        row(
            context = event.runtimeContext,
            agentId = event.agentName,
            skillId = when (event) {
                is PipelineEvent.SkillChosen -> event.skillName
                else -> null
            },
            toolId = when (event) {
                is PipelineEvent.ToolCalled -> event.toolName
                is PipelineEvent.ToolDenied -> event.toolName
                // #2757 — record the hallucinated tool name as the toolId so
                // audit-stream consumers can group by requested name.
                is PipelineEvent.ToolHallucinated -> event.requestedName
                else -> null
            },
            eventType = event.javaClass.simpleName,
            timestamp = now(),
            inputType = null,
            outputType = when (event) {
                is PipelineEvent.ToolCalled -> typeName(event.result)
                else -> null
            },
            // #2469 — multimodal tool results record one summary per part:
            // "<modality>:<hash-prefix>:<size>:<mime>". No bytes; the
            // ContentRef + modality is the auditable surface. Null on
            // non-ToolResult returns so legacy audit rows are unchanged.
            outputParts = when (event) {
                is PipelineEvent.ToolCalled -> partsSummary(event.result)
                else -> null
            },
            // #2395 — record blocked tool calls in the audit log via the
            // guardrailDecision column. Only the decision *type* is written:
            // the free-text reason can embed offending arg values (e.g. a
            // path), and this exporter is PII-safe by default. The
            // human-readable reason stays on the live PipelineEvent.ToolDenied
            // and on the tracing-bridge spans.
            guardrailDecision = when (event) {
                is PipelineEvent.ToolDenied -> "Deny"
                else -> null
            },
            toolPolicyRisk = when (event) {
                is PipelineEvent.ToolCalled -> event.toolPolicyRisk.manifestName
                is PipelineEvent.ToolDenied -> event.toolPolicyRisk.manifestName
                else -> null
            },
            usedDeclaredCapability = when (event) {
                is PipelineEvent.ToolCalled -> event.usedDeclaredCapability
                is PipelineEvent.ToolDenied -> event.usedDeclaredCapability
                else -> null
            },
            usage = null,
        )

    private fun rowFor(event: AgentEvent<*>): Map<String, Any?> {
        val usage = usageFor(event)
        return row(
            context = event.runtimeContext,
            agentId = event.agentId,
            skillId = when (event) {
                is AgentEvent.Token -> event.skillName
                is AgentEvent.ToolCallStarted -> event.skillName
                is AgentEvent.SkillStarted -> event.skillName
                is AgentEvent.SkillCompleted -> event.skillName
                else -> null
            },
            toolId = when (event) {
                is AgentEvent.ToolCallStarted -> event.toolName
                is AgentEvent.ToolCallFinished -> event.toolName
                else -> null
            },
            eventType = event.javaClass.simpleName,
            timestamp = now(),
            inputType = when (event) {
                is AgentEvent.ToolCallFinished -> "Map"
                else -> null
            },
            outputType = when (event) {
                is AgentEvent.Completed<*> -> typeName(event.output)
                is AgentEvent.ToolCallFinished -> typeName(event.result)
                else -> null
            },
            outputParts = when (event) {
                is AgentEvent.Completed<*> -> partsSummary(event.output)
                is AgentEvent.ToolCallFinished -> partsSummary(event.result)
                else -> null
            },
            toolPolicyRisk = null,
            usedDeclaredCapability = null,
            usage = usage,
        )
    }

    private fun usageFor(event: AgentEvent<*>): TokenUsage? =
        when (event) {
            is AgentEvent.SkillCompleted -> event.tokensUsed
            is AgentEvent.Completed<*> -> event.tokensUsed
            else -> null
        }

    private fun row(
        context: AgentRuntimeContext,
        agentId: String,
        skillId: String?,
        toolId: String?,
        eventType: String,
        timestamp: String,
        inputType: String?,
        outputType: String?,
        toolPolicyRisk: String?,
        usedDeclaredCapability: Boolean?,
        usage: TokenUsage?,
        guardrailDecision: String? = null,
        outputParts: List<String>? = null,
    ): Map<String, Any?> =
        linkedMapOf(
            "requestId" to context.requestId,
            "sessionId" to context.sessionId,
            "manifestHash" to context.manifestHash,
            "agentId" to agentId,
            "skillId" to skillId,
            "toolId" to toolId,
            "eventType" to eventType,
            "timestamp" to timestamp,
            "inputType" to inputType,
            "outputType" to outputType,
            "outputParts" to outputParts,
            "budgetState" to null,
            "guardrailDecision" to guardrailDecision,
            "mcpClientId" to null,
            "toolPolicyRisk" to toolPolicyRisk,
            "usedDeclaredCapability" to usedDeclaredCapability,
            "provider" to usage?.provider,
            "model" to usage?.model,
        )

    private fun now(): String = clock.instant().toString()

    private fun typeName(value: Any?): String? =
        value?.javaClass?.name

    /**
     * #2469 — for [agents_engine.content.ToolResult] return values,
     * render one summary string per part: `"<modality>:<hash-prefix>:
     * <sizeBytes>:<wireMime>"`. Hash prefix is the first 12 hex chars
     * (enough to disambiguate in audit grep, short enough to read).
     * Returns `null` when [value] is not a `ToolResult` — keeps legacy
     * audit rows byte-identical for non-multimodal returns.
     *
     * Crucially: no blob bytes enter the audit row. Modality + ref is
     * the auditable surface.
     */
    private fun partsSummary(value: Any?): List<String>? {
        val toolResult = value as? agents_engine.content.ToolResult ?: return null
        return toolResult.parts.map { part ->
            when (part) {
                is agents_engine.content.Content.Text ->
                    "${part.modality}:inline:${part.text.length}:text/plain"
                is agents_engine.content.Content.Image ->
                    "${part.modality}:${part.ref.hash.take(12)}:${part.ref.sizeBytes}:${part.mime.wireMime}"
                is agents_engine.content.Content.Audio ->
                    "${part.modality}:${part.ref.hash.take(12)}:${part.ref.sizeBytes}:${part.mime.wireMime}"
                is agents_engine.content.Content.Video ->
                    "${part.modality}:${part.ref.hash.take(12)}:${part.ref.sizeBytes}:${part.mime.wireMime}"
                is agents_engine.content.Content.Document ->
                    "${part.modality}:${part.ref.hash.take(12)}:${part.ref.sizeBytes}:${part.mime.wireMime}"
            }
        }
    }

    private fun encodeJson(value: Any?): String =
        when (value) {
            null -> "null"
            is String -> "\"${escapeJson(value)}\""
            is Number, is Boolean -> value.toString()
            is Map<*, *> -> value.entries.joinToString(prefix = "{", postfix = "}") { (key, mapValue) ->
                "\"${escapeJson(key.toString())}\":${encodeJson(mapValue)}"
            }
            is Iterable<*> -> value.joinToString(prefix = "[", postfix = "]") { encodeJson(it) }
            else -> "\"${escapeJson(value.toString())}\""
        }

    private fun escapeJson(value: String): String =
        buildString(value.length) {
            value.forEach { ch ->
                when (ch) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\b' -> append("\\b")
                    '\u000C' -> append("\\f")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> {
                        if (ch < ' ') {
                            append("\\u")
                            append(ch.code.toString(16).padStart(4, '0'))
                        } else {
                            append(ch)
                        }
                    }
                }
            }
        }

    private companion object {
        private val JUL_LOGGER = Logger.getLogger(JsonlAuditExporter::class.java.name)

        val DEFAULT_LOGGER: (String, Throwable?) -> Unit = { message, cause ->
            if (cause == null) {
                JUL_LOGGER.warning(message)
            } else {
                JUL_LOGGER.log(Level.WARNING, message, cause)
            }
        }
    }
}

sealed interface JsonlRotation {
    data object None : JsonlRotation

    data class Size(val maxBytes: Long) : JsonlRotation

    data class Daily(val zoneId: ZoneId = ZoneOffset.UTC) : JsonlRotation
}

val <IN, OUT> Agent<IN, OUT>.events: AgentJsonlExports
    get() = AgentJsonlExports(this)

class AgentJsonlExports internal constructor(private val agent: Agent<*, *>) {
    fun export(block: AgentJsonlExportBuilder.() -> Unit): List<JsonlAuditExporter> {
        val builder = AgentJsonlExportBuilder(agent)
        builder.block()
        return builder.exporters.toList()
    }

    /**
     * #2886 — wire a tamper-evident [ToolAuditLedger] to this agent. Every tool action is
     * auto-recorded to an append-only, Merkle-chained file: a [PipelineEvent.ToolCalled] as
     * `APPROVED`, a [PipelineEvent.ToolDenied] as `DENIED` (with the reason), a
     * [PipelineEvent.ToolHallucinated] as `HALLUCINATED`. PII-safe (the result is hashed,
     * never stored). Returns the ledger so the caller can [ToolAuditLedger.verify] it later.
     *
     * callId-keying of the denied/hallucinated rows lands once `PipelineEvent` carries the
     * callId (the approved rows already join via the AgentEvent layer) — #2886 follow-up.
     */
    fun ledger(file: File): ToolAuditLedger {
        val ledger = ToolAuditLedger(file.toPath())
        agent.observe { event ->
            when (event) {
                is PipelineEvent.ToolCalled ->
                    ledger.record(event.toolName, LedgerDecision.APPROVED, result = event.result)
                is PipelineEvent.ToolDenied ->
                    ledger.record(event.toolName, LedgerDecision.DENIED, denialReason = event.reason)
                is PipelineEvent.ToolHallucinated ->
                    ledger.record(event.requestedName, LedgerDecision.HALLUCINATED)
                else -> Unit
            }
        }
        return ledger
    }
}

class AgentJsonlExportBuilder internal constructor(private val agent: Agent<*, *>) {
    internal val exporters = mutableListOf<JsonlAuditExporter>()

    fun file(path: String): File = File(path)

    fun jsonl(
        file: File,
        rotation: JsonlRotation = JsonlRotation.None,
        maxBufferedLines: Int = 1_024,
        clock: Clock = Clock.systemUTC(),
        logger: (message: String, cause: Throwable?) -> Unit = DEFAULT_EXPORT_LOGGER,
    ): JsonlAuditExporter {
        val exporter = JsonlAuditExporter(
            file = file,
            rotation = rotation,
            maxBufferedLines = maxBufferedLines,
            logger = logger,
            clock = clock,
        )
        agent.observe { exporter.write(it) }
        exporters += exporter
        return exporter
    }

    private companion object {
        private val DEFAULT_EXPORT_LOGGER: (String, Throwable?) -> Unit =
            { message, cause ->
                if (cause == null) {
                    Logger.getLogger(JsonlAuditExporter::class.java.name).warning(message)
                } else {
                    Logger.getLogger(JsonlAuditExporter::class.java.name).log(Level.WARNING, message, cause)
                }
            }
    }
}
