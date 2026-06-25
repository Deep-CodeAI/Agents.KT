package agents_engine.web

import org.junit.jupiter.api.assertThrows
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WebFetchToolTest {

    @Test
    fun `tool construction requires a bounded url surface`() {
        val ex = assertThrows<IllegalArgumentException> {
            webFetchTool(backend = staticBackend(okResponse()))
        }

        assertTrue("allowed host or fixed URL" in ex.message.orEmpty(), ex.message.orEmpty())
    }

    @Test
    fun `fixed url may be fetched even when it is outside the host allowlist`() {
        var seen: WebRequest? = null
        val tool = webFetchTool(
            backend = staticBackend {
                seen = it
                okResponse(finalUrl = it.url)
            },
            options = webFetchOptions {
                allowHost("docs.example.com")
                fixedUrl("https://one-off.example.net/release-notes")
            },
        )

        val out = tool.executor(emptyMap())

        assertTrue(out is WebContent.Page, "expected page, got: $out")
        assertEquals("https://one-off.example.net/release-notes", seen?.url)
        assertTrue(tool.untrustedOutput, "web content must be marked untrusted")
        assertNotNull(tool.policy, "web_fetch must declare network capability for manifest review")
    }

    @Test
    fun `off allowlist dynamic urls are blocked before the backend runs`() {
        var called = false
        val tool = webFetchTool(
            backend = staticBackend {
                called = true
                okResponse(finalUrl = it.url)
            },
            options = webFetchOptions {
                allowHost("docs.example.com")
            },
        )

        val out = tool.executor(mapOf("url" to "https://evil.example/phish"))

        assertEquals(WebContent.Blocked(BlockReason.OFF_ALLOWLIST, "https://evil.example/phish"), out)
        assertTrue(!called, "backend must not run for off-allowlist URL")
    }

    @Test
    fun `robots size and content type prohibitions render blocked content`() {
        assertEquals(
            WebContent.Blocked(BlockReason.ROBOTS_TXT, "https://docs.example.com/private"),
            fetchWith(okResponse(finalUrl = "https://docs.example.com/private", robotsAllowed = false)),
        )
        assertEquals(
            WebContent.Blocked(BlockReason.SIZE_LIMIT, "https://docs.example.com/huge"),
            fetchWith(okResponse(finalUrl = "https://docs.example.com/huge", body = "123456")),
        )
        assertEquals(
            WebContent.Blocked(BlockReason.CONTENT_TYPE, "https://docs.example.com/file.pdf"),
            fetchWith(okResponse(finalUrl = "https://docs.example.com/file.pdf", contentType = "application/pdf")),
        )
    }

    @Test
    fun `screenshot requests require a backend capability at construction time`() {
        val ex = assertThrows<IllegalArgumentException> {
            webFetchTool(
                backend = staticBackend(okResponse()),
                options = webFetchOptions {
                    allowHost("docs.example.com")
                    captureScreenshot()
                },
            )
        }

        assertTrue("screenshot" in ex.message.orEmpty(), ex.message.orEmpty())
        assertTrue("Static" in ex.message.orEmpty(), ex.message.orEmpty())
    }

    @Test
    fun `screenshot capable backends receive screenshot requests`() {
        var seen: WebRequest? = null
        val backend = object : RenderBackend {
            override val name: String = "Cdp"
            override val capabilities: Set<WebCapability> = setOf(WebCapability.SCREENSHOT)

            override fun fetch(request: WebRequest): WebResponse {
                seen = request
                return okResponse(finalUrl = request.url, screenshot = WebScreenshot("image/png", byteArrayOf(1, 2, 3)))
            }
        }
        val tool = webFetchTool(
            backend = backend,
            options = webFetchOptions {
                allowHost("docs.example.com")
                captureScreenshot()
            },
        )

        val out = tool.executor(mapOf("url" to "https://docs.example.com/index.html"))

        assertTrue(out is WebContent.Page, "expected page, got: $out")
        assertTrue(seen?.captureScreenshot == true, "backend request must ask for screenshot")
        assertEquals("image/png", out.screenshot?.mimeType)
    }

    @Test
    fun `impossible prohibition config fails to build`() {
        val ex = assertThrows<IllegalArgumentException> {
            webFetchTool(
                backend = staticBackend(okResponse()),
                options = webFetchOptions {
                    allowHost("docs.example.com")
                    maxBytes = 0
                },
            )
        }

        assertTrue("maxBytes" in ex.message.orEmpty(), ex.message.orEmpty())
    }

    private fun fetchWith(response: WebResponse): WebContent {
        val tool = webFetchTool(
            backend = staticBackend(response),
            options = webFetchOptions {
                allowHost("docs.example.com")
                maxBytes = 5
                allowContentTypes("text/html")
            },
        )
        return tool.executor(mapOf("url" to response.finalUrl)) as WebContent
    }

    private fun staticBackend(response: WebResponse): StaticRenderBackend =
        staticBackend { response }

    private fun okResponse(
        finalUrl: String = "https://docs.example.com/index.html",
        contentType: String = "text/html; charset=utf-8",
        body: String = "ok",
        robotsAllowed: Boolean = true,
        screenshot: WebScreenshot? = null,
    ): WebResponse = WebResponse(
        finalUrl = finalUrl,
        contentType = contentType,
        body = body,
        robotsAllowed = robotsAllowed,
        screenshot = screenshot,
    )
}
