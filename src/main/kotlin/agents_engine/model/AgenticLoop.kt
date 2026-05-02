package agents_engine.model

import agents_engine.core.Agent
import agents_engine.core.Skill
import agents_engine.generation.constructFromMap
import agents_engine.generation.fromLlmOutput
import kotlin.reflect.KClass

private const val MAX_ARGUMENT_REPAIR_STEPS = 8

/**
 * Runs the agentic loop for [skill] on [agent] with [input].
 * Returns the parsed output as [Any]; the caller casts it via the agent's castOut.
 */
fun <IN> executeAgentic(
    agent: Agent<IN, *>,
    skill: Skill<*, *>,
    input: IN,
): Any {
    val config = requireNotNull(agent.modelConfig) {
        "Agent '${agent.name}' has no model configured. Add a model { } block."
    }
    val budget = agent.budgetConfig

    val messages = mutableListOf<LlmMessage>()

    // Action tools: tools the skill explicitly lists + agent capabilities + auto-injected memory tools
    val skillToolDefs = skill.toolNames?.mapNotNull { agent.toolMap[it] } ?: emptyList()
    val autoToolDefs = agent.autoToolNames.mapNotNull { agent.toolMap[it] }
    val memoryToolDefs = if (agent.memoryBank != null)
        agent.toolMap.values.filter { it.name in setOf("memory_read", "memory_write", "memory_search") }
    else emptyList()
    val actionToolDefs = (skillToolDefs + autoToolDefs + memoryToolDefs).distinctBy { it.name }

    // Knowledge tools: exposed lazily — LLM calls them to load context on demand
    val knowledgeToolDefs = skill.knowledgeTools().map { kt ->
        ToolDef(kt.name, kt.description) { _ -> kt.call() }
    }
    val knowledgeToolMap = knowledgeToolDefs.associateBy { it.name }

    val allToolDefs = actionToolDefs + knowledgeToolDefs

    // Fail-fast on duplicate tool names across the allowed sources (skill tools,
    // auto tools, memory tools, knowledge entries). `distinctBy` would silently
    // pick a winner; we want this surfaced as a configuration error. See #645.
    val duplicateNames = allToolDefs.groupBy { it.name }.filterValues { it.size > 1 }.keys
    check(duplicateNames.isEmpty()) {
        "Duplicate tool names in allowed tool set for skill '${skill.name}': $duplicateNames. " +
            "A name appears in more than one source (skill tools, auto tools, memory tools, " +
            "knowledge entries) — pick one source per name."
    }

    // Authorization boundary: execution looks up against THIS allowlist only,
    // not the wider agent.toolMap. A model emitting any tool name not in this
    // map will be refused — even if the agent has that tool registered for a
    // different skill. This is the runtime enforcement the prompt does NOT do.
    val allowedToolMap = allToolDefs.associateBy { it.name }

    val client = config.client ?: OllamaClient(config.host, config.port, config.name, config.temperature, allToolDefs)

    val systemContent = buildString {
        if (agent.prompt.isNotBlank()) { append(agent.prompt); append("\n\n") }
        // When knowledge is lazy, use description only — content loads via tool calls
        if (knowledgeToolDefs.isNotEmpty()) append(skill.toLlmDescription())
        else append(skill.toLlmContext())
        if (allToolDefs.isNotEmpty()) {
            append("\n\nAvailable tools:\n")
            allToolDefs.forEach { tool ->
                append("- ${tool.name}")
                if (tool.description.isNotEmpty()) append(": ${tool.description}")
                append("\n")
            }
        }
    }
    if (systemContent.isNotBlank()) messages.add(LlmMessage("system", systemContent))

    // User: serialized input
    messages.add(LlmMessage("user", input.toString()))

    var turns = 0
    while (true) {
        if (turns >= budget.maxTurns)
            throw BudgetExceededException(
                "Agent '${agent.name}' exceeded budget of ${budget.maxTurns} turns"
            )

        val response = client.chat(messages)
        turns++

        when (response) {
            is LlmResponse.Text -> {
                return skill.outputTransformer?.invoke(response.content)
                    ?: parseOutput(response.content, agent.outType)
                    ?: error("Could not parse LLM output as ${agent.outType.simpleName}: '${response.content}'")
            }
            is LlmResponse.ToolCalls -> {
                messages.add(LlmMessage("assistant", "", response.calls))
                for (call in response.calls) {
                    val isKnowledge = call.name in knowledgeToolMap
                    val tool = allowedToolMap[call.name]
                        ?: error(
                            "Tool '${call.name}' is not allowed for skill '${skill.name}'. " +
                                "Allowed: ${allowedToolMap.keys}"
                        )
                    val result = executeToolWithRecovery(agent, tool, call)
                    if (isKnowledge) agent.knowledgeUsedListener?.invoke(call.name, result?.toString() ?: "")
                    else agent.toolUseListener?.invoke(call.name, call.arguments, result)
                    messages.add(LlmMessage("tool", result?.toString() ?: "null"))
                }
            }
        }
    }
}

/**
 * Asks the LLM to pick a skill from [candidates] based on [input].
 * Returns the chosen skill name.
 */
fun <IN> selectSkillByLlm(
    agent: Agent<IN, *>,
    candidates: List<Skill<*, *>>,
    input: IN,
): String {
    val config = requireNotNull(agent.modelConfig) {
        "Agent '${agent.name}' has no model configured for LLM skill selection."
    }

    val systemPrompt = buildString {
        appendLine("You are a skill router. Given the user's input, pick the most appropriate skill.")
        appendLine()
        appendLine("Available skills:")
        candidates.forEach { skill ->
            appendLine()
            appendLine(skill.toLlmDescription())
        }
        appendLine()
        appendLine("Respond with ONLY the skill name, nothing else.")
    }

    val messages = listOf(
        LlmMessage("system", systemPrompt),
        LlmMessage("user", input.toString()),
    )

    val client = config.client ?: OllamaClient(config.host, config.port, config.name, config.temperature)
    val response = client.chat(messages)

    return when (response) {
        is LlmResponse.Text -> response.content.trim()
        is LlmResponse.ToolCalls -> error("Expected text response for skill selection, got tool calls")
    }
}

private fun <IN> executeToolWithRecovery(
    agent: Agent<IN, *>,
    tool: ToolDef,
    call: ToolCall,
): Any? {
    val handler = agent.getToolErrorHandler(call.name)
    call.invalidArgumentsError?.let { parseError ->
        return recoverInvalidArguments(agent, tool, call, handler, parseError)
    }
    // Typed-args pre-validation: for tools authored via tool<Args, _>("..."),
    // attempt @Generable deserialization BEFORE invoking the executor. Failure
    // routes through the same invalidArgs handler as JSON-parse failures.
    tool.argsType?.let { argsClass ->
        @Suppress("UNCHECKED_CAST")
        val constructed = (argsClass as KClass<Any>).constructFromMap(call.arguments)
        if (constructed == null) {
            return recoverInvalidArguments(
                agent, tool, call, handler,
                parseError = "Could not deserialize ${argsClass.simpleName} from arguments: ${call.arguments}",
            )
        }
    }
    return executeToolWithExecutionRecovery(agent, tool, call.name, call.arguments, handler)
}

private fun <IN> recoverInvalidArguments(
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
                        return executeToolWithExecutionRecovery(
                            agent = agent,
                            tool = tool,
                            toolName = call.name,
                            args = parsed.arguments,
                            handler = handler,
                        )
                    }
                }
                throw ToolExecutionException(
                    "Tool '${call.name}' arguments remained invalid after ${result.maxAttempts} retries",
                    IllegalArgumentException(currentError),
                )
            }
            is RepairResult.Escalated -> return formatEscalatedToolError(call.name, result)
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

private fun <IN> executeToolWithExecutionRecovery(
    agent: Agent<IN, *>,
    tool: ToolDef,
    toolName: String,
    args: Map<String, Any?>,
    handler: ToolErrorHandler?,
): Any? {
    try {
        return tool.executor(args)
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
            is RepairResult.Escalated -> return formatEscalatedToolError(toolName, result)
            is RepairResult.Unrecoverable -> throw ToolExecutionException(
                "Tool '$toolName' failed and recovery was unrecoverable", e
            )
            null -> throw e
        }
    }
}

private fun formatEscalatedToolError(toolName: String, result: RepairResult.Escalated): String =
    "ERROR: Tool '$toolName' failed: ${result.reason} " +
        "(severity: ${result.severity}). Please retry with corrected arguments."

private fun parseOutput(text: String, outType: KClass<*>): Any? = when {
    outType == String::class -> text
    else -> @Suppress("UNCHECKED_CAST") (outType as KClass<Any>).fromLlmOutput(text)
}
