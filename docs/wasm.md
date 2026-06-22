# Agent → WebAssembly (experimental spike, #4547/#4548)

[← Back to README](../README.md)

> **Status: feasibility spike, not a shipped target.** This documents a proof of concept and a go/no-go, not a
> supported `wasmJs` build of the runtime. The full measured-walls report is `docs/wasm-feasibility.md` (lands
> with the [#4548](../../issues/4548) spike PR). (Embedding a WASM runtime to *sandbox tools* — `WasmSandbox`
> #2894 — is closed won't-do; this is the opposite direction: compiling an **agent** to WASM.)

The agents.kt programming model — `Agent<IN, OUT>`, `Skill`, the generic `then` operator — is **not JVM-bound**.
A no-reflection slice compiles to a `.wasm` and runs in a browser and in node, reaching a **local LLM over
`fetch`** — the same logical path a JVM agent takes, minus the JVM.

## The typed core, on wasm

```kotlin
// Compiles to wasmJs with no reflection and no java.* :
class Agent<IN, OUT>(val name: String, private val body: suspend (IN) -> OUT) {
    suspend operator fun invoke(input: IN): OUT = body(input)
}
infix fun <A, B, C> Agent<A, B>.then(next: Agent<B, C>): Agent<A, C> =
    Agent("$name->${next.name}") { next(this(it)) }
```

## The HTTP wall, crossed with `fetch`

On `wasmJs` there is no `java.net.http`; an agent reaches the network through the host `fetch` (present in both
the browser and node). A two-stage typed pipeline calling a local Ollama:

```kotlin
suspend fun runDemoAgent(baseUrl: String, model: String, userText: String): String {
    val framing = Agent<String, String>("framing") { "Answer concisely.\n\nUser: $it" }
    val llm     = Agent<String, String>("ollama")  { ollamaChat(baseUrl, model, it) }
    return (framing then llm)(userText)      // Agent<String,String>, checked at compile time
}

// fetch seam via js(...) interop — POST /api/chat, no java.net.http:
private fun postJson(url: String, body: String): kotlin.js.Promise<kotlin.js.JsString> =
    js("fetch(url,{method:'POST',headers:{'Content-Type':'application/json'},body:body}).then(r=>r.text())")
```

## Verified end-to-end (this machine)

- **node** — `runAgent` imported from the compiled `.wasm`, driven against Ollama `gemma3:4b`:
  `[wasm->ollama in 0.5s] Hello there, friend.`
- **headless Chrome** — page served over http, wasm instantiated, a real **cross-origin** `fetch`
  (`localhost:8080` → `localhost:11434`, CORS allowed by Ollama by default), the model's reply rendered into the
  page.

## Run the demo

A complete, reproducible demo lives in **`wasm_tmp/`** (gitignored — it pulls the Kotlin Multiplatform plugin,
which is kept out of the JVM build). Prereqs: JDK 21+ and a local [Ollama](https://ollama.com)
(`ollama pull gemma3:4b`).

```bash
cd wasm_tmp && ./run.sh        # builds the wasmJs bundle, serves on :8080, opens the browser
```

**It must be served over http** — opening `index.html` via `file://` fails because browsers give ES modules a
`null` origin and block them (the page detects this and tells you to run `./run.sh`). CORS is allowed for
`localhost` by default; if blocked, start Ollama with `OLLAMA_ORIGINS='*' ollama serve`.

Headless / node (no browser), using the Kotlin-bundled node (your system node may be too old for Kotlin/Wasm):

```bash
cd wasm_tmp
./gradlew compileDevelopmentExecutableKotlinWasmJs
NODE=$(find ~/.gradle -path '*nodejs*/bin/node' | head -1)
"$NODE" node-smoke.mjs http://localhost:11434 gemma3:4b "Write a haiku about types."
```

## Recommendation (from the spike)

**Conditional GO** for a `wasmJs` *capability profile* (no subprocess tools; `fetch`-bound network), gated on
finishing the KSP reflection-removal — **not** a whole-codebase port. The blockers concentrate in the model
adapters (HTTP/reflection) and platform glue, not the typed core. Full detail and the measured walls are in the
`docs/wasm-feasibility.md` report from the [#4548](../../issues/4548) spike.
