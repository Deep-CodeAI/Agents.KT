package agents_engine.runtime

import agents_engine.core.agent
import kotlin.system.exitProcess

// Manually drive a LiveShow REPL against a real Ollama model (#981).
//
// Lives under src/test/kotlin so it never ships in the published JAR.
// Wired up via the `interactiveLiveShow` Gradle task in build.gradle.kts —
// uses JavaExec (Test task can't forward stdin) with the test classpath.
//
// Prerequisites:
//   1. Ollama running locally on http://localhost:11434
//   2. The gemma3:4b model pulled:  `ollama pull gemma3:4b`
//
// Run:
//   ./gradlew interactiveLiveShow --console=plain -q
//
// Try:
//   gemma> hi, what are you good at?
//   gemma> write me a haiku about kotlin
//   gemma> /help
//   gemma> /clear        (user-side: wipes conversation history)
//   gemma> /quit         (user-side: leave the REPL)  or Ctrl-D
//   gemma> bye           (model-side: gemma calls the exit_app tool)
//
// Two ways to exit are intentional:
//   - `/quit` is a slash command handled inside LiveShow — never reaches
//     the LLM.
//   - The `exit_app` tool is exposed to the LLM. Saying "exit" / "bye" /
//     "I'm done" should make gemma emit a tool call; the tool calls
//     System.exit. Because gemma3:4b doesn't support native tools, this
//     exercises the inline-tool-call fallback path (#706).
//
// History is on (default cap 20 turns). Each turn after the first sees the
// prior transcript prepended via `--- user ---` / `--- assistant ---`
// delimiters, so gemma should be able to reference what was said earlier.

private const val MODEL = "gemma3:4b"
private const val HOST  = "localhost"
private const val PORT  = 11434

fun main(args: Array<String>) {
    val gemma = agent<String, String>("gemma") {
        prompt("""
            You are a friendly, concise terminal assistant.
            Keep responses short — 1 to 3 sentences unless the user asks
            for code or a list.
            When prior turns appear above the current user message
            (separated by --- user --- / --- assistant --- markers), use
            them as conversation context.

            You have exactly ONE tool. Do NOT invent or call any other
            tool name — there are no math, search, calc, time, or other
            tools available. For everything except exit, just answer in
            plain text.

              exit_app — call with no arguments when the user says they
              want to exit, quit, leave, close the app, stop, are done,
              say bye/goodbye, or any clear synonym. Do NOT call it for
              casual greetings, math, or unrelated requests.
        """.trimIndent())
        model {
            ollama(MODEL)
            host = HOST
            port = PORT
            temperature = 0.3
        }
        tools {
            tool(
                "exit_app",
                "Close this application. Use when the user asks to exit, quit, leave, or end the session.",
            ) { _ ->
                println()
                println("(gemma called exit_app — shutting down)")
                exitProcess(0)
            }
        }
        skills {
            skill<String, String>("chat", "Have a conversation with the user, ending it on request") {
                // Listing exit_app marks this skill as LLM-driven AND
                // authorizes gemma to call it. Because gemma3:4b doesn't
                // support native tool calls, the framework's inline-tool-call
                // fallback path (#706) kicks in — gemma emits a single JSON
                // object `{"tool":"exit_app","arguments":{}}` and the loop
                // dispatches it. This demo exercises that whole stack.
                tools("exit_app")
            }
        }
    }

    println()
    println("============================================================")
    println("LiveShow interactive demo (#981) — model: $MODEL @ $HOST:$PORT")
    println("Type messages, then Enter. /help for commands. /quit to exit.")
    println("History is on (default cap = 20 turns) — gemma sees the prior")
    println("transcript prepended to each new turn.")
    println("============================================================")
    println()

    val rc = LiveRunner.serve(gemma, args) {
        prompt = "gemma> "
        slash("now") { println("server time: ${java.time.Instant.now()}") }
    }

    println()
    println("(REPL exited cleanly)")
    exitProcess(rc)
}
