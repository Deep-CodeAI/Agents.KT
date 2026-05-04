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
//   gemma> /clear        (wipes conversation history)
//   gemma> /quit         (or Ctrl-D)
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
        """.trimIndent())
        model {
            ollama(MODEL)
            host = HOST
            port = PORT
            temperature = 0.3
        }
        skills {
            skill<String, String>("chat", "Have a conversation with the user") {
                // Empty tools() marks the skill as LLM-driven — the model
                // produces the response directly. No action tools are
                // exposed, so the inline-tool-call fallback path (#706)
                // never triggers.
                tools()
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
