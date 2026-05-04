package agents_engine.runtime.swarmdemo.exitagent

import agents_engine.core.Agent
import agents_engine.core.agent
import agents_engine.runtime.AgentProvider
import kotlin.system.exitProcess

// exit.jar — sibling agent for the swarm demo (#984). Self-contained.

private const val MODEL = "gpt-oss:120b-cloud"
private const val HOST  = "localhost"
private const val PORT  = 11434

internal fun traceTool(agentName: String, toolName: String, args: Map<String, Any?>, result: Any?) {
    val argsStr = if (args.isEmpty()) "" else args.entries.joinToString(", ") { "${it.key}=${it.value}" }
    val resultStr = result?.toString()?.let { if (it.length > 80) it.take(77) + "..." else it } ?: "null"
    System.err.println("  [$agentName] $toolName($argsStr) → $resultStr")
}

fun buildExitAgent(): Agent<String, String> = agent("exit") {
    prompt("""
        You have exactly ONE tool: `exit_app`. Call it with no arguments
        when the user wants to close, exit, quit, leave, or end the
        session. Do NOT call it for other requests.
    """.trimIndent())
    model { ollama(MODEL); host = HOST; port = PORT; temperature = 0.0 }
    lateinit var exitApp: agents_engine.model.Tool<Map<String, Any?>, Any?>
    tools {
        exitApp = tool("exit_app", "Close the application. Use when the user asks to exit, quit, or end.") { _ ->
            println()
            println("(exit agent — shutting down)")
            exitProcess(0)
        }
    }
    skills {
        skill<String, String>("close", "End the session on user request") {
            tools(exitApp)
        }
    }
    onToolUse { name, args, result -> traceTool("exit", name, args, result) }
}

class ExitProvider : AgentProvider {
    override fun build(): Agent<*, *> = buildExitAgent()
}
