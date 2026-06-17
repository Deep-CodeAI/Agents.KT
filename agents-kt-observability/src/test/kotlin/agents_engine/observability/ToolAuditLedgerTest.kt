package agents_engine.observability

import agents_engine.core.agent
import agents_engine.model.LlmResponse
import agents_engine.model.ModelClient
import agents_engine.model.Tool
import agents_engine.model.ToolCall
import java.nio.file.Files
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * #2886 (epic #2882, Pillar 2) — the `ToolAuditLedger` core: append-only, PII-safe,
 * Merkle-chained, with `verify()` tamper detection. These tests pin the cryptographic
 * heart; the observe-hook auto-wiring is layered on once this is green.
 */
@OptIn(ExperimentalPathApi::class)
class ToolAuditLedgerTest {

    private val dir = createTempDirectory("ledger")
    private val path = dir.resolve("audit.ledger")

    @AfterTest fun cleanup() = dir.deleteRecursively()

    @Test fun `records link into a Merkle chain keyed by sequence`() {
        val ledger = ToolAuditLedger(path)
        val e0 = ledger.record("writeFile", LedgerDecision.APPROVED, callId = "c0")
        val e1 = ledger.record("readSecret", LedgerDecision.DENIED, callId = "c1", denialReason = "outside policy")
        assertEquals(0L, e0.sequence)
        assertEquals(1L, e1.sequence)
        assertEquals(ToolAuditLedger.GENESIS_HASH, e0.prevHash, "first entry chains to genesis")
        assertEquals(e0.entryHash, e1.prevHash, "each entry chains to the previous one's hash")
        assertNotNull(e1.entryHash)
    }

    @Test fun `verify passes on an untampered ledger`() {
        val ledger = ToolAuditLedger(path)
        repeat(3) { ledger.record("tool$it", LedgerDecision.APPROVED, callId = "c$it", result = "result-$it") }
        val v = ToolAuditLedger.verify(path)
        assertTrue(v.ok, "untampered chain must verify: $v")
    }

    @Test fun `empty ledger verifies ok`() {
        Files.createFile(path)
        assertTrue(ToolAuditLedger.verify(path).ok)
    }

    @Test fun `verify detects an edited entry at its sequence`() {
        val ledger = ToolAuditLedger(path)
        repeat(3) { ledger.record("tool$it", LedgerDecision.APPROVED, callId = "c$it") }
        val lines = Files.readAllLines(path)
        lines[1] = lines[1].replace("tool1", "toolX") // tamper the toolName of sequence 1
        Files.write(path, lines)
        val v = ToolAuditLedger.verify(path)
        assertFalse(v.ok, "an edited entry must be detected")
        assertEquals(1L, v.brokenAtSequence)
    }

    @Test fun `verify detects a deleted entry`() {
        val ledger = ToolAuditLedger(path)
        repeat(3) { ledger.record("tool$it", LedgerDecision.APPROVED, callId = "c$it") }
        val lines = Files.readAllLines(path).toMutableList()
        lines.removeAt(1) // drop the middle entry
        Files.write(path, lines)
        assertFalse(ToolAuditLedger.verify(path).ok, "a removed entry breaks the chain")
    }

    @Test fun `verify detects reordering`() {
        val ledger = ToolAuditLedger(path)
        repeat(3) { ledger.record("tool$it", LedgerDecision.APPROVED, callId = "c$it") }
        val lines = Files.readAllLines(path).toMutableList()
        lines.add(1, lines.removeAt(2)) // swap entries 1 and 2
        Files.write(path, lines)
        assertFalse(ToolAuditLedger.verify(path).ok, "reordering breaks the chain")
    }

    @Test fun `ledger is PII-safe — the result is hashed, never stored`() {
        ToolAuditLedger(path).record("tool", LedgerDecision.APPROVED, result = "SUPER_SECRET_VALUE")
        val text = Files.readString(path)
        assertFalse("SUPER_SECRET_VALUE" in text, "the raw result must not appear in the ledger")
        assertTrue("resultHash" in text, "the ledger records the result hash")
    }

    @Test fun `callId and decision are recorded and joinable`() {
        val e = ToolAuditLedger(path).record("syncTicket", LedgerDecision.DENIED, callId = "call-42", denialReason = "no")
        assertEquals("call-42", e.callId)
        assertEquals("DENIED", e.decision)
        val parsed = ToolAuditLedger.read(path).single()
        assertEquals("call-42", parsed.callId)
        assertEquals("syncTicket", parsed.toolName)
    }

    @Test fun `misbehaviour verdicts chain alongside approved rows and the chain still verifies`() {
        val ledger = ToolAuditLedger(path)
        ledger.record("writeFile", LedgerDecision.APPROVED, result = "ok")
        ledger.record("readSecret", LedgerDecision.DENIED, denialReason = "outside policy glob")
        ledger.record("MAX_TOOL_CALLS", LedgerDecision.BUDGET_EXCEEDED, denialReason = "MAX_TOOL_CALLS at 1.0 of limit")
        ledger.record("ConnectException", LedgerDecision.INFRA_ERROR, denialReason = "java.net.ConnectException")
        assertTrue(ToolAuditLedger.verify(path).ok, "a chain mixing approved + misbehaviour rows must verify")
    }

    @Test fun `readMisbehaviour returns every non-approved row and skips approved traffic`() {
        val ledger = ToolAuditLedger(path)
        ledger.record("a", LedgerDecision.APPROVED)
        ledger.record("b", LedgerDecision.DENIED, denialReason = "no")
        ledger.record("c", LedgerDecision.APPROVED)
        ledger.record("TOKENS", LedgerDecision.BUDGET_EXCEEDED)
        ledger.record("IOException", LedgerDecision.INFRA_ERROR)
        val mis = ToolAuditLedger.readMisbehaviour(path)
        assertEquals(listOf("b", "TOKENS", "IOException"), mis.map { it.toolName })
        assertTrue(mis.all { it.isMisbehaviour }, "every returned row is misbehaviour")
    }

    @Test fun `decision drives the derived severity and misbehaviour view`() {
        val ledger = ToolAuditLedger(path)
        val approved = ledger.record("ok", LedgerDecision.APPROVED)
        val denied = ledger.record("blocked", LedgerDecision.DENIED, denialReason = "policy")
        val budget = ledger.record("DURATION", LedgerDecision.BUDGET_EXCEEDED)
        assertFalse(approved.isMisbehaviour)
        assertEquals(LedgerSeverity.INFO, approved.severity)
        assertEquals(LedgerSeverity.CRITICAL, denied.severity, "a guardrail block is the strongest audit signal")
        assertEquals(LedgerSeverity.WARN, budget.severity)
        assertEquals(LedgerDecision.BUDGET_EXCEEDED, budget.decisionType)
    }

    @Test fun `an unknown future verdict reads as misbehaviour and is never silently dropped`() {
        // Forward-compat: a verdict written by a newer version must not crash the reader and,
        // fail-safe, must surface rather than hide — so it counts as misbehaviour at WARN.
        ToolAuditLedger(path).record("x", LedgerDecision.APPROVED)
        val tampered = Files.readAllLines(path).map { it.replace("\"APPROVED\"", "\"QUARANTINED_V2\"") }
        Files.write(path, tampered)
        val entry = ToolAuditLedger.read(path).single()
        assertEquals(null, entry.decisionType, "an unrecognised verdict parses to null, not an exception")
        assertTrue(entry.isMisbehaviour, "unknown verdicts surface as misbehaviour")
        assertEquals(LedgerSeverity.WARN, entry.severity)
    }

    @Test fun `events ledger auto-records an infra error by exception class without leaking the message`() {
        val a = agent<String, String>("erroring") {
            model {
                ollama("llama3")
                client = ModelClient { _ -> throw IllegalStateException("SECRET_IN_MESSAGE") }
            }
            skills { skill<String, String>("s", "stub") { } }
        }
        a.events.ledger(path.toFile())
        runCatching { a("input") } // the error propagates; the ledger observes it on the way out

        val text = Files.readString(path)
        assertFalse("SECRET_IN_MESSAGE" in text, "the exception message (possible PII) must never be stored")
        val infra = ToolAuditLedger.read(path).single { it.decision == "INFRA_ERROR" }
        assertEquals("IllegalStateException", infra.toolName)
        assertTrue(infra.isMisbehaviour)
        assertTrue(ToolAuditLedger.verify(path).ok, "the auto-written misbehaviour row must verify")
    }

    @Test fun `events ledger auto-records an approved tool call and verifies`() {
        val responses = ArrayDeque(
            listOf<LlmResponse>(
                LlmResponse.ToolCalls(listOf(ToolCall(name = "ping", arguments = emptyMap()))),
                LlmResponse.Text("done"),
            ),
        )
        val a = agent<String, String>("ledgered") {
            lateinit var ping: Tool<Map<String, Any?>, Any?>
            model { ollama("llama3"); client = ModelClient { _ -> responses.removeFirst() } }
            tools { ping = tool("ping", "") { _ -> "pong" } }
            skills { skill<String, String>("s", "stub") { tools(ping) } }
        }
        a.events.ledger(path.toFile())
        a("input")

        val entries = ToolAuditLedger.read(path)
        assertTrue(
            entries.any { it.toolName == "ping" && it.decision == "APPROVED" },
            "the approved tool call must be recorded: $entries",
        )
        assertTrue(ToolAuditLedger.verify(path).ok, "the auto-written ledger must verify")
    }
}
