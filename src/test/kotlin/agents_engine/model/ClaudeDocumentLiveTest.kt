package agents_engine.model

import agents_engine.content.Content
import agents_engine.content.DocMime
import agents_engine.content.InMemoryBlobStore
import agents_engine.core.agent
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertTrue

/**
 * #2470 slice c — live document-input test against Anthropic Claude.
 * The only built-in provider with a native document content block.
 *
 * Cost discipline:
 * - In-memory plain-text "document" of ~50 bytes
 * - claude-haiku-4-5 (cheapest vision/doc tier)
 * - temperature=0, maxTokens=80, single turn
 *
 * The model must extract a secret word from the document and return
 * it. Loose substring match — robust against per-model phrasing.
 *
 * Tagged `live-cloud-api`; `assumeTrue` skips when no key. To run:
 *   ./gradlew test --tests "*ClaudeDocumentLiveTest*"
 */
class ClaudeDocumentLiveTest {

    private val claudeModel = System.getenv("AGENTSKT_TEST_CLAUDE_DOC_MODEL") ?: "claude-haiku-4-5"

    @Tag("live-cloud-api")
    @Test
    fun `Claude reads a plain-text document and extracts the secret word`() {
        val apiKey = loadKey("ANTHROPIC_API_KEY", ".secrets/anthropic-key")
        assumeTrue(apiKey != null, "skipping: no Anthropic key")

        // Tiny inline "document". Plain text → text/plain on the wire.
        val docBytes = "Project codename: PINEAPPLE. Internal use only.".toByteArray()
        val store = InMemoryBlobStore()
        val ref = store.put(docBytes, DocMime.PlainText.wireMime)

        val a = agent<String, String>("doc-reader") {
            model {
                claude(claudeModel)
                this.apiKey = apiKey
                temperature = 0.0
                maxTokens = 80
            }
            blobStore(store)
            skills { skill<String, String>("read", "") { tools() } }
        }

        val reply = runBlocking {
            a.invokeSuspendWithAttachments(
                input = "What is the project codename in this document? Respond with just the word in uppercase.",
                attachments = listOf(Content.Document(ref, DocMime.PlainText)),
            )
        }
        println("[Claude doc] reply → $reply")
        assertTrue("PINEAPPLE" in reply || "pineapple" in reply.lowercase(),
            "Claude($claudeModel) did not extract the codename from the document; got: $reply")
    }

    @Tag("live-cloud-api")
    @Test
    fun `Claude summarises a short markdown document`() {
        val apiKey = loadKey("ANTHROPIC_API_KEY", ".secrets/anthropic-key")
        assumeTrue(apiKey != null, "skipping: no Anthropic key")

        val mdBytes = """
            # Release Notes

            - Added vision input across all four providers (#2470 slice a).
            - Added Content.Image flow through the agent surface (#2470 slice b).
            - Added document input on Anthropic (#2470 slice c).
        """.trimIndent().toByteArray()
        val store = InMemoryBlobStore()
        val ref = store.put(mdBytes, DocMime.Markdown.wireMime)

        val a = agent<String, String>("md-reader") {
            model {
                claude(claudeModel)
                this.apiKey = apiKey
                temperature = 0.0
                maxTokens = 80
            }
            blobStore(store)
            skills { skill<String, String>("read", "") { tools() } }
        }

        val reply = runBlocking {
            a.invokeSuspendWithAttachments(
                input = "How many bullet points does this document list? Respond with just the digit.",
                attachments = listOf(Content.Document(ref, DocMime.Markdown)),
            )
        }
        println("[Claude markdown] reply → $reply")
        assertTrue("3" in reply || "three" in reply.lowercase(),
            "Claude($claudeModel) did not count the bullet points; got: $reply")
    }

    private fun loadKey(envVar: String, secretFile: String): String? {
        val envKey = System.getenv(envVar)
        if (!envKey.isNullOrBlank()) return envKey
        val file = File(secretFile)
        return if (file.exists()) file.readText().trim().ifBlank { null } else null
    }
}
