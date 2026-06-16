package agents_engine.agntcy.dir

import agntcy.dir.core.v1.Record
import agntcy.dir.core.v1.RecordRef
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

/**
 * `agents_engine/agntcy/dir/DirClient.kt` — #4520 (PRD §12.6). A typed Kotlin client for the AGNTCY
 * [DIR](https://github.com/agntcy/dir) content-addressed directory `StoreService`, over generated
 * grpc-kotlin coroutine stubs. The directory side of the AGNTCY epic (#4517): publish the OASF discovery
 * record (`toOasfRecord`, #4518) and pull it back by CID.
 *
 * The grpc/protobuf dependency graph lives entirely in the `:agents-kt-dir` feature module — core stays
 * free of it (same pattern as `:agents-kt-rag` / `:agents-kt-identity`). The stored record body is an opaque
 * JSON object (the OASF record, carried as a `google.protobuf.Struct` — see [jsonToStruct]).
 *
 * ```kotlin
 * DirClient.connect("localhost", 8888).use { dir ->
 *     val cid = dir.push(agent.toOasfRecord(version = "1.0.0"))  // publish
 *     val json = dir.pull(cid)                                    // discover by CID
 * }
 * ```
 *
 * **Auth.** Plaintext (dev) or TLS via [connect]; an OIDC **bearer** token is attached as an `authorization`
 * header. SPIFFE/mTLS is supported by building your own [ManagedChannel] (with the SPIFFE transport creds)
 * and passing it to [fromChannel] — the client doesn't bundle a SPIFFE provider.
 *
 * Scope: the four content-addressable record RPCs (`Push`/`Pull`/`Lookup`/`Delete`). RoutingService /
 * SearchService (network publish + discovery) and OCI referrers are follow-ups under epic #4517.
 */
class DirClient private constructor(
    private val channel: ManagedChannel,
    private val stub: StoreServiceCoroutineStub,
) : AutoCloseable {

    /** Publish one OASF record; returns its content identifier (CID). */
    suspend fun push(oasfJson: String): String =
        stub.push(flowOf(recordOf(oasfJson))).single().cid

    /** Publish several records in one stream; returns the CIDs in request order. */
    suspend fun pushAll(oasfJsons: List<String>): List<String> =
        stub.push(oasfJsons.asFlow().map { recordOf(it) }).map { it.cid }.toList()

    /** Pull a record's OASF JSON by CID. */
    suspend fun pull(cid: String): String =
        structToJson(stub.pull(flowOf(refOf(cid))).single().data)

    /** Resolve a record's metadata by CID without pulling the payload, or null if absent. */
    suspend fun lookup(cid: String): DirRecordMeta? =
        stub.lookup(flowOf(refOf(cid))).singleOrNull()?.let {
            DirRecordMeta(it.cid, it.annotationsMap, it.schemaVersion, it.createdAt)
        }

    /** Delete records by CID. */
    suspend fun delete(vararg cids: String) {
        stub.delete(cids.asList().asFlow().map { refOf(it) })
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
         * Wrap an existing [channel] (the seam for custom transports — SPIFFE/mTLS, proxies, in-process
         * test channels). [bearerToken], if set, is attached as an `authorization` header.
         */
        fun fromChannel(channel: ManagedChannel, bearerToken: String? = null): DirClient {
            var stub = StoreServiceCoroutineStub(channel)
            if (bearerToken != null) {
                val headers = Metadata().apply {
                    put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), "Bearer $bearerToken")
                }
                stub = stub.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers))
            }
            return DirClient(channel, stub)
        }

        private fun recordOf(json: String): Record = Record.newBuilder().setData(jsonToStruct(json)).build()

        private fun refOf(cid: String): RecordRef = RecordRef.newBuilder().setCid(cid).build()
    }
}
