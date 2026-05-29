package agents_engine.core

import agents_engine.model.LlmMessage
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertNull

/**
 * #2753 — `FileSnapshotStore` must not let an external session id
 * escape the configured directory. Raw `dir.resolve("$key.json")`
 * (pre-#2753) would happily write to `dir/../../etc/poisoned.json`
 * given a hostile key. We now hash session ids to fixed-length hex
 * before forming the filename; the original id stays inside the
 * snapshot body for traceability.
 */
class FileSnapshotStoreSafetyTest {

    private fun snap(sessionId: String) = SessionSnapshot(
        messages = listOf(LlmMessage("user", "hi")),
        turns = 0,
        toolCalls = 0,
        toolCallLimit = 8,
        tokensUsed = null,
        memory = emptyMap(),
        requestId = "r-1",
        sessionId = sessionId,
        manifestHash = null,
    )

    @Test
    fun `round-trip a normal session id works`(@TempDir tmp: Path) {
        val store = FileSnapshotStore(tmp)
        store.save("session-42", snap("session-42"))
        val loaded = store.load("session-42")
        assertNotNull(loaded)
        assertEquals("session-42", loaded.sessionId, "original sessionId preserved in body")
    }

    @Test
    fun `path-traversal session id stays inside the configured directory`(@TempDir tmp: Path) {
        val store = FileSnapshotStore(tmp)
        val hostile = "../../../etc/poisoned"

        store.save(hostile, snap(hostile))

        // Nothing escaped the temp dir
        val outsideMarker = tmp.resolveSibling("etc").resolve("poisoned.json")
        assertTrue(
            Files.notExists(outsideMarker),
            "FileSnapshotStore must not write outside its dir; found $outsideMarker",
        )

        // The file that DID land is inside tmp, and load() still finds it
        val written = Files.list(tmp).use { it.toList() }
            .single { it.toString().endsWith(".json") }
        assertTrue(written.startsWith(tmp), "snapshot file must live inside $tmp; got $written")
        assertEquals(hostile, store.load(hostile)?.sessionId, "hostile id round-trips through hashed filename")
    }

    @Test
    fun `unicode and shell metachars in session id are accepted (no exception)`(@TempDir tmp: Path) {
        val store = FileSnapshotStore(tmp)
        val weird = "foo bar/baz\n*你好🚀"
        store.save(weird, snap(weird))
        assertEquals(weird, store.load(weird)?.sessionId, "weird id round-trips")
    }

    @Test
    fun `delete only removes the entry for the given key`(@TempDir tmp: Path) {
        val store = FileSnapshotStore(tmp)
        store.save("a", snap("a"))
        store.save("b", snap("b"))
        store.delete("a")
        assertNull(store.load("a"))
        assertNotNull(store.load("b"))
    }

    @Test
    fun `filename is stable for the same key across calls (deterministic hash)`(@TempDir tmp: Path) {
        val store = FileSnapshotStore(tmp)
        store.save("session-42", snap("session-42"))
        val first = Files.list(tmp).use { it.toList() }.single { it.toString().endsWith(".json") }
        store.save("session-42", snap("session-42"))
        val second = Files.list(tmp).use { it.toList() }.single { it.toString().endsWith(".json") }
        assertEquals(first, second, "same key → same filename (deterministic hash, not random)")
    }
}
