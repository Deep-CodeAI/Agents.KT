package agents_engine.model

import agents_engine.core.Agent
import agents_engine.generation.constructFromMap
import agents_engine.runtime.events.AgentEvent
import java.util.concurrent.atomic.AtomicReference
import kotlin.reflect.KClass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * #3376 batch 3 — the per-tool-call execution subsystem extracted from `AgenticLoop`: the budget gate
 * (arg-size cap + per-tool timeout), the 4-layer recovery ladder (invalid-args repair, typed-arg
 * validation, execution-error repair, escalation), and the `ToolCallFinished` event emit. The loop's
 * `executeToolWithBudgetHandlingEvents` wrapper delegates here. These were private to the loop and are
 * now `internal` so the recovery ladder is directly unit-testable. Behavior-preserving move.
 */
internal const val MAX_ARGUMENT_REPAIR_STEPS = 8

/**
 * Wrap tool execution in a per-tool wall-clock timeout when one is configured.
 *
 * Regular tools still use the pre-suspend sacrificial worker thread so blocking
 * lambdas can be interrupted. Session-aware tools are already suspend-shaped, so
 * they use coroutine cancellation via `withTimeout` (#1903).
 */
// #2888 — byte size of a tool call's arguments: the provider wire form when
// present, else the serialized argument map (zero-dep, already in this package).
internal fun toolArgsByteSize(call: ToolCall): Long =
    (call.rawArguments ?: InlineToolCallParser.argsToJson(call.arguments)).toByteArray(Charsets.UTF_8).size.toLong()

internal suspend fun <IN> executeToolWithBudget(
    agent: Agent<IN, *>,
    tool: ToolDef,
    call: ToolCall,
    budget: BudgetConfig,
    emitter: AgentEventEmitter? = null,
): Any? {
    // #2888 — single chokepoint, before either executor branch: hard-cap the
    // argument size so an oversized (often injected) call is rejected before the
    // executor runs. Unconditional like perToolTimeout — not extendable.
    budget.maxToolArgsBytes?.let { cap ->
        val bytes = toolArgsByteSize(call)
        if (bytes > cap) {
            throw BudgetExceededException(
                "Tool '${tool.name}' arguments are $bytes bytes, over the maxToolArgsBytes cap of $cap",
                BudgetReason.TOOL_ARGS_SIZE,
            )
        }
    }
    if (emitter != null) {
        tool.sessionExecutor?.let { sessionExec ->
            val timeout = budget.perToolTimeout
                ?: return sessionExec(call.arguments, emitter)
            return try {
                withTimeout(timeout) {
                    withContext(Dispatchers.IO) {
                        sessionExec(call.arguments, emitter)
                    }
                }
            } catch (_: TimeoutCancellationException) {
                throw BudgetExceededException(
                    "Tool '${tool.name}' exceeded per-tool timeout of $timeout",
                    BudgetReason.PER_TOOL_TIMEOUT,
                )
            }
        }
    }
    val timeout = budget.perToolTimeout ?: return executeToolWithRecovery(agent, tool, call)
    val resultRef = AtomicReference<Any?>(null)
    val errorRef = AtomicReference<Throwable?>(null)
    val worker = Thread({
        try { resultRef.set(executeToolWithRecovery(agent, tool, call)) }
        catch (e: Throwable) { errorRef.set(e) }
    }, "ToolTimeoutWorker-${tool.name}").apply { isDaemon = true; start() }
    worker.join(timeout.inWholeMilliseconds)
    if (worker.isAlive) {
        worker.interrupt()
        throw BudgetExceededException(
            "Tool '${tool.name}' exceeded per-tool timeout of $timeout",
            BudgetReason.PER_TOOL_TIMEOUT,
        )
    }
    errorRef.get()?.let { throw it }
    return resultRef.get()
}

internal fun <IN> executeToolWithRecovery(
    agent: Agent<IN, *>,
    tool: ToolDef,
    call: ToolCall,
): Any? {
    val handler = agent.getToolErrorHandler(call.name)
    call.invalidArgumentsError?.let { parseError ->
        return recoverInvalidArguments(agent, tool, call, handler, parseError)
    }
    val typedError = validateTypedArgsOrNull(tool, call.arguments)
    if (typedError != null) {
        return recoverInvalidArguments(agent, tool, call, handler, typedError)
    }
    return executeToolWithExecutionRecovery(agent, tool, call.name, call.arguments, handler)
}

/**
 * Single source of truth for typed-args validation. Returns null on success,
 * an error message on failure. Invoked at every entry point that hands args
 * to the executor — including the repair path (#658) — so a `Fixed` repair
 * that's syntactically valid but typed-invalid can't bypass the contract.
 */
internal fun validateTypedArgsOrNull(tool: ToolDef, args: Map<String, Any?>): String? {
    val argsClass = tool.argsType ?: return null
    @Suppress("UNCHECKED_CAST")
    val constructed = (argsClass as KClass<Any>).constructFromMap(args)
    return if (constructed == null) {
        "Could not deserialize ${argsClass.simpleName} from arguments: $args"
    } else null
}

internal fun <IN> recoverInvalidArguments(
    agent: Agent<IN, *>,
    tool: ToolDef,
    call: ToolCall,
    handler: ToolErrorHandler?,
    parseError: String,
): Any? {
    val rawArguments = call.rawArguments ?: ""
    if (handler == null) {
        throw ToolExecutionException(
            "Tool '${call.name}' received invalid arguments: $parseError",
            IllegalArgumentException(parseError),
        )
    }

    var currentRaw = rawArguments
    var currentError = parseError
    var useInvalidArgsHandler = true

    repeat(MAX_ARGUMENT_REPAIR_STEPS) {
        val result = if (useInvalidArgsHandler) {
            handler.handleInvalidArgs(currentRaw, currentError)
        } else {
            handler.handleDeserializationError(currentRaw, currentError)
        }

        when (result) {
            is RepairResult.Fixed -> {
                val parsed = parseToolArguments(result.value)
                if (parsed.parseError == null) {
                    // #658: re-validate typed args before reaching the executor.
                    val typedError = validateTypedArgsOrNull(tool, parsed.arguments)
                    if (typedError != null) {
                        // Continue the repair loop with the new typed-validation error
                        // — keeps invalidArgs as the failure classification.
                        currentRaw = result.value
                        currentError = typedError
                        useInvalidArgsHandler = true
                        return@repeat
                    }
                    return executeToolWithExecutionRecovery(
                        agent = agent,
                        tool = tool,
                        toolName = call.name,
                        args = parsed.arguments,
                        handler = handler,
                    )
                }
                currentRaw = result.value
                currentError = parsed.parseError
                useInvalidArgsHandler = false
            }
            is RepairResult.Retry -> {
                repeat(result.maxAttempts) {
                    val parsed = parseToolArguments(currentRaw)
                    if (parsed.parseError == null) {
                        val typedError = validateTypedArgsOrNull(tool, parsed.arguments)
                        if (typedError == null) {
                            return executeToolWithExecutionRecovery(
                                agent = agent,
                                tool = tool,
                                toolName = call.name,
                                args = parsed.arguments,
                                handler = handler,
                            )
                        }
                        // Typed validation failed — falls through to the throw below
                    }
                }
                throw ToolExecutionException(
                    "Tool '${call.name}' arguments remained invalid after ${result.maxAttempts} retries",
                    IllegalArgumentException(currentError),
                )
            }
            is RepairResult.Escalated -> return ToolResultRendering.formatEscalatedToolError(call.name, result)
            is RepairResult.Unrecoverable -> throw ToolExecutionException(
                "Tool '${call.name}' argument recovery was unrecoverable",
                IllegalArgumentException(currentError),
            )
            null -> throw ToolExecutionException(
                "Tool '${call.name}' received invalid arguments: $currentError",
                IllegalArgumentException(currentError),
            )
        }
    }

    throw ToolExecutionException(
        "Tool '${call.name}' argument recovery exceeded $MAX_ARGUMENT_REPAIR_STEPS repair steps",
        IllegalArgumentException(currentError),
    )
}

internal fun <IN> executeToolWithExecutionRecovery(
    agent: Agent<IN, *>,
    tool: ToolDef,
    toolName: String,
    args: Map<String, Any?>,
    handler: ToolErrorHandler?,
): Any? {
    try {
        return tool.executor(args)
    } catch (budget: BudgetExceededException) {
        // #3377 — a budget/safety cap that surfaces from inside a tool executor (e.g. a nested agent
        // invocation hitting maxAgentDepth) is NOT a tool error to recover — it must propagate so the
        // cap actually stops the run. Never let `onError` swallow it.
        throw budget
    } catch (e: Throwable) {
        if (handler == null) throw e

        val result = handler.handleExecutionError(e)
        when (result) {
            is RepairResult.Retry -> {
                repeat(result.maxAttempts) { attempt ->
                    try {
                        return tool.executor(args)
                    } catch (_: Throwable) {
                        if (attempt == result.maxAttempts - 1) {
                            throw ToolExecutionException(
                                "Tool '$toolName' failed after ${result.maxAttempts} retries", e
                            )
                        }
                    }
                }
                throw ToolExecutionException(
                    "Tool '$toolName' failed after ${result.maxAttempts} retries", e
                )
            }
            is RepairResult.Fixed -> return result.value
            is RepairResult.Escalated -> return ToolResultRendering.formatEscalatedToolError(toolName, result)
            is RepairResult.Unrecoverable -> throw ToolExecutionException(
                "Tool '$toolName' failed and recovery was unrecoverable", e
            )
            null -> throw e
        }
    }
}

// #3376 — formatEscalatedToolError / formatDeniedToolError / wrapUntrustedToolResult /
// renderToolResultForLlm moved to ToolResultRendering.kt; parseOutput moved to OutputCoercion.kt.

// #3376 — semconvProviderName / constrainedOutputSchemaFor / defaultClientFor /
// defaultClientForTesting moved to ModelClientFactory.kt.

// #2804 — central emit helper for `AgentEvent.ToolCallFinished`. Replaces
// four near-identical emit blocks (unknown-tool, denied, success, exception)
// each of which differed only in `result` / `isError`. No-op when emitter
// is null or callId is absent (the streaming surface needs both).
internal fun emitToolFinished(
    emitter: AgentEventEmitter?,
    agent: Agent<*, *>,
    call: ToolCall,
    result: Any?,
    isError: Boolean,
) {
    if (emitter == null || call.callId == null) return
    emitter(
        AgentEvent.ToolCallFinished(
            agentId = agent.name,
            callId = call.callId,
            toolName = call.name,
            arguments = call.arguments,
            result = result,
            isError = isError,
        )
    )
}
