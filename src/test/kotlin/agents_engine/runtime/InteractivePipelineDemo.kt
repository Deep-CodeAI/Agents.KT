package agents_engine.runtime

import agents_engine.composition.pipeline.then
import agents_engine.core.agent
import kotlin.system.exitProcess

// Manually drive a LiveShow REPL against a real two-stage Pipeline (#982).
//
// Exercises the `LiveShow.from(pipeline: Pipeline<String, *>)` overload from
// #981 — proves the REPL works with composed structures, not just single
// agents. Lives under src/test/kotlin so it never ships in the published
// JAR.
//
// Prerequisites:
//   1. Ollama running locally on http://localhost:11434
//   2. `ollama pull gpt-oss:20b`
//
// Note: this demo uses gpt-oss:20b instead of gemma3:4b because two-stage
// pipelines need the LLM to keep a structured QUESTION/PLAN format across
// turns. README §Known Limitations explicitly notes that small models
// (gemma3:4b) are unreliable for multi-step reasoning of this shape, while
// tool-native models (gpt-oss:20b) handle it cleanly.
//
// Run:
//   ./gradlew interactivePipeline --console=plain -q
//
// Try:
//   plan> what is the capital of France
//   plan> write a haiku about coffee
//   plan> /help
//   plan> /quit         (or Ctrl-D)
//
// Pipeline shape: planner → executor.
//   planner: takes the user's question, outputs a short 2–4 step plan
//   executor: receives the plan as INPUT (not the original user text) and
//             produces the final answer the user sees
// The REPL only displays the executor's output. The planner's plan is
// internal scaffolding the user never sees directly. Multi-turn history
// (string-concat) prepends prior turns to the planner's input, same as
// the single-agent demo.

private const val MODEL = "gpt-oss:20b"
private const val HOST  = "localhost"
private const val PORT  = 11434

fun main(args: Array<String>) {
    val planner = agent<String, String>("planner") {
        prompt("""
            You are a planner. The user has asked something. Your reply
            MUST follow this exact format and contain NOTHING else:

            QUESTION: <a self-contained restatement of the user's most
                       recent question — inline any relevant context from
                       prior turns so the question makes sense alone>
            PLAN:
            1. <one short imperative step>
            2. <one short imperative step>
            3. <optional third step>

            Do not answer the question yourself; just emit the format
            above. The next agent will use both QUESTION and PLAN to
            produce the user-facing answer — it does NOT see the prior
            turns, so the QUESTION line is the only context it gets.

            Example. If the conversation is:
              --- user ---
              name a programming language
              --- assistant ---
              Python.
              --- user ---
              what year was it created
            Then QUESTION should read "What year was Python created?",
            NOT just "what year was it created".

            Prior turns above the current message are separated by
            --- user --- / --- assistant --- markers.
        """.trimIndent())
        model {
            ollama(MODEL); host = HOST; port = PORT
            temperature = 0.2
        }
        skills {
            skill<String, String>("plan", "Decompose the user request into a brief plan") { tools() }
        }
    }

    val executor = agent<String, String>("executor") {
        prompt("""
            You receive input with two sections:
              QUESTION: <the user's question>
              PLAN: <numbered steps>

            Use both to produce the FINAL user-facing answer in 1–3
            sentences. Answer the QUESTION directly. Do NOT repeat the
            plan; do NOT mention "Step" or "PLAN" in your reply; do NOT
            ask for more input.
        """.trimIndent())
        model {
            ollama(MODEL); host = HOST; port = PORT
            temperature = 0.3
        }
        skills {
            skill<String, String>("execute", "Answer the question using the plan") { tools() }
        }
    }

    val pipeline = planner then executor

    println()
    println("============================================================")
    println("LiveShow Pipeline demo (#982) — planner → executor")
    println("Model: $MODEL @ $HOST:$PORT (first run may take a moment to load)")
    println("Type a request. The pipeline plans privately, then answers.")
    println("/help for commands. /quit (or Ctrl-D) to exit.")
    println("============================================================")
    println()

    val rc = LiveRunner.serve(pipeline, args) {
        prompt = "plan> "
    }

    println()
    println("(REPL exited cleanly)")
    exitProcess(rc)
}
