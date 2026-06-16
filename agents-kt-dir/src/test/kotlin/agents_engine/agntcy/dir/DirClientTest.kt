package agents_engine.agntcy.dir

import agntcy.dir.core.v1.Record
import agntcy.dir.core.v1.RecordMeta
import agntcy.dir.core.v1.RecordRef
import agntcy.dir.routing.v1.Peer
import agntcy.dir.routing.v1.PublishRequest
import agntcy.dir.routing.v1.RoutingServiceGrpcKt.RoutingServiceCoroutineImplBase
import agntcy.dir.routing.v1.SearchRequest
import agntcy.dir.routing.v1.SearchResponse
import agntcy.dir.routing.v1.UnpublishRequest
import agntcy.dir.search.v1.SearchCIDsRequest
import agntcy.dir.search.v1.SearchCIDsResponse
import agntcy.dir.search.v1.SearchRecordsRequest
import agntcy.dir.search.v1.SearchRecordsResponse
import agntcy.dir.search.v1.SearchServiceGrpcKt.SearchServiceCoroutineImplBase
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

// #4520 (PRD §12.6) — AGNTCY DIR client. Hermetic: fake Store/Search/Routing services over an in-process
// gRPC channel (no network, no daemon). Proves the generated coroutine stubs, the OASF-JSON <-> Struct
// mapping, content addressing, and the typed search/routing query mapping.
class DirClientTest {

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

    // Echoes the first query back as a record, so the test can assert the typed query mapping reached the wire.
    private class FakeSearch : SearchServiceCoroutineImplBase() {
        override fun searchRecords(request: SearchRecordsRequest): Flow<SearchRecordsResponse> = flow {
            request.queriesList.firstOrNull()?.let { q ->
                val data = jsonToStruct("""{"matched":"${q.value}","type":"${q.type.name}"}""")
                emit(SearchRecordsResponse.newBuilder().setRecord(Record.newBuilder().setData(data)).build())
            }
        }

        override fun searchCIDs(request: SearchCIDsRequest): Flow<SearchCIDsResponse> = flow {
            request.queriesList.forEach {
                emit(SearchCIDsResponse.newBuilder().setRecordCid("cid-for-${it.value}").build())
            }
        }
    }

    private class FakeRouting : RoutingServiceCoroutineImplBase() {
        private val published = ConcurrentHashMap.newKeySet<String>()

        override suspend fun publish(request: PublishRequest): Empty {
            published.addAll(request.recordRefs.refsList.map { it.cid })
            return Empty.getDefaultInstance()
        }

        override suspend fun unpublish(request: UnpublishRequest): Empty {
            published.removeAll(request.recordRefs.refsList.map { it.cid }.toSet())
            return Empty.getDefaultInstance()
        }

        override fun search(request: SearchRequest): Flow<SearchResponse> = flow {
            published.forEach { cid ->
                emit(
                    SearchResponse.newBuilder()
                        .setRecordRef(RecordRef.newBuilder().setCid(cid))
                        .setPeer(Peer.newBuilder().setId("peer-1"))
                        .setMatchScore(request.queriesCount)
                        .build(),
                )
            }
        }
    }

    private fun startServer(): Pair<DirClient, () -> Unit> {
        val name = InProcessServerBuilder.generateName()
        val server = InProcessServerBuilder.forName(name).directExecutor()
            .addService(FakeStore()).addService(FakeSearch()).addService(FakeRouting())
            .build().start()
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
            assertEquals(jsonToStruct(recordJson), jsonToStruct(client.pull(cid)))
        } finally {
            stop()
        }
    }

    @Test
    fun `integral ids survive the Struct round-trip`() = runTest {
        val (client, stop) = startServer()
        try {
            assertTrue("\"id\":1003" in client.pull(client.push(recordJson)), "id must stay integral")
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
    fun `searchRecords maps the typed query to the wire and returns records`() = runTest {
        val (client, stop) = startServer()
        try {
            val results = client.searchRecords(
                listOf(DirQuery(DirQueryType.SKILL_NAME, "agent_orchestration/multi_agent_planning")),
            )
            val only = results.single()
            assertTrue("agent_orchestration/multi_agent_planning" in only, only)
            assertTrue("RECORD_QUERY_TYPE_SKILL_NAME" in only, only) // DirQueryType -> proto enum mapping
        } finally {
            stop()
        }
    }

    @Test
    fun `searchCids returns a CID per query`() = runTest {
        val (client, stop) = startServer()
        try {
            val cids = client.searchCids(listOf(DirQuery(DirQueryType.DOMAIN_NAME, "legal")), limit = 10)
            assertEquals(listOf("cid-for-legal"), cids)
        } finally {
            stop()
        }
    }

    @Test
    fun `publish then routeSearch finds the record across the network and unpublish removes it`() = runTest {
        val (client, stop) = startServer()
        try {
            client.publish(listOf("cid-abc"))
            val hits = client.routeSearch(listOf(DirQuery(DirQueryType.SKILL_NAME, "plan")))
            val hit = hits.single()
            assertEquals("cid-abc", hit.cid)
            assertEquals("peer-1", hit.peerId)
            assertEquals(1, hit.matchScore)

            client.unpublish(listOf("cid-abc"))
            assertTrue(client.routeSearch(listOf(DirQuery(DirQueryType.SKILL_NAME, "plan"))).isEmpty())
        } finally {
            stop()
        }
    }

    @Test
    fun `routeSearch rejects a non-routable facet`() = runTest {
        val (client, stop) = startServer()
        try {
            assertFailsWith<IllegalArgumentException> {
                client.routeSearch(listOf(DirQuery(DirQueryType.SCHEMA_VERSION, "1.0.0")))
            }
        } finally {
            stop()
        }
    }
}
