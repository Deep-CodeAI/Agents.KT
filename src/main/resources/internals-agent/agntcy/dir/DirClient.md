---
description: Source-file knowledge for the agents-kt-dir module (agents_engine.agntcy.dir) — AGNTCY DIR directory client (#4520, PRD §12.6, epic #4517). DirClient wraps generated grpc-kotlin coroutine stubs for StoreService (Push/Pull/Lookup/Delete) — push an OASF record (toOasfRecord JSON), get a content-addressed CID, pull it back. Protos are vendored trimmed+wire-compatible under src/main/proto (no buf/validate closure); the record body is a google.protobuf.Struct (JSON via JsonFormat). grpc/protobuf/netty live in this feature module, not core. Insecure/TLS/OIDC-bearer auth; SPIFFE via fromChannel(). Call when the IDE LLM reasons about publishing/discovering agents in the AGNTCY directory.
---

# `agents-kt-dir` — AGNTCY DIR directory client (#4520)

The **directory** side of the AGNTCY epic (#4517), beside OASF discovery (`toOasfRecord`/`fromOasfRecord`,
#4518/#4519) and Identity-verify (#4521). DIR is a content-addressed directory: push an OASF record, get a
CID; pull/lookup by CID.

```kotlin
DirClient.connect("localhost", 8888).use { dir ->
    val cid = dir.push(agent.toOasfRecord(version = "1.0.0"))  // publish → CID
    val json = dir.pull(cid)                                    // discover by CID
}
```

## Generated stubs + the toolchain

`src/main/proto/**` is compiled by the `com.google.protobuf` Gradle plugin (protoc + `grpc` Java + `grpckt`
Kotlin codegen) into coroutine stubs (`StoreServiceGrpcKt.StoreServiceCoroutineStub`). The streaming RPCs are
`Flow`-based, so `kotlinx-coroutines-core` is on the compile classpath. The whole grpc/protobuf/netty graph
is confined to this feature module — core never sees it (the `:agents-kt-rag` / `:agents-kt-identity` pattern).

## Vendored protos — trimmed but wire-compatible

The upstream agntcy/dir protos import `buf/validate/validate.proto` + referrer/rules types. We vendor a
**trimmed** subset (`agntcy/dir/core/v1/record.proto`, `store/v1/store_service.proto`) with the same package,
service, RPC, message names, **field numbers and types** — but the `buf.validate` field *options* (compile-time
only; they don't affect the wire) and the unused PushReferrer/etc. RPCs dropped. So the client is on-the-wire
identical to a real DIR server without dragging in the buf/validate proto closure. Re-sync if the wire shape changes.

## Record body = Struct (JSON is the contract)

`Record.data` is a `google.protobuf.Struct`, so DIR stores the OASF record as opaque JSON — no OASF protos
needed (PRD §12.6). `DirStruct.kt` converts via protobuf's canonical `JsonFormat`. Caveats: Struct fields are
unordered and numbers are doubles, so the round trip preserves *content*, not byte order; `JsonFormat` prints
whole numbers without `.0`, so `{"id":1003}` survives as `1003` (tested).

## Auth

`connect(host, port, plaintext, bearerToken)`: plaintext (dev / self-hosted `localhost:8888`) or TLS; an OIDC
**bearer** token is attached as an `authorization` header. **SPIFFE/mTLS** is supported by building your own
`ManagedChannel` with the SPIFFE transport creds and passing it to `fromChannel()` — the module doesn't bundle
a SPIFFE provider. `fromChannel` is also the seam in-process test channels use.

## Scope / follow-ups (epic #4517)

Slice = the four content-addressable record RPCs (`Push`/`Pull`/`Lookup`/`Delete`). RoutingService /
SearchService (network publish + DHT discovery) and OCI referrers are the documented next steps. With this,
the AGNTCY epic's core (OASF export/import + Identity-verify + DIR store) is complete.

## Files

- `DirClient.kt` — the client (channel + coroutine stub + push/pull/lookup/delete + auth).
- `DirStruct.kt` — OASF-JSON ↔ `Struct` (internal, JsonFormat-based).
- `DirRecordMeta.kt` — typed `Lookup` result.
- `src/main/proto/agntcy/dir/{core,store}/v1/*.proto` — vendored trimmed protos.
- Tests: `DirClientTest` — in-process gRPC fake StoreService round-trip (hermetic, no daemon).
