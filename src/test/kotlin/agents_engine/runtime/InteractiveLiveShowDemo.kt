package agents_engine.runtime

import agents_engine.core.agent
import kotlin.system.exitProcess

// Manually drive a LiveShow REPL from your terminal (#981).
//
// Lives under src/test/kotlin so it never ships in the published JAR.
// Wired up via the `interactiveLiveShow` Gradle task in build.gradle.kts —
// uses JavaExec (Test task can't forward stdin) with the test classpath.
//
// Run:
//   ./gradlew interactiveLiveShow --console=plain -q
//
// The agent is a deterministic echo. The goal is to feel out the REPL UX
// (prompt, history prepending, slash commands) without an LLM dependency.
// Try:
//   echo> hello
//   echo> what was that
//   echo> /help
//   echo> /clear
//   echo> /quit       (or Ctrl-D)

fun main(args: Array<String>) {
    val echo = agent<String, String>("echo") {
        skills {
            skill<String, String>("op", "Echo back, uppercased, with input length") {
                implementedBy { input -> "[len=${input.length}] ECHO: ${input.uppercase()}" }
            }
        }
    }

    println()
    println("============================================================")
    println("LiveShow interactive demo (#981)")
    println("Type messages, then Enter. /help for commands. /quit to exit.")
    println("History is on (default cap = 20 turns) — a second message")
    println("will see the prior transcript prepended to its input. Try a")
    println("short follow-up like 'and what about now?' to watch the")
    println("transcript grow inside [len=...].")
    println("============================================================")
    println()

    val rc = LiveRunner.serve(echo, args) {
        prompt = "echo> "
        // Custom slash so /now is something to test against.
        slash("now") { println("server time: ${java.time.Instant.now()}") }
    }

    println()
    println("(REPL exited cleanly)")
    exitProcess(rc)
}
