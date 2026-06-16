package agents_engine.agntcy.dir

import agntcy.dir.core.v1.Record
import agntcy.dir.core.v1.RecordRef
import agntcy.dir.routing.v1.PublishRequest
import agntcy.dir.routing.v1.RecordRefs
import agntcy.dir.routing.v1.RoutingServiceGrpcKt.RoutingServiceCoroutineStub
import agntcy.dir.routing.v1.SearchRequest
import agntcy.dir.routing.v1.UnpublishRequest
import agntcy.dir.search.v1.SearchCIDsRequest
import agntcy.dir.search.v1.SearchRecordsRequest
import agntcy.dir.search.v1.SearchServiceGrpcKt.SearchServiceCoroutineStub
import agntcy.dir.store.v1.StoreServiceGrpcKt.StoreServiceCoroutineStub
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.grpc.Metadata
import io.grpc.stub.MetadataUtils
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.flow.singleOrNull
import kotlinx.coroutines.flow.toList
import agntcy.dir.routing.v1.RecordQuery as RouteRecordQuery
import agntcy.dir.routing.v1.RecordQueryType as RouteRecordQueryType
import agntcy.dir.search.v1.RecordQuery as SearchRecordQuery
import agntcy.dir.search.v1.RecordQueryType as SearchRecordQueryType

/**
 * `agents_engine/agntcy/dir/DirClient.kt` — #4520 (PRD §12.6). A typed Kotlin client for the AGNTCY
 * [DIR](https://github.com/agntcy/dir) content-addressed directory, over generated grpc-kotlin coroutine
 * stubs. The directory pillar of the AGNTCY epic (#4517): publish the OASF discovery record
 * (`toOasfRecord`, #4518) and discover records by content or across the network.
 *
 * Three services, one channel:
 * - **StoreService** — content-addressed CRUD: [push]/[pushAll], [pull], [lookup], [delete].
 * - **SearchService** — local content search by OASF facet: [searchRecords], [searchCids].
 * - **RoutingService** — network publish + discovery: [publish]/[unpublish], [routeSearch].
 *
 * The grpc/protobuf dependency graph lives entirely in `:agents-kt-dir` — core never sees it (the
 * `:agents-kt-rag` / `:agents-kt-identity` pattern). The stored record body is opaque JSON (the OASF record,
 * carried as a `google.protobuf.Struct` — see [jsonToStruct]).
 *
 * ```kotlin
 * DirClient.connect("localhost", 8888).use { dir ->
 *     val cid = dir.push(agent.toOasfRecord(version = "1.0.0"))          // store
 *     dir.publish(listOf(cid))                                           // announce to the network
 *     val hits = dir.searchRecords(listOf(DirQuery(DirQueryType.SKILL_NAME, "agent_orchestration/multi_agent_planning")))
 * }
 * ```
 *
 * **Auth.** Plaintext (dev) or TLS via [connect]; an OIDC **bearer** token is attached as an `authorization`
 * header. SPIFFE/mTLS is supported by building your own [ManagedChannel] and passing it to [fromChannel].
 *
 * Scope: StoreService (full), SearchService (full), RoutingService publish/unpublish/search. RoutingService
 * `List` and OCI referrers are follow-ups under epic #4517.
 */
class DirClient private constructor(
    private val channel: ManagedChannel,
    private val store: StoreServiceCoroutineStub,
    private val routing: RoutingServiceCoroutineStub,
    private val search: SearchServiceCoroutineStub,
) : AutoCloseable {

    // --- StoreService: content-addressed CRUD ---

    /** Publish one OASF record; returns its content identifier (CID). */
    suspend fun push(oasfJson: String): String =
        store.push(flowOf(recordOf(oasfJson))).single().cid

    /** Publish several records in one stream; returns the CIDs in request order. */
    suspend fun pushAll(oasfJsons: List<String>): List<String> =
        store.push(oasfJsons.asFlow().map { recordOf(it) }).map { it.cid }.toList()

    /** Pull a record's OASF JSON by CID. */
    suspend fun pull(cid: String): String =
        structToJson(store.pull(flowOf(refOf(cid))).single().data)

    /** Resolve a record's metadata by CID without pulling the payload, or null if absent. */
    suspend fun lookup(cid: String): DirRecordMeta? =
        store.lookup(flowOf(refOf(cid))).singleOrNull()?.let {
            DirRecordMeta(it.cid, it.annotationsMap, it.schemaVersion, it.createdAt)
        }

    /** Delete records by CID. */
    suspend fun delete(vararg cids: String) {
        store.delete(cids.asList().asFlow().map { refOf(it) })
    }

    // --- SearchService: local content search ---

    /** Search the local store by OASF facet; returns the matching records' OASF JSON. */
    suspend fun searchRecords(queries: List<DirQuery>, limit: Int? = null, offset: Int? = null): List<String> {
        val req = SearchRecordsRequest.newBuilder()
            .addAllQueries(queries.map { it.toSearchQuery() })
            .apply { limit?.let { setLimit(it) }; offset?.let { setOffset(it) } }
            .build()
        return search.searchRecords(req).map { structToJson(it.record.data) }.toList()
    }

    /** Search the local store by OASF facet; returns the matching records' CIDs. */
    suspend fun searchCids(queries: List<DirQuery>, limit: Int? = null, offset: Int? = null): List<String> {
        val req = SearchCIDsRequest.newBuilder()
            .addAllQueries(queries.map { it.toSearchQuery() })
            .apply { limit?.let { setLimit(it) }; offset?.let { setOffset(it) } }
            .build()
        return search.searchCIDs(req).map { it.recordCid }.toList()
    }

    // --- RoutingService: network publish + discovery ---

    /** Announce records (by CID) to the directory network so other peers can discover them. */
    suspend fun publish(cids: List<String>) {
        routing.publish(PublishRequest.newBuilder().setRecordRefs(recordRefsOf(cids)).build())
    }

    /** Retract previously published records (by CID) from the network. */
    suspend fun unpublish(cids: List<String>) {
        routing.unpublish(UnpublishRequest.newBuilder().setRecordRefs(recordRefsOf(cids)).build())
    }

    /**
     * Discover records across the directory network. [queries] are the coarse routing facets
     * (skill/locator/domain/module — see [toRouteQuery]); each hit carries its CID, the announcing peer, and a
     * match score. [minMatchScore] filters weak matches; [limit] caps results.
     */
    suspend fun routeSearch(queries: List<DirQuery>, minMatchScore: Int? = null, limit: Int? = null): List<DirRouteMatch> {
        val req = SearchRequest.newBuilder()
            .addAllQueries(queries.map { it.toRouteQuery() })
            .apply { minMatchScore?.let { setMinMatchScore(it) }; limit?.let { setLimit(it) } }
            .build()
        return routing.search(req).map { DirRouteMatch(it.recordRef.cid, it.peer.id, it.matchScore) }.toList()
    }

    override fun close() {
        channel.shutdownNow()
    }

    companion object {
        const val DEFAULT_PORT: Int = 8888

        /**
         * Connect to a DIR daemon at [host]:[port]. [plaintext] true uses an insecure channel (dev /
         * self-hosted `localhost:8888`); false uses TLS. [bearerToken], if set, is sent as
         * `authorization: Bearer …` (OIDC). For SPIFFE/mTLS, build the channel yourself and use [fromChannel].
         */
        fun connect(
            host: String = "localhost",
            port: Int = DEFAULT_PORT,
            plaintext: Boolean = true,
            bearerToken: String? = null,
        ): DirClient {
            val builder = ManagedChannelBuilder.forAddress(host, port)
            if (plaintext) builder.usePlaintext() else builder.useTransportSecurity()
            return fromChannel(builder.build(), bearerToken)
        }

        /**
         * Wrap an existing [channel] (the seam for custom transports — SPIFFE/mTLS, proxies, in-process test
         * channels). [bearerToken], if set, is attached as an `authorization` header to every service.
         */
        fun fromChannel(channel: ManagedChannel, bearerToken: String? = null): DirClient {
            val interceptor = bearerToken?.let {
                val headers = Metadata().apply {
                    put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), "Bearer $it")
                }
                MetadataUtils.newAttachHeadersInterceptor(headers)
            }
            fun store() = StoreServiceCoroutineStub(channel).maybeAuth(interceptor)
            fun routing() = RoutingServiceCoroutineStub(channel).maybeAuth(interceptor)
            fun search() = SearchServiceCoroutineStub(channel).maybeAuth(interceptor)
            return DirClient(channel, store(), routing(), search())
        }

        private fun <T : io.grpc.stub.AbstractStub<T>> T.maybeAuth(i: io.grpc.ClientInterceptor?): T =
            if (i == null) this else withInterceptors(i)

        private fun recordOf(json: String): Record = Record.newBuilder().setData(jsonToStruct(json)).build()

        private fun refOf(cid: String): RecordRef = RecordRef.newBuilder().setCid(cid).build()

        private fun recordRefsOf(cids: List<String>): RecordRefs =
            RecordRefs.newBuilder().addAllRefs(cids.map { refOf(it) }).build()

        private fun DirQuery.toSearchQuery(): SearchRecordQuery =
            SearchRecordQuery.newBuilder()
                .setType(SearchRecordQueryType.valueOf("RECORD_QUERY_TYPE_${type.name}"))
                .setValue(value)
                .build()

        /** Map a [DirQuery] to the coarse routing facet; non-routable facets (NAME, VERSION, …) are rejected. */
        private fun DirQuery.toRouteQuery(): RouteRecordQuery {
            val routeType = when (type) {
                DirQueryType.SKILL_ID, DirQueryType.SKILL_NAME -> RouteRecordQueryType.RECORD_QUERY_TYPE_SKILL
                DirQueryType.LOCATOR -> RouteRecordQueryType.RECORD_QUERY_TYPE_LOCATOR
                DirQueryType.DOMAIN_ID, DirQueryType.DOMAIN_NAME -> RouteRecordQueryType.RECORD_QUERY_TYPE_DOMAIN
                DirQueryType.MODULE_ID, DirQueryType.MODULE_NAME -> RouteRecordQueryType.RECORD_QUERY_TYPE_MODULE
                else -> throw IllegalArgumentException(
                    "DirQueryType.$type is not routable; network routeSearch supports SKILL/LOCATOR/DOMAIN/MODULE facets",
                )
            }
            return RouteRecordQuery.newBuilder().setType(routeType).setValue(value).build()
        }
    }
}
