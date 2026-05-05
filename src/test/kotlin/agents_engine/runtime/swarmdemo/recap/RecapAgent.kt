package agents_engine.runtime.swarmdemo.recap

import agents_engine.core.Agent
import agents_engine.core.MemoryBank
import agents_engine.core.agent
import agents_engine.runtime.AgentProvider
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.GraphicsEnvironment
import java.awt.GridLayout
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.SwingUtilities
import javax.swing.WindowConstants

// recap.jar — sibling agent for the swarm demo (#984, #1053). Self-contained:
// own copies of helpers so the JAR doesn't depend on any sibling's classes.
//
// Behavior: when the captain dispatches a user request like "how was that",
// "show me a recap", or "give me your opinion", this agent reads its own
// memory bank, forms a brief opinion on "How fun was that?", optionally
// jots a note for next time, and pops up a Swing window with two panels
// (Memory + Opinion). Falls back to stderr in headless environments.

private const val MODEL = "gpt-oss:120b-cloud"
private const val HOST  = "localhost"
private const val PORT  = 11434

internal fun traceTool(agentName: String, toolName: String, args: Map<String, Any?>, result: Any?) {
    val argsStr = if (args.isEmpty()) "" else args.entries.joinToString(", ") { "${it.key}=${it.value}" }
    val resultStr = result?.toString()?.let { if (it.length > 80) it.take(77) + "..." else it } ?: "null"
    System.err.println("  [$agentName] $toolName($argsStr) → $resultStr")
}

// One bank per JVM run; persists across captain → recap dispatch calls so
// successive invocations see what the agent wrote earlier in the session.
private val recapBank = MemoryBank()

internal fun showRecapWindow(memoryText: String, opinionText: String) {
    val displayMemory  = memoryText.ifBlank { "(empty)" }
    val displayOpinion = opinionText.ifBlank { "(no opinion offered)" }

    if (GraphicsEnvironment.isHeadless()) {
        System.err.println()
        System.err.println("== recap (headless — Swing not available) ==")
        System.err.println("memory:")
        System.err.println(displayMemory.prependIndent("  "))
        System.err.println("opinion:")
        System.err.println(displayOpinion.prependIndent("  "))
        System.err.println("==============================================")
        return
    }

    SwingUtilities.invokeLater {
        val frame = JFrame("recap — How fun was that?")
        frame.defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE
        frame.preferredSize = Dimension(640, 480)

        val grid = JPanel(GridLayout(2, 1, 8, 8))

        val memoryPanel = JPanel(BorderLayout(4, 4))
        memoryPanel.add(JLabel("Memory"), BorderLayout.NORTH)
        val memoryArea = JTextArea(displayMemory).apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
        }
        memoryPanel.add(JScrollPane(memoryArea), BorderLayout.CENTER)
        grid.add(memoryPanel)

        val opinionPanel = JPanel(BorderLayout(4, 4))
        opinionPanel.add(JLabel("Opinion: How fun was that?"), BorderLayout.NORTH)
        val opinionArea = JTextArea(displayOpinion).apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
        }
        opinionPanel.add(JScrollPane(opinionArea), BorderLayout.CENTER)
        grid.add(opinionPanel)

        frame.contentPane.add(grid)
        frame.pack()
        frame.setLocationRelativeTo(null)
        frame.isVisible = true
    }
}

fun buildRecapAgent(): Agent<String, String> = agent("recap") {
    prompt("""
        You are the session recap assistant. When invoked you do exactly this:

        1. Call `memory_read` to see what you remember from earlier turns.
        2. In 1–3 short sentences, form an opinion on the question
           "How fun was that?" — casual, honest, lightly playful. Use
           anything in memory if it fits; if memory is empty, react to
           the user's most recent message instead.
        3. Call `memory_write` once with a single short line summarising
           this turn (e.g. "user asked for fib(10)"). Append, don't replace —
           start the new content with the previous memory if any.
        4. Call `show_recap_window` with two arguments:
             - memory: the FULL memory text after your write (string)
             - opinion: your opinion text (string)
        5. Reply to the captain with one short sentence acknowledging that
           the recap window has been shown. NEVER paste the opinion into
           your reply — it lives in the window.
    """.trimIndent())
    model { ollama(MODEL); host = HOST; port = PORT; temperature = 0.4 }
    memory(recapBank)
    lateinit var showRecap: agents_engine.model.Tool<Map<String, Any?>, Any?>
    tools {
        showRecap = tool(
            "show_recap_window",
            "Open a Swing window showing the agent's memory and its opinion " +
                "on 'How fun was that?'. Arguments: memory (string), opinion (string).",
        ) { args ->
            val memoryText  = args["memory"]?.toString().orEmpty()
            val opinionText = args["opinion"]?.toString().orEmpty()
            showRecapWindow(memoryText, opinionText)
            "(recap window shown)"
        }
    }
    skills {
        skill<String, String>("recap", "Show a session recap — memory + opinion in a Swing window") {
            // Built-in memory_* tools come from `memory(recapBank)` above.
            // Mixed typed + string refs aren't supported by `Skill.tools(...)`,
            // so we use the deprecated string form for the whole list. The
            // 0.3.0 deprecation is intentional for built-ins (see CHANGELOG).
            @Suppress("DEPRECATION")
            tools("show_recap_window", "memory_read", "memory_write", "memory_search")
        }
    }
    onToolUse { name, args, result -> traceTool("recap", name, args, result) }
}

class RecapProvider : AgentProvider {
    override fun build(): Agent<*, *> = buildRecapAgent()
}
