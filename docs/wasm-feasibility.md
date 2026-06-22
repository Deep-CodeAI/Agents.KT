# Agent → WASM export — feasibility spike (#4548, epic #4547)

**Status: spike / go-no-go. Not a migration commitment.** This is the one committed step of epic #4547
([runtime] Agent → WASM export via Kotlin Multiplatform). It turns the four "walls" into **measured facts**
against the current tree and records a proof-of-concept, so the migration decision rests on numbers, not vibes.

> Scope reminder: embedding a WASM runtime to *sandbox tools* (`WasmSandbox`, #2894) is **closed won't-do** —
> `ProcessSandbox` (Seatbelt/bwrap/firejail) already isolates subprocess tools. The rational WASM play is the
> opposite direction: compile a typed agents.kt **agent** to a WASM module so it runs at the edge, in a
> browser (CopilotKit-style), or in a standalone WASI runtime.

## TL;DR

- **The typed core compiles to `wasmJs`, runs, AND reaches a live LLM today.** A no-reflection slice of the
  agents.kt programming model (`Agent<IN,OUT>`, `Skill`, the `then` operator) compiled to a real `.wasm` and a
  two-stage typed pipeline (`framing then ollama`) executed end-to-end — **in node and in a real headless
  browser** — calling a local Ollama over `fetch` and rendering the model's reply. The *abstractions* are
  portable, and the HTTP wall is crossable on `wasmJs` via `fetch` (demonstrated, not just asserted).
- **The blockers are concentrated, not pervasive.** Of 345 main source files, the WASM-hostile surface clusters
  in two areas: the **model adapters** (HTTP + reflection) and **platform glue** (filesystem/concurrency/process).
  The core type system, composition, and event model are largely clean.
- **One hard external dependency:** Kotlin/Wasm has no full reflection (JetBrains **KT-63410**, OPEN). Every
  `@Generable` schema/description read must go 100% through KSP codegen. The foundation exists (`agents-kt-ksp`
  + `compileOnly` kotlin-reflect since #1705) but the core still has runtime-reflection fallbacks.
- **Recommendation: conditional GO for a `wasmJs` *profile*, not a full port** — see [Recommendation](#recommendation).

## The four walls — measured (current tree)

Counts over `src/main/kotlin` (**345** `.kt` files total), 2026-06-20:

| Wall | Metric | Files | Where it concentrates | Severity |
|---|---|---:|---|---|
| **1. Reflection** | `kotlin.reflect` or `::class` | **48** | `model` 15, `core` 10, `composition/branch` 4 | **High** |
| ↳ hard: `import kotlin.reflect` | | 19 | schema/`@Generable`/construct reads | High (KT-63410) |
| ↳ soft: `::class` only (no reflect import) | | 28 | `::class.simpleName`, sealed dispatch | Low–Med |
| **2. HTTP** | `java.net.http` | **24** | `model` 20 (every adapter), `x402`/`mcp`/`a2a` 1 each | **High** (mechanical) |
| **3a. Filesystem** | `java.nio.file` | 11 | snapshots, blob store, resources | Med |
| **3b. Concurrency** | `java.util.concurrent` | 13 | session channels, executors | Med |
| **3c. Process/Thread** | `ProcessBuilder` / `Thread` | 17 | `ProcessSandbox`, CLI, daemons | **N/A on WASM** (no subprocess) |
| **(tailwind)** | `kotlinx.coroutines` already used | 29 | sessions, streaming | **Portable** (wasmJs target exists) |

Reading the table:

- **Wall 1 (reflection) is the real gate.** Only 19 files touch `kotlin.reflect` proper; the other 28 use
  `::class` in shapes Kotlin/Wasm largely supports (`simpleName`, identity, sealed `when`). The 19 are the
  `@Generable` schema/description/construct path — exactly what `agents-kt-ksp` was built to replace at compile
  time. Closing this wall is *finishing* the KSP migration (#1016–#1018 line), not inventing anything.
- **Wall 2 (HTTP) is broad but mechanical — and demonstrated crossable.** 20 of 24 are model adapters all
  funnelling through one shape (`HttpClient.send`). A single `expect/actual` HTTP seam collapses this to one
  porting task, not 20. The PoC backs this with the host `fetch` directly (the lightest option — a real
  `POST /api/chat` to Ollama from wasm, in node and the browser); Ktor's `wasmJs` engine is the heavier
  production option if multipart/streaming/interceptors are wanted. `x402`/`mcp`/`a2a` each have one HTTP entry.
- **Wall 3 (platform) splits cleanly.** Filesystem/concurrency are portable via `expect/actual` over
  okio + `kotlinx.coroutines` (+ atomicfu). **Process/Thread does not port and should not** — there are no
  subprocess tools on WASM; a `wasmJs` agent is a no-sandbox-tools, no-network-by-default profile by nature.
- **Coroutines are a tailwind.** 29 files already on `kotlinx.coroutines`, whose `wasmJs` target is stable —
  the streaming `AgentSession`/event model carries over.

## Proof of concept

An isolated `kotlin("multiplatform")` probe with a `wasmJs { binaries.executable() }` target compiled a minimal
typed core — `Agent<IN,OUT>`, `Skill`, and the generic compiler-checked `then` operator — plus a `main` that
runs a two-stage typed pipeline, to a real `.wasm` binary. No `kotlin.reflect`, no `java.*`, no HTTP.

```kotlin
val parse = Agent<String, Spec>("parse", Skill("p") { Spec(it.split(",").map(String::trim)) })
val gen   = Agent<Spec, Code>("gen", Skill("g") { Code(it.endpoints.joinToString("\n") { e -> "fun $e() {}" }) })
val out   = (parse then gen)("getUsers, createUser, deleteUser")   // runs inside wasmJs
```

This isolates the result: **the agents.kt programming model itself is WASM-compatible**; the migration cost is
entirely in the adapters (HTTP/reflection) and platform glue, which is what the walls table quantifies. The
probe is intentionally *not* committed to the repo (it would pull the multiplatform plugin into the JVM build);
it is reproducible from the snippet above.

Two probes, both reproducible (Kotlin 2.4.0, Gradle 9.5, `wasmJs` target):

**(a) Typed core only.** `compileProductionExecutableKotlinWasmJs` produced a valid ~**98 KB** `.wasm`
(`\0asm` magic, Binaryen-optimized), and `Agent` + the generic `then` composition ran under node:
`agents.kt typed core on wasmJs -> fun getUsers() {} | fun createUser() {} | ...`.

**(b) Typed agent + live LLM over `fetch`.** A second probe wires the HTTP wall: a `framing then ollama`
pipeline whose `ollama` skill calls a local Ollama (`POST /api/chat`) through the host `fetch` — the proposed
multiplatform HTTP seam — with the JSON build/parse done via `js(...)` interop (no `java.net.http`). Verified:
- **node:** `runAgent` imported from the compiled module → `[wasm->ollama in 0.5s] Hello there, friend.`
- **headless Chrome:** page served over http, wasm instantiated, real **cross-origin** `fetch`
  (`localhost:8080` → `localhost:11434`, CORS allowed by Ollama by default), model reply rendered into the DOM.

So the streaming-capable `kotlinx.coroutines` and the `fetch` HTTP seam both work on `wasmJs` against a real
model — the HTTP wall is a porting task, not an unknown. Reproduce via the `wasm_tmp/` demo (gitignored; see its
`README.md`); not committed because it pulls the multiplatform plugin into the build.

## Recommendation

**Conditional GO — target a `wasmJs` *capability profile*, not a whole-codebase port.**

1. **Finish the reflection wall first (prerequisite, independently valuable).** Drive the 19 `kotlin.reflect`
   files to zero runtime reflection via `agents-kt-ksp`; the 28 `::class`-only files are mostly already fine.
   This pays off on the JVM too (smaller runtime, the `agents-kt-no-reflect-test` contract) and is the gate for
   everything else.
2. **Introduce `expect/actual` seams behind the two concentrated walls** — one HTTP seam (Ktor `wasmJs`/fetch)
   and one filesystem/concurrency seam (okio + coroutines + atomicfu). 20+ adapters collapse to one porting task.
3. **Define the `wasmJs` profile honestly:** no subprocess tools, no `ProcessSandbox`, network only via the host
   `fetch` seam (HITL/CORS-bound). That is a *real, useful* edge/browser agent, not a crippled one — it is the
   natural runtime behind an AG-UI/CopilotKit frontend.
4. **Defer `wasmWasi`** (standalone Wasmtime/WasmEdge) — immature sockets; revisit after `wasmJs` lands.

**Blocking risk:** KT-63410 (no Kotlin/Wasm full reflection) is OPEN and externally owned. The KSP path routes
*around* it rather than waiting on it, so the project is not blocked on JetBrains — but any code that reaches for
runtime reflection on the `wasmJs` profile will fail to compile, so the no-reflection contract must be enforced
(extend `agents-kt-no-reflect-test` to a `wasmJs` compilation smoke test before committing to the profile).

## Tracking

Epic **#4547** (Agent → WASM export, KMP). This spike: **#4548**. Prerequisite line: the `agents-kt-ksp`
reflection-removal work (#1016–#1018). Superseded won't-do: `WasmSandbox` #2894 (embedded-WASM tool sandbox).
