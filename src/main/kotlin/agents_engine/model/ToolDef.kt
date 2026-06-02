package agents_engine.model

import kotlin.reflect.KClass
import java.util.logging.Logger

// #2804 — used for FINE/WARNING diagnostics emitted from the built-in tools.
private val TOOL_LOGGER: Logger = Logger.getLogger("agents_engine.model.Tool")

/**
 * `agents_engine/model/ToolDef.kt` — the `ToolDef` shape (wire-level
 * `Map<String, Any?> -> Any?` executor with optional session-aware
 * variant #1752), plus the `Tool<Args, Result>` typed handle (#1015 /
 * #1016) returned by `tool(...)` builders so `Skill.tools(...)` accepts
 * compile-time-checked refs. Includes typed builder overloads, the
 * `argsType` introspection slot, and the `untrustedOutput` flag for
 * sandboxed tool wiring. See
 * `src/main/resources/internals-agent/model/ToolDef.md` (#1837 / #1857).
 */

/**
 * A tool the agentic loop can invoke on the model's behalf.
 *
 * The wire signature is intentionally `Map<String, Any?> -> Any?` because that's
 * what the LLM actually sends and reads. For typed authoring, use the
 * `tool<Args, Result>("name") { args -> ... }` builder — it wraps your typed
 * lambda in a Map-shaped executor and records the [argsType] so downstream
 * consumers (provider schema generation, runtime validation) can introspect it.
 *
 * @property argsType the `@Generable` Args class for typed tools, `null` for
 *   tools authored via the legacy `tool(name, desc) { args: Map -> ... }` form.
 */
class ToolDef(
    val name: String,
    val description: String = "",
    val argsType: KClass<*>? = null,
    /**
     * #2377 — raw JSON Schema for the tool's parameters when [argsType] is
     * null but the schema is known from elsewhere (notably MCP imports that
     * carry an upstream `inputSchema`). Providers prefer [argsType]'s
     * generated schema first, then [parametersSchemaJson], then a closed
     * `additionalProperties:false` empty-object fallback. Must be a valid
     * JSON object literal — providers paste it verbatim into the request
     * body.
     */
    val parametersSchemaJson: String? = null,
    val untrustedOutput: Boolean = false,
    val risk: agents_engine.core.ToolRisk = agents_engine.core.ToolRisk.LOW,
    val policy: agents_engine.core.ToolPolicy? = null,
    /**
     * #1752 — session-aware tool executor. When non-null AND the
     * agentic loop runs under a session (`emitter != null`), this is
     * used instead of [executor]. Allows tools that wrap a sibling
     * agent (Swarm absorb path) to stream the sibling's inner events
     * into the captain's session.
     *
     * Falls back to [executor] when null — preserves byte-for-byte
     * behavior for plain function tools and for non-streaming
     * invocations.
     *
     * Declared BEFORE [executor] so the trailing-lambda construction
     * `ToolDef(name, desc) { args -> ... }` still binds the lambda
     * to [executor].
     */
    val sessionExecutor: (suspend (Map<String, Any?>, agents_engine.model.AgentEventEmitter) -> Any?)? = null,
    val executor: (Map<String, Any?>) -> Any?,
) {
    var errorHandler: ToolErrorHandler? = null
        internal set
}

fun buildBuiltInTools(): List<ToolDef> = listOf(
    ToolDef(
        name = "escalate",
        description = "Signal that you cannot fix the problem. Args: reason (string), severity (LOW/MEDIUM/HIGH/CRITICAL, optional, defaults to HIGH).",
        executor = { args ->
            val reason = args["reason"]?.toString() ?: "Unknown reason"
            val severityStr = args["severity"]?.toString()?.uppercase() ?: "HIGH"
            // #2804 — was a silent fall-through to HIGH that masked LLM-supplied
            // typos / out-of-vocab values. Logging at WARNING surfaces the
            // misuse without changing the routing decision (default-to-HIGH
            // is the safe fallback for the escalate signal).
            val severity = try {
                Severity.valueOf(severityStr)
            } catch (e: IllegalArgumentException) {
                TOOL_LOGGER.warning(
                    "escalate: unknown severity \"$severityStr\" — defaulting to HIGH " +
                        "(valid values: ${Severity.entries.joinToString { it.name }})"
                )
                Severity.HIGH
            }
            throw EscalationException(reason, severity)
        },
    ),
    ToolDef(
        name = "throwException",
        description = "Signal a hard failure — the problem is fundamentally unrecoverable. Args: reason (string).",
        executor = { args ->
            val reason = args["reason"]?.toString() ?: "Unknown reason"
            throw ToolExecutionException(reason)
        },
    ),
)
