package agents_engine.observability

import agents_engine.core.AgentRuntimeContext
import agents_engine.core.PipelineEvent
import agents_engine.core.agent
import agents_engine.model.TokenUsage
import agents_engine.runtime.events.AgentEvent
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JsonlAuditExporterTest {

    private val fixedClock: Clock = Clock.fixed(Instant.parse("2026-05-23T10:40:00Z"), ZoneOffset.UTC)

    @Test
    fun `agent events export writes deterministic parseable JSONL`() {
        val dir = Files.createTempDirectory("agents-jsonl-audit")
        val auditFile = dir.resolve("audit.jsonl").toFile()
        val a = agent<String, String>("audited") {
            skills {
                skill<String, String>("echo", "echo") { implementedBy { it } }
            }
        }

        val exporters = a.events.export {
            jsonl(file(auditFile.path), clock = fixedClock)
        }
        try {
            assertEquals("hello", a("hello"))
        } finally {
            exporters.forEach { it.close() }
        }

        val lines = Files.readAllLines(auditFile.toPath())
        assertEquals(1, lines.size, "implementedBy invoke should emit one SkillChosen pipeline event")
        val row = parse(lines.single())
        assertEquals(EXPECTED_FIELDS, row.keys)
        assertEquals("audited", row["agentId"])
        assertEquals("echo", row["skillId"])
        assertEquals(null, row["toolId"])
        assertEquals("SkillChosen", row["eventType"])
        assertEquals("2026-05-23T10:40:00Z", row["timestamp"])
        assertTrue((row["requestId"] as String).isNotBlank())
        assertEquals(null, row["sessionId"])
        assertEquals(null, row["manifestHash"])
    }

    @Test
    fun `agent event rows include session context and token provider fields`() {
        val dir = Files.createTempDirectory("agents-jsonl-audit")
        val auditFile = dir.resolve("audit.jsonl")
        val exporter = JsonlAuditExporter(auditFile, clock = fixedClock)
        exporter.write(
            AgentEvent.SkillCompleted(
                agentId = "worker",
                skillName = "summarize",
                tokensUsed = TokenUsage(
                    promptTokens = 10,
                    completionTokens = 5,
                    cachedInputTokens = null,
                    provider = "openai",
                    model = "gpt-test",
                ),
                runtimeContext = AgentRuntimeContext(
                    requestId = "req-1",
                    sessionId = "session-1",
                    manifestHash = "sha256:abc",
                ),
            ),
        )
        exporter.close()

        val row = parse(Files.readAllLines(auditFile).single())
        assertEquals(EXPECTED_FIELDS, row.keys)
        assertEquals("req-1", row["requestId"])
        assertEquals("session-1", row["sessionId"])
        assertEquals("sha256:abc", row["manifestHash"])
        assertEquals("worker", row["agentId"])
        assertEquals("summarize", row["skillId"])
        assertEquals("SkillCompleted", row["eventType"])
        assertEquals("openai", row["provider"])
        assertEquals("gpt-test", row["model"])
    }

    @Test
    fun `tool rows do not serialize arguments or results that may contain secrets`() {
        val dir = Files.createTempDirectory("agents-jsonl-audit")
        val auditFile = dir.resolve("audit.jsonl")
        val exporter = JsonlAuditExporter(auditFile, clock = fixedClock)
        exporter.write(
            PipelineEvent.ToolCalled(
                agentName = "agent",
                timestamp = Instant.EPOCH,
                toolName = "call_api",
                arguments = mapOf("apiKey" to "sk-secret-value"),
                result = "token=secret-value",
                runtimeContext = AgentRuntimeContext(requestId = "req-2"),
            ),
        )
        exporter.close()

        val line = Files.readAllLines(auditFile).single()
        assertFalse(line.contains("sk-secret-value"), "arguments must not be serialized: $line")
        assertFalse(line.contains("token=secret-value"), "result values must not be serialized: $line")
        val row = parse(line)
        assertEquals("call_api", row["toolId"])
        assertEquals("ToolCalled", row["eventType"])
        assertEquals("Unknown", row["toolPolicyRisk"])
        assertEquals(false, row["usedDeclaredCapability"])
    }

    @Test
    fun `multimodal ToolResult writes outputParts with modality plus hash plus size — no bytes`() {
        // #2469 — audit-row support for typed multimodal tool returns.
        val store = agents_engine.content.InMemoryBlobStore()
        val pngBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 2, 3)
        val imgRef = store.put(pngBytes, agents_engine.content.ImageMime.Png.wireMime)

        val dir = Files.createTempDirectory("agents-jsonl-audit-multimodal")
        val auditFile = dir.resolve("audit.jsonl")
        val exporter = JsonlAuditExporter(auditFile, clock = fixedClock)
        exporter.write(
            PipelineEvent.ToolCalled(
                agentName = "agent",
                timestamp = Instant.EPOCH,
                toolName = "screenshot",
                arguments = mapOf("url" to "https://example.com"),
                result = agents_engine.content.ToolResult(
                    agents_engine.content.Content.Text("captured"),
                    agents_engine.content.Content.Image(imgRef, agents_engine.content.ImageMime.Png),
                ),
                runtimeContext = AgentRuntimeContext(requestId = "req-multimodal"),
            ),
        )
        exporter.close()

        val line = Files.readAllLines(auditFile).single()
        // No bytes anywhere — neither the URL arg nor the image bytes
        assertFalse(line.contains("example.com"), "argument values must not be serialized: $line")
        // No PNG magic — image bytes definitely never enter the audit row
        assertFalse(line.contains("0x89"), "image bytes must not be serialized: $line")

        // Substring assertions on the rendered JSON (the test parser doesn't
        // model arrays, and the column contents are stable enough to grep).
        assertTrue("\"outputParts\":[" in line, "outputParts is emitted as an array: $line")
        assertTrue("\"text:inline:8:text/plain\"" in line, "text-part shape in array: $line")
        assertTrue("\"image:${imgRef.hash.take(12)}:${pngBytes.size}:image/png\"" in line,
            "image-part shape modality:hashPrefix:size:mime — $line")
        assertTrue("\"agents_engine.content.ToolResult\"" in line,
            "outputType still names the wrapper type: $line")
    }

    @Test
    fun `denied tool calls are recorded as ToolDenied rows without leaking the reason text`() {
        // #2395 — blocked calls must appear in the audit log. The PII-safe
        // default means only the decision type is written; the reason (which
        // here embeds the offending path) must NOT reach the on-disk row.
        val dir = Files.createTempDirectory("agents-jsonl-audit")
        val auditFile = dir.resolve("audit.jsonl")
        val exporter = JsonlAuditExporter(auditFile, clock = fixedClock)
        exporter.write(
            PipelineEvent.ToolDenied(
                agentName = "agent",
                timestamp = Instant.EPOCH,
                toolName = "writeFile",
                arguments = mapOf("path" to "/etc/passwd"),
                reason = "path '/etc/passwd' outside declared write policy",
                runtimeContext = AgentRuntimeContext(requestId = "req-3"),
            ),
        )
        exporter.close()

        val line = Files.readAllLines(auditFile).single()
        assertFalse(line.contains("/etc/passwd"), "denial reason text must not leak to the audit row: $line")
        val row = parse(line)
        assertEquals("ToolDenied", row["eventType"])
        assertEquals("writeFile", row["toolId"])
        assertEquals("Deny", row["guardrailDecision"])
    }

    @Test
    fun `size rotation keeps appending into a new active file`() {
        val dir = Files.createTempDirectory("agents-jsonl-audit")
        val auditFile = dir.resolve("audit.jsonl")
        val exporter = JsonlAuditExporter(
            auditFile,
            rotation = JsonlRotation.Size(maxBytes = 80),
            clock = fixedClock,
        )

        exporter.write(AgentEvent.SkillStarted("a", "s", AgentRuntimeContext(requestId = "one")))
        exporter.write(AgentEvent.SkillStarted("a", "s", AgentRuntimeContext(requestId = "two")))
        exporter.close()

        assertTrue(Files.exists(auditFile), "active file should exist after rotation")
        assertTrue(Files.exists(dir.resolve("audit.jsonl.1")), "rotated file should exist")
        assertEquals(1, Files.readAllLines(auditFile).size)
        assertEquals(1, Files.readAllLines(dir.resolve("audit.jsonl.1")).size)
    }

    @Test
    fun `write failures never throw and drop oldest buffered line under backpressure`() {
        val dir = Files.createTempDirectory("agents-jsonl-audit")
        val directoryInsteadOfFile = dir.resolve("audit.jsonl")
        Files.createDirectories(directoryInsteadOfFile)
        val logs = mutableListOf<String>()
        val exporter = JsonlAuditExporter(
            directoryInsteadOfFile,
            maxBufferedLines = 1,
            logger = { message, _ -> logs += message },
            clock = fixedClock,
        )

        exporter.write(AgentEvent.SkillStarted("a", "s", AgentRuntimeContext(requestId = "one")))
        exporter.write(AgentEvent.SkillStarted("a", "s", AgentRuntimeContext(requestId = "two")))
        exporter.close()

        assertTrue(logs.any { it.contains("dropped", ignoreCase = true) }, "expected drop log, got: $logs")
    }

    @Suppress("UNCHECKED_CAST")
    private fun parse(line: String): Map<String, Any?> {
        val parser = TestJsonParser(line)
        val value = parser.parseRoot()
        return value as? Map<String, Any?>
            ?: error("not a JSON object: $line")
    }

    private class TestJsonParser(private val text: String) {
        private var index = 0

        fun parseRoot(): Any? {
            val value = parseValue()
            skipWhitespace()
            if (index != text.length) error("unexpected trailing JSON content at $index in $text")
            return value
        }

        fun parseValue(): Any? {
            skipWhitespace()
            return when (peek()) {
                '{' -> parseObject()
                '"' -> parseString()
                'n' -> {
                    expect("null")
                    null
                }
                't' -> {
                    expect("true")
                    true
                }
                'f' -> {
                    expect("false")
                    false
                }
                else -> error("unexpected JSON token at $index in $text")
            }
        }

        private fun parseObject(): Map<String, Any?> {
            expect('{')
            val values = linkedMapOf<String, Any?>()
            skipWhitespace()
            if (peek() == '}') {
                index++
                return values
            }
            while (true) {
                val key = parseString()
                skipWhitespace()
                expect(':')
                values[key] = parseValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> {
                        index++
                        skipWhitespace()
                    }
                    '}' -> {
                        index++
                        return values
                    }
                    else -> error("expected comma or object end at $index in $text")
                }
            }
        }

        private fun parseString(): String {
            expect('"')
            val out = StringBuilder()
            while (index < text.length) {
                when (val ch = text[index++]) {
                    '"' -> return out.toString()
                    '\\' -> out.append(parseEscape())
                    else -> out.append(ch)
                }
            }
            error("unterminated string in $text")
        }

        private fun parseEscape(): Char =
            when (val escaped = text[index++]) {
                '"', '\\', '/' -> escaped
                'b' -> '\b'
                'f' -> '\u000C'
                'n' -> '\n'
                'r' -> '\r'
                't' -> '\t'
                'u' -> {
                    val hex = text.substring(index, index + 4)
                    index += 4
                    hex.toInt(16).toChar()
                }
                else -> error("bad escape \\$escaped in $text")
            }

        private fun skipWhitespace() {
            while (index < text.length && text[index].isWhitespace()) index++
        }

        private fun peek(): Char =
            text.getOrNull(index) ?: error("unexpected end of JSON in $text")

        private fun expect(expected: Char) {
            if (peek() != expected) error("expected $expected at $index in $text")
            index++
        }

        private fun expect(expected: String) {
            if (!text.startsWith(expected, index)) error("expected $expected at $index in $text")
            index += expected.length
        }
    }

    private companion object {
        val EXPECTED_FIELDS: Set<String> = linkedSetOf(
            "requestId",
            "sessionId",
            "manifestHash",
            "agentId",
            "skillId",
            "toolId",
            "eventType",
            "timestamp",
            "inputType",
            "outputType",
            // #2469 — per-part summary for multimodal ToolResult returns.
            // Null on non-multimodal rows; field is always present so
            // schema-pinning consumers see a stable column set.
            "outputParts",
            "budgetState",
            "guardrailDecision",
            "mcpClientId",
            "toolPolicyRisk",
            "usedDeclaredCapability",
            "provider",
            "model",
        )
    }
}
