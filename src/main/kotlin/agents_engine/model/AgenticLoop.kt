package agents_engine.model

import agents_engine.core.Agent
import agents_engine.core.Skill
import agents_engine.generation.fromLlmOutput
import kotlin.reflect.KClass

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

    // Action tools: tools the skill explicitly lists + auto-injected memory tools
    val skillToolDefs = skill.toolNames?.mapNotNull { agent.toolMap[it] } ?: emptyList()
    val memoryToolDefs = if (agent.memoryBank != null)
        agent.toolMap.values.filter { it.name in setOf("memory_read", "memory_write", "memory_search") }
    else emptyList()
    val actionToolDefs = (skillToolDefs + memoryToolDefs).distinctBy { it.name }

    // Knowledge tools: exposed lazily — LLM calls them to load context on demand
    val knowledgeToolDefs = skill.knowledgeTools().map { kt ->
        ToolDef(kt.name, kt.description) { _ -> kt.call() }
    }
    val knowledgeToolMap = knowledgeToolDefs.associateBy { it.name }

    val allToolDefs = actionToolDefs + knowledgeToolDefs
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
                @Suppress("UNCHECKED_CAST")
                return (skill.outputTransformer as? ((String) -> Any?))?.invoke(response.content)
                    ?: parseOutput(response.content, agent.outType)
                    ?: error("Could not parse LLM output as ${agent.outType.simpleName}: '${response.content}'")
            }
            is LlmResponse.ToolCalls -> {
                messages.add(LlmMessage("assistant", "", response.calls))
                for (call in response.calls) {
                    val isKnowledge = call.name in knowledgeToolMap
                    val tool = agent.toolMap[call.name]
                        ?: knowledgeToolMap[call.name]
                        ?: error("Tool '${call.name}' not found in agent '${agent.name}'. Available: ${agent.toolMap.keys + knowledgeToolMap.keys}")
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
    try {
        return tool.executor(call.arguments)
    } catch (e: Throwable) {
        if (handler == null) throw e

        val result = handler.handleExecutionError(e)
        when (result) {
            is RepairResult.Retry -> {
                repeat(result.maxAttempts) { attempt ->
                    try {
                        return tool.executor(call.arguments)
                    } catch (_: Throwable) {
                        if (attempt == result.maxAttempts - 1) {
                            throw ToolExecutionException(
                                "Tool '${call.name}' failed after ${result.maxAttempts} retries", e
                            )
                        }
                    }
                }
                throw ToolExecutionException(
                    "Tool '${call.name}' failed after ${result.maxAttempts} retries", e
                )
            }
            is RepairResult.Fixed -> return result.value
            is RepairResult.Escalated -> return "ERROR: Tool '${call.name}' failed: ${result.reason} " +
                "(severity: ${result.severity}). Please retry with corrected arguments."
            is RepairResult.Unrecoverable -> throw ToolExecutionException(
                "Tool '${call.name}' failed and recovery was unrecoverable", e
            )
            null -> throw e
        }
    }
}

private fun parseOutput(text: String, outType: KClass<*>): Any? = when {
    outType == String::class -> text
    else -> @Suppress("UNCHECKED_CAST") (outType as KClass<Any>).fromLlmOutput(text)
}
