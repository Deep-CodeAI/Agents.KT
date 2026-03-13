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

    // System: agent prompt + skill context + tool instructions
    val toolDefs = skill.toolNames?.mapNotNull { agent.toolMap[it] } ?: emptyList()
    val client = config.client ?: OllamaClient(config.host, config.port, config.name, config.temperature, toolDefs)
    val systemContent = buildString {
        if (agent.prompt.isNotBlank()) { append(agent.prompt); append("\n\n") }
        append(skill.toLlmContext())
        if (toolDefs.isNotEmpty()) {
            append("\n\nAvailable tools:\n")
            toolDefs.forEach { tool ->
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
                return (skill.outputParser as? ((String) -> Any?))?.invoke(response.content)
                    ?: parseOutput(response.content, agent.outType)
                    ?: error("Could not parse LLM output as ${agent.outType.simpleName}: '${response.content}'")
            }
            is LlmResponse.ToolCalls -> {
                messages.add(LlmMessage("assistant", "", response.calls))
                for (call in response.calls) {
                    val tool = agent.toolMap[call.name]
                        ?: error("Tool '${call.name}' not found in agent '${agent.name}'. Available: ${agent.toolMap.keys}")
                    val result = tool.executor(call.arguments)
                    agent.toolUseListener?.invoke(call.name, call.arguments, result)
                    messages.add(LlmMessage("tool", result?.toString() ?: "null"))
                }
            }
        }
    }
}

private fun parseOutput(text: String, outType: KClass<*>): Any? = when {
    outType == String::class -> text
    else -> @Suppress("UNCHECKED_CAST") (outType as KClass<Any>).fromLlmOutput(text)
}
