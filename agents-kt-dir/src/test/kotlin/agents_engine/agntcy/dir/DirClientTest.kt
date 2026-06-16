package agents_engine.agntcy.dir

import agntcy.dir.core.v1.Record
import agntcy.dir.core.v1.RecordMeta
import agntcy.dir.core.v1.RecordRef
import agntcy.dir.store.v1.StoreServiceGrpcKt.StoreServiceCoroutineImplBase
import com.google.protobuf.Empty
import com.google.protobuf.Struct
import io.grpc.Status
import io.grpc.StatusException
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

// #4520 (PRD §12.6) — AGNTCY DIR client. Hermetic: a fake StoreService over an in-process gRPC channel
// (no network, no real DIR daemon). Proves the generated coroutine stubs + the OASF-JSON <-> Struct mapping
// round-trip, that content is addressed by the returned CID, and that delete makes a record unresolvable.
class DirClientTest {

    // Minimal in-memory content-addressed store: CID = a deterministic hash of the record payload.
    private class FakeStore : StoreServiceCoroutineImplBase() {
        private val store = ConcurrentHashMap<String, Struct>()

        override fun push(requests: Flow<Record>): Flow<RecordRef> = flow {
            requests.collect { record ->
                val cid = "cid-" + Integer.toHexString(record.data.hashCode())
                store[cid] = record.data
                emit(RecordRef.newBuilder().setCid(cid).build())
            }
        }

        override fun pull(requests: Flow<RecordRef>): Flow<Record> = flow {
            requests.collect { ref ->
                val data = store[ref.cid] ?: throw StatusException(Status.NOT_FOUND.withDescription(ref.cid))
                emit(Record.newBuilder().setData(data).build())
            }
        }

        override fun lookup(requests: Flow<RecordRef>): Flow<RecordMeta> = flow {
            requests.collect { ref ->
                store[ref.cid]?.let {
                    emit(RecordMeta.newBuilder().setCid(ref.cid).setSchemaVersion("1.0.0").build())
                }
            }
        }

        override suspend fun delete(requests: Flow<RecordRef>): Empty {
            requests.collect { store.remove(it.cid) }
            return Empty.getDefaultInstance()
        }
    }

    private fun startServer(): Pair<DirClient, () -> Unit> {
        val name = InProcessServerBuilder.generateName()
        val server = InProcessServerBuilder.forName(name).directExecutor().addService(FakeStore()).build().start()
        val channel = InProcessChannelBuilder.forName(name).directExecutor().build()
        val client = DirClient.fromChannel(channel)
        return client to { client.close(); server.shutdownNow() }
    }

    private val recordJson =
        """{"name":"catalog","schema_version":"1.0.0","authors":["Ada"],"skills":[{"name":"plan","id":1003}]}"""

    @Test
    fun `push then pull round-trips the OASF record content`() = runTest {
        val (client, stop) = startServer()
        try {
            val cid = client.push(recordJson)
            assertTrue(cid.startsWith("cid-"), cid)
            val pulled = client.pull(cid)
            // Struct is unordered + numbers are doubles; compare re-parsed content, not bytes.
            assertEquals(jsonToStruct(recordJson), jsonToStruct(pulled))
        } finally {
            stop()
        }
    }

    @Test
    fun `integral ids survive the Struct round-trip`() = runTest {
        val (client, stop) = startServer()
        try {
            val pulled = client.pull(client.push(recordJson))
            assertTrue("\"id\":1003" in pulled, "id must stay integral (not 1003.0): $pulled")
        } finally {
            stop()
        }
    }

    @Test
    fun `lookup resolves metadata and delete makes the record unresolvable`() = runTest {
        val (client, stop) = startServer()
        try {
            val cid = client.push(recordJson)
            assertEquals("1.0.0", client.lookup(cid)?.schemaVersion)
            client.delete(cid)
            assertNull(client.lookup(cid))
            assertFailsWith<StatusException> { client.pull(cid) }
        } finally {
            stop()
        }
    }

    @Test
    fun `pushAll returns CIDs in request order`() = runTest {
        val (client, stop) = startServer()
        try {
            val a = """{"name":"a","schema_version":"1.0.0"}"""
            val b = """{"name":"b","schema_version":"1.0.0"}"""
            val cids = client.pushAll(listOf(a, b))
            assertEquals(2, cids.size)
            assertEquals(jsonToStruct(a), jsonToStruct(client.pull(cids[0])))
            assertEquals(jsonToStruct(b), jsonToStruct(client.pull(cids[1])))
        } finally {
            stop()
        }
    }
}
