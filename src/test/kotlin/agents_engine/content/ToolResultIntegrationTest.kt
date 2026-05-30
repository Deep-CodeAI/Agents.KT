package agents_engine.content

import agents_engine.core.PipelineEvent
import agents_engine.core.agent
import agents_engine.core.observe
import agents_engine.model.LlmMessage
import agents_engine.model.LlmResponse
import agents_engine.model.ModelClient
import agents_engine.model.Tool
import agents_engine.model.ToolCall
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * #2469 — multimodal ToolResult end-to-end. Pins:
 *
 * 1. A tool returning a `ToolResult` works through the agentic loop;
 *    the text-summary placeholder reaches the model on the next turn.
 * 2. Non-text parts surface as `[modality: <mime>]` placeholders in v1.
 *    The provider adapter rendering (#2470) replaces this end-to-end.
 * 3. `ToolResult` requires at least one part — empty list fails fast.
 * 4. The placeholder text encodes hash prefix + size for traceability.
 */
class ToolResultIntegrationTest {

    @Test
    fun `tool returning ToolResult flows through the loop with placeholder text reaching the model`() {
        val store = InMemoryBlobStore()
        val imageBytes = byteArrayOf(1, 2, 3, 4, 5)
        val imageRef = store.put(imageBytes, ImageMime.Png.wireMime)

        val responses = ArrayDeque<LlmResponse>()
        responses.add(LlmResponse.ToolCalls(listOf(ToolCall("screenshot", emptyMap()))))
        responses.add(LlmResponse.Text("got the screenshot"))
        val sawMessages = mutableListOf<List<LlmMessage>>()
        val mock = ModelClient { msgs -> sawMessages += msgs.toList(); responses.removeFirst() }

        val a = agent<String, String>("snap") {
            lateinit var screenshot: Tool<Map<String, Any?>, Any?>
            model { ollama("t"); client = mock }
            tools {
                screenshot = tool("screenshot", "Take a screenshot") { _ ->
                    ToolResult(
                        Content.Text("Captured page."),
                        Content.Image(imageRef, ImageMime.Png),
                    )
                }
            }
            skills { skill<String, String>("s", "") { tools(screenshot) } }
        }

        val out = a("go")
        assertEquals("got the screenshot", out)

        val resumeMsgs = sawMessages[1]
        val toolMsg = resumeMsgs.last { it.role == "tool" }
        assertTrue("Captured page." in toolMsg.content, "text part inlines in tool message")
        assertTrue("[image: image/png]" in toolMsg.content, "image part surfaces as a typed placeholder")
        // Hash prefix appears for traceability (full hash omitted to keep audit rows compact)
        assertTrue(imageRef.hash.take(12) in toolMsg.content, "hash prefix surfaces in placeholder")
    }

    @Test
    fun `empty ToolResult fails fast`() {
        val ex = kotlin.runCatching { ToolResult(emptyList()) }.exceptionOrNull()
        assertNotNull(ex)
        assertTrue(ex is IllegalArgumentException)
    }

    @Test
    fun `PipelineEvent ToolCalled carries the ToolResult as event_result for audit consumers`() {
        val store = InMemoryBlobStore()
        val ref = store.put(byteArrayOf(1, 2, 3), DocMime.Pdf.wireMime)
        val responses = ArrayDeque<LlmResponse>()
        responses.add(LlmResponse.ToolCalls(listOf(ToolCall("read_doc", emptyMap()))))
        responses.add(LlmResponse.Text("done"))
        val mock = ModelClient { _ -> responses.removeFirst() }

        val events = mutableListOf<PipelineEvent>()
        val a = agent<String, String>("doc-reader") {
            lateinit var readDoc: Tool<Map<String, Any?>, Any?>
            model { ollama("t"); client = mock }
            tools {
                readDoc = tool("read_doc", "Read a document") { _ ->
                    ToolResult(
                        Content.Text("Spec summary: 12 pages"),
                        Content.Document(ref, DocMime.Pdf),
                    )
                }
            }
            skills { skill<String, String>("s", "") { tools(readDoc) } }
        }
        a.observe { events += it }
        a("read it")

        val toolEvent = events.filterIsInstance<PipelineEvent.ToolCalled>().single()
        val result = toolEvent.result as ToolResult
        assertEquals(2, result.parts.size, "both parts in the event for the bridge to walk")
        assertTrue(result.parts.any { it is Content.Text })
        assertTrue(result.parts.any { it is Content.Document })
    }
}
