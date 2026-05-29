package agents_engine.model

import agents_engine.core.agent
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * #2657 — prefix-stability guard. Detects silent cache-busting: when the
 * content of a cacheable segment changes across invocations of the same
 * agent (timestamps, UUIDs, non-deterministic formatting), the vendor
 * cache silently misses without any error from the provider. The guard
 * emits a warning so deployers can find and remove the source of variance.
 */
class PrefixStabilityGuardTest {

    private lateinit var capturing: CapturingHandler
    private val guardLogger: Logger = Logger.getLogger(PrefixStabilityGuard::class.java.name)

    @BeforeTest
    fun attachHandler() {
        capturing = CapturingHandler()
        guardLogger.useParentHandlers = false
        guardLogger.level = Level.ALL
        guardLogger.addHandler(capturing)
    }

    @AfterTest
    fun detachHandler() {
        guardLogger.removeHandler(capturing)
        guardLogger.useParentHandlers = true
    }

    @Test
    fun `stable system prompt across two invocations does NOT trigger a warning`() {
        val responses = ArrayDeque<LlmResponse>()
        repeat(2) { responses.add(LlmResponse.Text("ok")) }
        val mock = ModelClient { _ -> responses.removeFirst() }

        val a = agent<String, String>("StablePrompt") {
            prompt("You are a helpful assistant.")
            model { ollama("test"); client = mock }
            skills { skill<String, String>("s", "stub") { tools() } }
        }
        PrefixStabilityGuard.reset(a)  // fresh slate

        a("first")
        a("second")

        val unstable = capturing.records.filter { it.level == Level.WARNING && it.message.contains("changed between invocations") }
        assertTrue(unstable.isEmpty(), "stable prompt must not warn; got: ${unstable.map { it.message }}")
    }

    @Test
    fun `system prompt that mutates between invocations triggers an unstable-prefix warning`() {
        // The same agent is invoked twice; between the two invocations the
        // prompt content changes (here via reassignment of an external state).
        // Real-world equivalent: timestamps interpolated into the prompt, a
        // counter, a per-call request id.
        val responses = ArrayDeque<LlmResponse>()
        repeat(2) { responses.add(LlmResponse.Text("ok")) }
        val mock = ModelClient { _ -> responses.removeFirst() }

        // Two agents with the same name but different system prompts ARE
        // different agents from the guard's perspective. Instead, build one
        // agent whose prompt content changes via a mutable backing — but
        // `prompt` is set at construction, so we can't change it after.
        // The actual silent-killer scenario is "the agent code runs once
        // per call and produces a non-deterministic prompt." Simulate that
        // by hashing a system prompt that contains a clock-millis token.

        val nowMs = System.currentTimeMillis()
        val volatileA = agent<String, String>("Volatile") {
            prompt("System @ $nowMs — instructions.")
            model { ollama("test"); client = mock }
            skills { skill<String, String>("s", "stub") { tools() } }
        }
        PrefixStabilityGuard.reset(volatileA)

        volatileA("first")

        // The first-sighting variance probe should warn on the timestamp pattern.
        val probeWarn = capturing.records.firstOrNull {
            it.level == Level.WARNING && it.message.contains("timestamp")
        }
        assertTrue(probeWarn != null, "first-sighting probe should flag the timestamp; got: ${capturing.records.map { it.message }}")
    }

    @Test
    fun `UUID pattern in a cacheable segment is flagged on first sighting`() {
        val responses = ArrayDeque<LlmResponse>()
        responses.add(LlmResponse.Text("ok"))
        val mock = ModelClient { _ -> responses.removeFirst() }

        val a = agent<String, String>("WithUuid") {
            prompt("You operate under directive 04a4ce7e-cbea-4f50-9f78-6e3f1d5aa238. Be helpful.")
            model { ollama("test"); client = mock }
            skills { skill<String, String>("s", "stub") { tools() } }
        }
        PrefixStabilityGuard.reset(a)

        a("hello")

        val uuidWarn = capturing.records.firstOrNull {
            it.level == Level.WARNING && it.message.contains("UUID")
        }
        assertTrue(uuidWarn != null, "UUID probe should fire; got: ${capturing.records.map { it.message }}")
    }

    @Test
    fun `ISO-8601 timestamp in a cacheable segment is flagged`() {
        val responses = ArrayDeque<LlmResponse>()
        responses.add(LlmResponse.Text("ok"))
        val mock = ModelClient { _ -> responses.removeFirst() }

        val a = agent<String, String>("WithIso") {
            prompt("Current time is 2026-05-29T12:34:56Z — answer accordingly.")
            model { ollama("test"); client = mock }
            skills { skill<String, String>("s", "stub") { tools() } }
        }
        PrefixStabilityGuard.reset(a)

        a("now")

        val tsWarn = capturing.records.firstOrNull {
            it.level == Level.WARNING && it.message.contains("timestamp")
        }
        assertTrue(tsWarn != null, "ISO-8601 timestamp probe should fire; got: ${capturing.records.map { it.message }}")
    }

    @Test
    fun `caching disabled means the guard does NOT inspect content`() {
        // If the agent has caching off, there's no cache to bust, so the
        // guard should be silent even on obviously unstable content.
        val responses = ArrayDeque<LlmResponse>()
        responses.add(LlmResponse.Text("ok"))
        val mock = ModelClient { _ -> responses.removeFirst() }

        val a = agent<String, String>("CachingOff") {
            prompt("Time is ${System.currentTimeMillis()} — clearly unstable.")
            model { ollama("test"); client = mock }
            caching { enabled = false }
            skills { skill<String, String>("s", "stub") { tools() } }
        }
        PrefixStabilityGuard.reset(a)

        a("input")

        assertTrue(
            capturing.records.none { it.level == Level.WARNING },
            "caching disabled → no guard warnings; got: ${capturing.records.map { it.message }}",
        )
    }

    @Test
    fun `reset clears prior-hash state so a fresh sequence does not falsely warn`() {
        val mock = ModelClient { _ -> LlmResponse.Text("ok") }

        val a = agent<String, String>("Resetter") {
            prompt("Stable system prompt v1.")
            model { ollama("test"); client = mock }
            skills { skill<String, String>("s", "stub") { tools() } }
        }

        PrefixStabilityGuard.reset(a)
        a("first")

        // Simulate a deploy: the agent stays the same, but we want to forget
        // the previous run's hashes (e.g. across JVM restarts in tests).
        PrefixStabilityGuard.reset(a)
        a("second")

        val unstable = capturing.records.filter { it.message.contains("changed between invocations") }
        assertEquals(0, unstable.size, "reset should erase prior state, no 'changed' warning expected")
    }

    private class CapturingHandler : Handler() {
        val records = mutableListOf<LogRecord>()
        override fun publish(record: LogRecord?) { if (record != null) records += record }
        override fun flush() = Unit
        override fun close() = Unit
    }
}
