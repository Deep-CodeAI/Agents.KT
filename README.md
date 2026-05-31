<p align="center">
  <img src="branding/1_logo/PNG/transparent/logo_color_transparent.png" alt="Agents.KT" width="320" />
</p>

<p align="center">
  <strong>The auditable Kotlin agent runtime for regulated teams.</strong><br/>
  <em>Typed boundaries. Least-privilege tools. MCP-native.</em>
</p>

<p align="center">
  <a href="https://github.com/Deep-CodeAI/Agents.KT/actions/workflows/ci.yml"><img src="https://github.com/Deep-CodeAI/Agents.KT/actions/workflows/ci.yml/badge.svg" alt="CI" /></a>
  <a href="https://central.sonatype.com/artifact/ai.deep-code/agents-kt"><img src="https://img.shields.io/maven-central/v/ai.deep-code/agents-kt?color=blue" alt="Maven Central" /></a>
  <a href="https://mvnrepository.com/artifact/ai.deep-code/agents-kt"><img src="https://badges.mvnrepository.com/badge/ai.deep-code/agents-kt/badge.svg?label=MvnRepository" alt="MvnRepository" /></a>
  <a href="https://kotlinlang.org"><img src="https://img.shields.io/badge/Kotlin-2.1-7F52FF?logo=kotlin&logoColor=white" alt="Kotlin" /></a>
  <a href="https://openjdk.org"><img src="https://img.shields.io/badge/JDK-21+-orange" alt="JDK" /></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-MIT-blue.svg" alt="License: MIT" /></a>
</p>

---

Agents.KT is built for teams that need to know exactly what an AI system is allowed to do. Every agent is `Agent<IN, OUT>`: one input type, one output type, one job. Type mismatches and wrong compositions are caught by the compiler where composition is purely type-driven, and structural misuses fail fast at construction time.

The 0.6 line turns those boundaries into audit-ready evidence: deterministic permission manifests, runtime `manifestHash` correlation, JSONL audit export, OTel/LangSmith/Langfuse bridge adapters, before-interceptor policy hooks, declarative tool policy metadata, reasoning/thinking stream, vendor-neutral prompt caching with prefix-stability guard, snapshot/resume with manifest-hash restore guard, and typed audit events for hallucinated tool calls. Agents.KT is the runtime behind [agents-kt.dev](https://agents-kt.dev/).

---

## First 10 Minutes

**Requirements:** JDK 21+, Kotlin 2.x, Gradle

```kotlin
// build.gradle.kts
dependencies {
    implementation("ai.deep-code:agents-kt:0.6.6")
}
```

Or clone and build from source:

```bash
git clone https://github.com/Deep-CodeAI/Agents.KT.git
cd Agents.KT
./gradlew test
# #2807 — static analysis. Baseline freezes existing violations;
# new code is held to the rules in `detekt.yml`.
./gradlew detekt
```

### Building an agent

An **`Agent<IN, OUT>`** has one input type, one output type, one or more **skills**, and an optional **model** for the agentic path. Skills come in two shapes:

- **`implementedBy { input -> output }`** — deterministic Kotlin lambda. No LLM. Fastest and fully testable.
- **`tools(...)`** with a `model { }` block — agentic. The framework runs a multi-turn loop where the LLM picks tools from the skill's allowlist; the runtime refuses anything outside it (authorization, not just prompting).

```kotlin
val coder = agent<Spec, Code>("coder") {
    model { claude("claude-opus-4-7"); apiKey = System.getenv("ANTHROPIC_API_KEY") }
    lateinit var writeFile: Tool<Map<String, Any?>, Any?>
    lateinit var compile:   Tool<Map<String, Any?>, Any?>
    tools {
        writeFile = tool("write_file", "Write a source file") { args -> writeFile(args) }
        compile   = tool("compile",    "Compile the bundle")  { args -> compile(args)   }
    }
    skills {
        skill<Spec, Code>("write-code", "Implement endpoints") { tools(writeFile, compile) }
    }
}
```

When multiple skills can take the same input type, the LLM (or a manual `skillSelection { }`) routes between them.

### Composing agents

Composition is purely type-driven — the compiler enforces that boundaries line up. Five primitives ship today:

| Primitive | Shape | What it does |
|---|---|---|
| `a then b` | `Pipeline<IN, OUT>` | Sequential. `a.OUT` must equal `b.IN`, enforced at compile time. |
| `a / b` | `Parallel<IN, List<OUT>>` | Run both branches concurrently against the same input; collect results. |
| `agent.branch { … }` | `Branch<IN, OUT>` | Route per source-output shape (`onClass<X> then …`, `onElse then …`); sealed sources are exhaustiveness-checked. |
| `teacher wrap student` | `Pipeline<IN, OUT>` | Teacher-student: `teacher.OUT` (a `String`) becomes `student`'s per-call system prompt. |
| `forum { members(…); captain = … }` | `Forum<IN, OUT>` | Council of members with a captain that emits the verdict. |

A single agent instance can only be placed in one composition — wiring it into two spots fails fast at construction. See [`docs/composition.md`](docs/composition.md) for the operator reference and [`docs/comparison.md`](docs/comparison.md) for the release narrative.

### One typed pipeline

```kotlin
val parse = agent<RawText, Specification>("parse") {
    skills {
        skill<RawText, Specification>("parse-spec", "Splits raw text into a structured specification") {
            implementedBy { input -> Specification(input.text.split(",").map { it.trim() }) }
        }
    }
}
val generate = agent<Specification, CodeBundle>("generate") {
    skills {
        skill<Specification, CodeBundle>("gen-code", "Generates stub functions for each endpoint") {
            implementedBy { spec -> CodeBundle(spec.endpoints.joinToString("\n") { "fun $it() {}" }) }
        }
    }
}
val review = agent<CodeBundle, ReviewResult>("review") {
    skills {
        skill<CodeBundle, ReviewResult>("review-code", "Approves code if it is non-empty") {
            implementedBy { code -> ReviewResult(approved = code.source.isNotBlank()) }
        }
    }
}

// Compiler checks every boundary
val pipeline = parse then generate then review
// Pipeline<RawText, ReviewResult>

val result = pipeline(RawText("getUsers, createUser, deleteUser"))
// ReviewResult(approved=true)
```

Testing details — task names, integration test setup, mutation testing, and how to write tests with a stub `ModelClient` — are in [**`docs/testing.md`**](docs/testing.md). Build prerequisites are on the [**Building From Source**](https://github.com/Deep-CodeAI/Agents.KT/wiki/Building-From-Source) wiki page. **macOS contributors:** the Linux sandbox tests (`bwrap`/`firejail`) need a Linux kernel, so they auto-skip on macOS — CI runs them on a native Ubuntu runner; see [docs/testing.md → Linux sandbox tests](docs/testing.md#linux-sandbox-tests-bwrap--firejail).

---

## What Agents.KT Owns

Agents.KT owns the runtime boundary model:

- Typed `Agent<IN, OUT>` contracts and composition operators.
- Per-skill tool authorization and typed tool handles.
- MCP client/server surfaces that share the same tool/skill shape.
- Permission manifests, declarative tool policies, and runtime audit correlation.
- JSONL audit export plus OTel, LangSmith, and Langfuse adapters through `ObservabilityBridge`.
- Local-first JVM execution with Ollama by default and cloud providers when you choose them.

These are the pieces the framework can make deterministic, testable, and reviewable in code. Start with [permission manifests](docs/permission-manifest.md), the [threat model](docs/threat-model.md), the [regulated deployment guide](docs/regulated-deployment.md), and the [comparison page](docs/comparison.md) for the release narrative.

## What Agents.KT Does Not Own

Agents.KT emits evidence and enforces in-runtime boundaries; it does not replace your deployment controls:

- It is not a legal compliance product. It produces compliance-supporting artifacts and audit-ready evidence; your counsel and compliance team still classify the use case.
- It does not yet OS-sandbox arbitrary Kotlin lambdas. A tool's *declared* `ToolPolicy` is now enforced in-JVM for **filesystem-path arguments** (Layer 1, #2890 — a declared write/read glob blocks out-of-policy absolute paths before the executor runs; see [docs/tool-policy-enforcement.md](docs/tool-policy-enforcement.md)), but a lambda can still touch paths it doesn't take as arguments, and `network`/`environment` isolation needs the Layer-2 OS/container sandbox (#1916), which remains a deployer responsibility for now.
- It does not rate-limit public MCP ingress. Use `McpServer` auth/policy plus your gateway.
- It does not ship a universal prompt-injection classifier. Wire your chosen detector through `onBeforeTurn`.
- It does not try to be a vector-store, eval-suite, or hosted orchestration platform. It is the typed JVM runtime boundary underneath those integrations.

---

## Why Agents.KT

Most agent frameworks let you wire anything to anything. Agents.KT says no.

| Problem | Agents.KT answer |
|---------|-----------------|
| God-agents with unlimited responsibilities | `Agent<IN, OUT>` — one type contract, compiler-enforced SRP |
| Runtime type mismatches between agents | `then` requires `A.OUT == B.IN` — compile error otherwise |
| The same agent instance wired into two places | Single-placement rule — `IllegalArgumentException` at construction time |
| LLM doesn't know which skill to use | Manual `skillSelection {}` routing or automatic LLM routing — descriptions sell each skill to the router |
| LLM doesn't know what context to load | `knowledge("key", "description") { }` entries — LLM reads descriptions before deciding to call |
| Flat pipelines only | Composition operators covering sequential, forum, parallel, iterative, and branching patterns |
| LLM output is an untyped string | `@Generable` + `@Guide` — JSON Schema, provider constrained decoding, prompt fragments, lenient deserializer, and `PartiallyGenerated<T>`; KSP-generated metadata avoids runtime reflection when present |
| MCP tools are wrappers, not first-class | `McpClient.tools()` returns first-class `McpTool<*, *>` handles, while `toolSkills()` keeps the prompt-style skill adapter; agents can also be exposed as MCP servers via `McpServer.from(agent)` |
| Permission model is stringly-typed | `grants { tools(writeFile, compile) }` — actual `Tool<*,*>` references, compiler-validated *(planned Phase 2)* |
| No testing story | AgentUnit — deterministic through semantic assertions *(planned)* |
| JVM frameworks require Java installed | Native CLI binary via GraalVM *(planned Phase 2 Priority)* |

---

## What's Shipped

This section is the index — every claim below points to working code in `main`, with the issue number that established it. Topical detail lives in [`docs/`](docs/).

### Implemented today

These APIs work in `main`, are unit-tested, and are exercised by integration tests (`./gradlew test` for default suite, `./gradlew integrationTest` for live-LLM):

- **Typed agents** — `Agent<IN, OUT>` with at least one skill producing `OUT`, validated at construction. See [docs/skills.md](docs/skills.md).
- **Skills with knowledge** — `skill { knowledge("key", "...") { } }`, lazy-loaded per call. See [docs/skills.md#shared-knowledge](docs/skills.md#shared-knowledge).
- **Agentic loop with tool calling** — multi-turn `chat ↔ tools` driven by the model. See [docs/model-and-tools.md](docs/model-and-tools.md).
- **Six model providers** — `model { ollama(...) }` for local/cloud Ollama, `model { claude("claude-opus-4-7"); apiKey = ... }` for Anthropic's Messages API, `model { openai("gpt-4o"); apiKey = ... }` for OpenAI Chat Completions, `model { deepseek("deepseek-v4-flash"); apiKey = ... }` for DeepSeek's OpenAI-compatible API, `model { kimi("moonshot-v1-8k"); apiKey = ... }` for Kimi (Moonshot AI) — long-context (`-32k` / `-128k`) variants via the model string (#2697), and `model { openrouter("anthropic/claude-3.5-sonnet"); apiKey = ...; openRouterHttpReferer = "https://your.app"; openRouterXTitle = "Your App" }` for OpenRouter — the OpenAI-compatible multi-provider aggregator that fronts hundreds of upstream models behind one API, with optional `HTTP-Referer` / `X-Title` attribution headers honored across the catalog (#2701). All six go through one `ModelClient` interface — `LlmMessage` / `LlmResponse` are provider-agnostic, tools/system/role mapping is per-adapter (#1644, #1656).
- **Typed tools via `@Generable`** — `tool<Args, Result>(...)` with reflection-built JSON Schema; `additionalProperties: false`; sealed-discriminator validation (#658, #661, #699).
- **Provider-neutral tool handles** — local typed tool handles and MCP-discovered tools share `Tool<IN, OUT>`; `McpClient.tools()` returns `McpTool<Map<String, Any?>, String>` for grants/manifests/policy work while `toolSkills()` remains available for primary-skill use (#1948).
- **Provider constrained decoding for `@Generable` outputs** — agentic skills returning `@Generable` types pass their JSON Schema to supporting providers automatically: OpenAI `response_format.json_schema`, Ollama `format`, and Anthropic's forced structured-output tool pattern (#1949).
- **Reasoning/thinking stream** — opt-in `model { reasoning(budgetTokens = ..., effort = ...) }` surfaces a model's reasoning as `AgentEvent.Reasoning`, a channel separate from the answer `Token` stream, so a production UI can render live reasoning instead of a spinner. Claude (extended thinking), DeepSeek (`reasoning_content`), and Ollama (`thinking`) emit reasoning text; OpenAI Chat Completions reports `reasoning_effort` + reasoning token counts only (no text). Off by default (#2406). See [docs/streaming.md](docs/streaming.md).
- **Typed tool refs in skill allowlists** — `tool(...)` returns a `Tool<Args, Result>` handle; `skill { tools(writeFile, compile) }` accepts handles, the IDE catches typos (#1015–#1017). The legacy `tools("name")` string form remains for built-in tools and runtime-discovered MCP names but produces a deprecation warning.
- **Declarative tool policies + in-JVM filesystem enforcement** — `tool { policy { risk = ToolRisk.Medium; filesystem { write("/uploads/**") }; network { denyAll() } } }` records expected filesystem/network/environment scope for manifests and audit events, and the declared **filesystem path-arguments are now enforced at runtime by default** (Layer 1, #2890): an out-of-policy absolute path is denied before the executor runs, surfacing via `onToolDenied` / `PipelineEvent.ToolDenied`. Opt-in by declaration; `enforceToolPolicies = false` opts out. OS/container sandboxing of executors (network/env, subprocess isolation) is the separate Layer-2 work (#1916). See [docs/tool-policy-enforcement.md](docs/tool-policy-enforcement.md).
- **Permission manifests** — `agent.permissionManifest()` and `pipeline.permissionManifest()` emit deterministic JSON/YAML capability graphs with agents, skills, tools, memory, MCP, providers, budgets, guardrails, composition structure, masked secrets, and a SHA-256 hash that is attached to runtime events (#1912). See [docs/permission-manifest.md](docs/permission-manifest.md).
- **Per-skill tool authorization** — runtime allowlist; the prompt's "Available tools" listing is descriptive, the security boundary is the runtime check (#630). See [docs/model-and-tools.md#tool-authorization-model](docs/model-and-tools.md#tool-authorization-model).
- **Before interceptors** — `onBeforeSkill`, `onBeforeTurn`, and `onBeforeToolCall` return `Decision` (`Proceed`, `ProceedWith`, `Deny`, `Substitute`) for dynamic policy, prompt filtering, argument mutation, and synthetic results (#1907). See [docs/interceptors.md](docs/interceptors.md).
- **Inline tool-call fallback** — auto-recovery when an Ollama model rejects native `tools` (e.g. `gemma3:4b`) — strips the field, injects inline JSON format prompt, retries (#702, #706). See [docs/model-and-tools.md#inline-tool-call-fallback-ollama-models-without-native-tool-support](docs/model-and-tools.md#inline-tool-call-fallback-ollama-models-without-native-tool-support).
- **Composition operators** — `then`, `/` (parallel), `*` and `forum { }` (multi-agent), `.loop {}`, `.branch {}` on sealed types. See [docs/composition.md](docs/composition.md).
- **Single-placement rule** — each `Agent` instance participates in at most one structure; second placement throws at construction. See [docs/composition.md#single-placement-rule](docs/composition.md#single-placement-rule).
- **Memory bank** — `memory(MemoryBank())` auto-injects `memory_read` / `memory_write` / `memory_search` tools. See [docs/memory.md](docs/memory.md).
- **LLM skill routing** — manual `skillSelection { }` or LLM router with `skillSelectionConfidenceThreshold`; `SkillRoute(name, confidence, rationale)` is structured (#641). See [docs/model-and-tools.md#skill-selection](docs/model-and-tools.md#skill-selection).
- **Tool error recovery** — per-tool `onError`, per-skill default, agent default; built-in `escalate` and `throwException` agents. See [docs/error-recovery.md](docs/error-recovery.md).
- **Budget controls** — `budget { maxTurns; maxToolCalls; maxDuration; perToolTimeout; maxTokens; maxConsecutiveSameTool }` (`perToolTimeout` covers regular and session-aware tools; token counts cumulative across turns when the provider reports usage; `maxConsecutiveSameTool` catches LLM retry loops on a broken tool) (#637, #963, #969, #1903). `onBudgetExceeded { reason, currentLimit -> BudgetDecision.Extend(newLimit) }` raises a cap and continues instead of throwing — a long-running agent can grant itself more tool calls mid-run rather than failing (#2412). `BudgetDecision.Checkpoint` (#2749) is the third variant — pause at the cap, deliver a `SessionSnapshot` via the registered `onTurnCheckpoint` hook, throw a recoverable `BudgetCheckpointException`, and resume later via `agent.invokeSuspendResuming(input, resumeFrom = snapshot)` once the human approves a raise (no history replay).
- **Public snapshot / resume** — `agent.invokeSuspendResuming(input, resumeFrom = null, onTurnCheckpoint = null)` (#2749) is the public seam over the internal `executeAgentic(resumeFrom, onTurnCheckpoint)` primitives from #2416. With defaults it matches `invokeSuspend(input)` byte-for-byte; with `onTurnCheckpoint` set it captures a `SessionSnapshot` at every turn boundary; with `resumeFrom = snapshot` it continues an in-flight invocation without replaying history. On resume the loop honors `max(snapshot.toolCallLimit, agent.budget.maxToolCalls)` so a rebuilt agent with a raised cap actually picks it up.
- **Eval harness** — `DeterministicModelClient(LlmResponse.Text("..."), LlmResponse.ToolCalls(...))` (#2492) scripts model responses for reproducible eval without a live provider; the streaming flow folds into the same Started → ArgsDelta → Finished → End chunk sequence a native streaming provider would emit. Typed assertion DSL `eval<IN, OUT>("name") { input(...); expect { ... }; expectSnapshot(...) }` (#2493) runs against the parsed `OUT` — not regex on the wire. Snapshot mode pins `toLlmInput(output)` JSON for structural diffs; `evalSuite { + case; + case }` bundles cases. Optional `judge("tone", rubric)` (#2494) runs an advisory LLM-as-judge scorer with a typed `@Generable` `JudgeVerdict` — explicitly separate from the deterministic pass/fail contract (judges never gate). See [docs/eval.md](docs/eval.md).
- **Multimodal foundation** — `sealed Content { Text, Image(ref, ImageMime), Audio, Video, Document(ref, DocMime) }` (#2466) with closed mime types per modality (no `String` mime). Content-addressed `ContentRef(hash, sizeBytes, wireMime)` + `BlobStore` interface, `InMemoryBlobStore` / `FileBlobStore` impls — SHA-256 keys match the manifest-hash family, atomic tmp+rename, process-restart-safe, idempotent put (#2467). Tools can return `ToolResult(parts: List<Content>)` for mixed text + image + document outputs; JSONL audit exporter records `outputParts` per-part summary (`<modality>:<hash-prefix>:<size>:<mime>`) with no blob bytes in the audit row (#2469). Stage 1 wires Image + Document end-to-end. One-line file loading via `Files.load(path, store)` — auto-detects modality + mime from extension, returns typed `Content`. See [docs/multimodal.md](docs/multimodal.md).
- **Vision input to models** — `LlmMessage(role = "user", content = "...", images = listOf(ImagePart(base64, ImagePart.WireMime.Png)))` (#2470 slice a) reaches all four built-in adapters: Ollama emits `images: [<b64>...]`, Claude emits `{type:"image", source:{type:"base64",...}}` content blocks, OpenAI emits `{type:"image_url", image_url:{url:"data:..."}}` content blocks, DeepSeek inherits OpenAI (silently ignored on non-vision models). Closed `ImagePart.WireMime { Png, Jpeg, Gif, Webp }` — no `String` mime. Programmatic `VisionFixtures.threeSquaresPng()` / `housePng()` (256×256, `BufferedImage`-rendered, ~5KB) + per-provider live tests (qwen3-vl:8b / Haiku 4.5 / gpt-4o-mini) with cost discipline. See [docs/multimodal.md](docs/multimodal.md#vision-input--talking-to-the-model-2470-slice-a).
- **Typed `Content.Image` at the agent surface** — `agent.invokeWithAttachments("describe", attachments = listOf(Content.Image(ref, ImageMime.Png)))` (#2470 slice b). Inject a `BlobStore` via `blobStore(store)` in the agent DSL; the runtime dereferences each `Content.Image` against the store, base64-encodes once, and attaches `ImagePart` to the first user message. Closed `ImageMime → ImagePart.WireMime` mapping covers all four variants. Misconfiguration errors fast (no `blobStore` configured, missing blob for a ref). Composes with snapshot/resume — refs travel in the snapshot; the same store dereferences on resume. Suspending sibling `invokeSuspendWithAttachments`. Live tests across all three vision providers via the agent surface. See [docs/multimodal.md](docs/multimodal.md#agent-attachments--typed-contentimage-at-the-invoke-surface-2470-slice-b).
- **Prompt caching across providers** — `agent { caching { enabled = true; cacheSystemPrompt = true; cacheToolDefs = true; cacheConversation = Rolling; ttl = 1.hours; cacheable("doc-id") { ... } } }`. Vendor-neutral DSL drives Anthropic's explicit `cache_control` breakpoints (#2658), OpenAI / DeepSeek automatic prefix caching with a stable `prompt_cache_key` routing hint (#2659 / #2661), Ollama / vLLM / SGLang engine-level KV-cache reuse (no-op hints, #2662), and surfaces cache reads + writes + hit-rate on `TokenUsage` (#2663). A prefix-stability guard (#2657) detects silent cache-busters — timestamps, UUIDs, non-deterministic ordering inside cacheable segments — and warns before you pay for a single non-cached run. Off by default; non-breaking. See [docs/caching.md](docs/caching.md).
- **JSONL audit exporter** — `:agents-kt-observability` writes append-only, one-line-per-event audit rows with `requestId`, `sessionId`, `manifestHash`, agent/skill/tool ids, event type, provider, and model; raw arguments/results are omitted by default (#1914). See [docs/observability.md](docs/observability.md).
- **ObservabilityBridge adapters** — `.observe(OtelBridge(tracer))` maps runtime events to OTel spans (#1908), `.observe(LangSmithBridge(apiKey, project))` maps the same events to LangSmith run trees (#1909), and `.observe(LangfuseBridge(publicKey, secretKey))` maps them to Langfuse traces, generations, spans, and events (#1910), while keeping core vendor-free. See [docs/observability.md](docs/observability.md).
- **MCP client** — `mcp { server() }` over HTTP / stdio / TCP; Bearer auth; namespaced tools (`server.tool`). See [docs/mcp.md](docs/mcp.md).
- **MCP server** — `McpServer.from(agent)` exposes an agent as an MCP-conformant HTTP server with explicit `tools/listChanged: false` capability (#619), inbound bearer auth, Host/Origin allowlists, and per-principal tool policy (#1902); `McpStdioServer.from(agent)` serves the same tools/prompts/resources over line-delimited stdio (#2045).
- **`McpRunner` standalone** — picocli-style one-liner main for shipping agents as MCP services over HTTP or `--stdio`.
- **`LiveShow` / `LiveRunner`** — REPL deployment with string-concatenated conversation history. Six factory overloads (Agent, Pipeline, Forum, Parallel, Loop, Branch) for any String-input structure; `--once "<prompt>"` for non-interactive use; built-in `/quit`, `/clear`, `/help` slash commands; user-extensible; JLine-backed cursor movement and in-memory arrow-key history for interactive terminals (#981, #985).
- **`Swarm` + `absorb`** — drop sibling agent JARs into a folder, the captain ServiceLoader-discovers them and absorbs each as a tool with full agent personality preserved (prompt, skills, knowledge, memory). In-JVM, no IPC, no static-typing-across-JARs limitation MCP-stdio would impose (#984).
- **Frozen-after-construction agents** — structural mutators (skills, tools, memory, model, budget, prompt, error handlers, routing) reject post-construction calls (#697, #708).
- **Encapsulated tool/skill maps** — `Agent.toolMap` and `Agent.skills` are read-only `Map` views; mutation only via DSL or framework-internal escape hatches (#659, #667).
- **`LlmProviderException`** — provider-boundary errors (auth, model-not-found, capability mismatch) surface distinctly from output-parse errors (#702).
- **Untrusted tool-output wrapping** — tool results carry an envelope so the model can't impersonate framework messages (#642).
- **`loadResource(path)`** — read agent system prompts (or any other context) from `src/main/resources/...` instead of inline string literals; fail-fast at construction if the path is wrong. `loadResourceOrNull` for the optional case (#980).

### Experimental

APIs that exist in `main` and have tests, but haven't been exercised in production and may evolve based on real-world usage:

- **Forum with `transcriptCaptain`** — captain receives the full `ForumTranscript<IN>` (all participant outputs) instead of only the original input (#639). Useful for synthesis patterns; semantics may sharpen with usage.
- **Branch on sealed hierarchies** — `BranchRoute` sealed type with `onNull` / `onElse` markers and construction-time completeness validation (#640). Stable surface, limited real-world coverage.

## What's Not Shipped

The release is intentionally explicit about what the framework does not enforce yet.

### Security Model

What the framework enforces today:

| Boundary | Enforcement | Established by |
|----------|-------------|----------------|
| Tool authorization | Runtime per-skill allowlist; unknown calls rejected — prompt is descriptive only | #630 |
| Tool policy declarations | `ToolPolicy` captures declared risk and filesystem/network/environment scope for review and audit | #1915 |
| Dynamic policy | `onBefore*` interceptors can deny, mutate, or substitute before skills, turns, and allowed tool calls run | #1907 |
| Tool name typos | Fail-fast at agent construction | #631 |
| Reserved memory names | `memory_read` / `memory_write` / `memory_search` cannot be shadowed by user tools | #659 |
| Agent contract | Skills, tools, memory, model, budget, prompt frozen after `agent { }` returns | #697, #708 |
| Typed args | `additionalProperties: false`; sealed `type` discriminator must match constructed variant | #661, #699 |
| Repaired args | Re-validated through the typed schema before reaching the executor | #658 |
| Tool output trust | Tool results wrapped in untrusted envelope so the model can't forge framework messages | #642 |
| Provider errors | Surface as `LlmProviderException` — never confused with model output | #702 |
| Budget caps | `maxTurns`, `maxToolCalls`, `maxDuration`, `perToolTimeout`, `maxTokens`, `maxConsecutiveSameTool` (`perToolTimeout` covers regular tools via worker interrupt and session-aware tools via coroutine cancellation; token cap cumulative across turns when provider reports usage; `maxConsecutiveSameTool` catches retry loops on a broken tool) | #637, #963, #969, #1903 |

What the framework does **not** enforce — your responsibility:

- **Built-in prompt-injection classifier** — wire your chosen classifier through `onBeforeTurn`; the framework provides the hook, not the detector.
- **Sandboxing of tool executors** — tool code runs in-process with full JVM permissions. `ToolPolicy` declares intended scope for review/audit, but sandbox at the OS / container layer if the tools execute untrusted plans.
- **Resource limits beyond budgets** — no automatic memory, file-descriptor, or network quotas.
- **MCP request rate limits** — `McpServer` authenticates and filters tools, but per-client throttling still belongs in your gateway for now.

### Known Limitations

- **Four LLM providers shipped** — Ollama, Anthropic, OpenAI, and DeepSeek. Google (Gemini) adapter is Phase 2; the injectable `ModelClient` covers test stubs and your own adapters in the meantime.
- **Synchronous agentic loop** — `runBlocking` inside the loop until the suspend refactor lands (#638). Calling agents from existing coroutine scopes works but doesn't propagate cancellation cleanly.
- **No built-in MCP rate limiter** — use `McpServer` auth/policy plus a gateway for throttling. Agent/runtime audit events have a first-party JSONL exporter in `:agents-kt-observability`.
- **Streaming runtime** *(shipped — v0.5.0)*. `agent.session(input): AgentSession<OUT>` exposes `events: Flow<AgentEvent<OUT>>` — bracket events (`SkillStarted` / `SkillCompleted` / `Completed<OUT>` / `Failed`) plus mid-loop `Token` / `Reasoning` / `ToolCallStarted` / `ToolCallArgumentsDelta` / `ToolCallFinished` events as the agentic loop runs. All events carry `requestId`, `sessionId`, and `manifestHash` for audit correlation (#1913). Ollama, Anthropic, OpenAI, and DeepSeek stream at the wire (DeepSeek via the OpenAI-compatible SSE path); live integration tests measure 19 / 2 / 19 chunks for the original three native adapters. `SkillCompleted.tokensUsed` and `Completed.tokensUsed` carry cumulative `TokenUsage` across all turns. The underlying `LlmChunk` sealed type + `ModelClient.chatStream(messages): Flow<LlmChunk>` foundation (#1722) is what custom adapters plug into. See [docs/streaming.md](docs/streaming.md) for the full API + the [v0.5.0 streaming premortem](docs/premortem-0.5.0-streaming.md) for design rationale.
  - *Partial cancellation today.* `Flow` collection cancels promptly, and `perToolTimeout` now applies to both regular and session-aware tool calls. Synchronous skill bodies and blocking HTTP reads still are not fully coroutine-cancellable mid-call; the remaining adapter migration is the `sendAsync`/suspend-refactor track.
  - *Leaf-agent sessions only.* Composition operators (`Pipeline` / `Branch` / `wrap` / `Swarm`) don't yet flow inner events through their own `session(...)` surfaces — known gap, see #1745 follow-ups.
- **No native binary** — JVM-only (≥ JDK 21). GraalVM and `jlink` bundles are Phase 2 priorities.
- **No A2A protocol yet** — agent-to-agent over network (Phase 2 / 3).
- **Inline-tool-call fallback model variance** — small Ollama models (e.g. `gemma3:4b`) reliably emit single tool calls via the inline format but may produce thin final-turn text after multi-step tool sequences. For multi-step reasoning, a tool-native model (`gpt-oss:20b-cloud` and similar) is the better fit.
- **No tool sandboxing** — tool executors run in-process with full JVM privileges. `grants { }` controls *which* tools an agent can call, not *what they can do* once invoked. Sandboxed execution (`ProcessSandbox` / `WasmSandbox` / `DockerSandbox` opt-in backends) is on the Phase 3 roadmap.
- **Text-only I/O today** — `LlmMessage.content: String` carries text. Image input (vision-capable adapters: Anthropic, OpenAI, Ollama, Gemini) and audio input land in Phase 2 alongside an `LlmContent` sealed-block evolution of the message model. Image generation (`ImageModelClient`: DALL-E, Imagen, Stability) and text-to-speech (`TTSModelClient`: OpenAI TTS, ElevenLabs, Google) are Phase 3.

For planned features beyond these limitations, see [docs/roadmap.md](docs/roadmap.md).

---

## Roadmap (highlights)

**Phase 1 — Core DSL** *(in progress)*: typed agents, skills, knowledge, composition operators (`then`, `/`, `*`, `forum`, `.loop`, `.branch`), MCP client + server, agent memory, `loadResource(path)` for prompts from classpath, agentic loop with full budget controls (`maxTurns` / `maxToolCalls` / `maxDuration` / `perToolTimeout` / `maxTokens` / `maxConsecutiveSameTool`), observability hooks (`onSkillChosen`, `onToolUse`, `onKnowledgeUsed`, `onError`, `onBudgetThreshold`, `onBudgetExceeded`, `Agent.observe { }`), runtime audit context (`requestId`, `sessionId`, `manifestHash`), JSONL audit export, declarative tool policy metadata, and before-interceptor policy hooks (`onBeforeSkill`, `onBeforeTurn`, `onBeforeToolCall`).

**Phase 2 — Runtime + Distribution** *(Q2 2026)*: remaining provider (Google), native CLI / jlink, `grants {}` permissions, session model, Flow-based observability, **multimodal input** (image + audio content blocks; vision-capable adapters for Anthropic/OpenAI/Ollama/Gemini), `agent.json` serialization, Gradle plugin. *(Anthropic + OpenAI adapters landed in #1644 / #1656; KSP `@Generable` codegen shipped in v0.4.6; per-adapter native streaming overrides — Anthropic SSE, OpenAI SSE, Ollama NDJSON — shipped in v0.5.0; provider-level constrained decoding for `@Generable` outputs shipped in v0.6.0 via #1949; the provider-neutral `Tool<IN, OUT>` / `McpTool<IN, OUT>` hierarchy shipped in v0.6.0 via #1948.)*

**Phase 3 — Production** *(Q3 2026)*: Layer 2 Structure DSL, all 37 compile-time validations, AgentUnit, A2A protocol, file-based knowledge with RAG, OpenTelemetry, **sandboxed tool execution** (`SandboxedExecutor` with `ProcessSandbox` (Seatbelt / bwrap), `WasmSandbox` (Chicory), `DockerSandbox` backends — opt-in per tool, subprocess-shaped tools only, default executor stays in-process), **generative outputs** (`ImageModelClient` for DALL-E / Imagen / Stability, `TTSModelClient` for OpenAI / ElevenLabs / Google).

**Phase 4 — Ecosystem** *(Q4 2026)*: knowledge packs, NL → DSL generation, Skillify, visual editor, knowledge marketplace.

Full per-feature breakdown in [**docs/roadmap.md**](docs/roadmap.md).

---

## Documentation

Topical guides:

- [**Website**](https://agents-kt.dev/) — distilled product tour: typed contracts, constrained tools, local-first runtime, swarm, install, and docs.
- [**Wiki**](https://github.com/Deep-CodeAI/Agents.KT/wiki) — expanded learning path and operational guides.
- [**Skills**](docs/skills.md) — agent skills, knowledge entries, shared catalogs, the lazy-vs-eager context model.
- [**Model & Tool Calling**](docs/model-and-tools.md) — agentic loop, typed tools via `@Generable`, inline-tool fallback, authorization, skill selection, budget caps.
- [**Provider Capability Matrix**](docs/providers.md) — what every `ModelProvider` supports: modality input, reasoning, caching, tool-choice, constrained decoding, streaming.
- [**MCP Integration**](docs/mcp.md) — `mcp { server() }` client, `McpServer.from(agent)`, `McpRunner` standalone.
- [**MCP Server Hardening**](docs/mcp-server.md) — inbound auth, Host/Origin allowlists, per-client tool policy, and gateway deployment recipes.
- [**Tool Error Recovery**](docs/error-recovery.md) — `onError { invalidArgs / deserializationError / executionError }`, `RepairResult.Fixed/Retry/Escalated/Unrecoverable`, default vs per-tool handlers.
- [**Agent Memory**](docs/memory.md) — `memory(MemoryBank())`, the three auto-injected tools, sharing memory across agents.
- [**Guided Generation**](docs/generation.md) — `@Generable`, `@Guide`, `@LlmDescription`, JSON-Schema generation, lenient deserializer, `PartiallyGenerated<T>`.
- [**Composition Operators**](docs/composition.md) — `then`, `/`, `*`, `forum`, `.loop {}`, `.branch {}`, single-placement rule, type algebra.
- [**InternalsAgent**](docs/internals-agent.md) — query agents-kt internals from your IDE via MCP (Cursor / Claude Desktop).
- [**Threat Model**](docs/threat-model.md) — five deployment scenarios + anti-patterns; self-classify your use case in 5 min.
- [**Production Hardening**](docs/production-hardening.md) — actionable checklist for "before going live."
- [**Regulated Deployment**](docs/regulated-deployment.md) — capability inventory, action log, decision points; EU AI Act mapping.
- [**Observability**](docs/observability.md) — JSONL audit exporter, `ObservabilityBridge`, OTel, LangSmith, and Langfuse adapters.
- [**Permission Manifest**](docs/permission-manifest.md) — deterministic capability graph, CI verification, and runtime `manifestHash` correlation.
- [**Comparison**](docs/comparison.md) — Agents.KT vs LangChain / Semantic Kernel / AutoGen / raw MCP.
- [**Interceptors**](docs/interceptors.md) — `onBefore*` family + `Decision` sealed type for deny/mutate/substitute policy (#1907).
- [**Roadmap**](docs/roadmap.md) — full Phase 1–4 feature plan.

---

## Current Release

`main` is currently `0.6.6` — a maintainability pass on top of 0.6.5: the 10-ticket code-smell epic (#2790) plus a field-reported correctness fix for session cancellation (#2863). The tagline: *0.6.6 keeps the surface stable while shrinking what a future audit can flag.* Boring on features, focused on internal hygiene + one user-visible cancellation bug.

**0.6.6 — Maintainability + cancellation (#2863 + epic #2790).** Session catch now distinguishes `CancellationException` (propagate per structured concurrency — no synthetic `Failed` event) from `TimeoutCancellationException` (real failure — keeps surfacing as `Failed`). Field report from a downstream SSE bridge that rendered cancelled subscriptions as "FlowSubscription was cancelled" failures, clobbering already-streamed output. Plus 10 internal refactors landing today: `Ansi` consolidation + suspending session send (#2806), `ToolRisk` dedup + `BudgetConfig.describeOverrides()` (#2805), `RESERVED_MEMORY_TOOL_NAMES` reuse + named magic constants + `reserveName` guard (#2804), `JsonEscape` consolidation across model/core/generation (#2799), shared `JsonRpc` helper + `McpException` hierarchy (#2796), `HttpModelClientSupport.sendBounded` (#2792), MCP client list/text-block dedup + `makeMcpSkill` factory (#2800), `toLlmInput`/`jsonSerialize` collapse (#2794), primary `(String) -> Any?` overload on `LiveShow.from` + `LiveRunner.serve` (#2801), detekt static analysis with baseline (#2807). Additive only — every 0.6.5 caller compiles and runs unchanged.

**0.6.5 — Request-timeout hotfix (#2850).** Bumped `DEFAULT_REQUEST_TIMEOUT` from 60s → 300s on `ClaudeClient`, `OpenAiClient`, `DeepSeekClient`, and `OllamaClient` so long Sonnet turns, big Ollama generations, and extended-thinking calls don't get aborted mid-stream. Added `requestTimeout: Duration?` and `connectTimeout: Duration?` to `ModelConfig` + `ModelBuilder` — null falls back to the adapter's `DEFAULT_REQUEST_TIMEOUT` / `DEFAULT_CONNECT_TIMEOUT`; non-null overrides on every provider through `defaultClientFor()`. Additive only — every 0.6.4 caller compiles and runs unchanged.

**0.6.4 — Trust patch (#2752).** **Snapshot path traversal closed**: `FileSnapshotStore` now hashes session ids (SHA-256 hex) before forming filenames — a hostile `../../../etc/poisoned` session id stays inside its configured directory (#2753). **Manifest-hash restore guard**: `SessionSnapshot.manifestHash` is enforced; resume fails closed with `SnapshotManifestMismatchException` when the snapshot's manifest disagrees with the current agent's, unless the caller passes `allowManifestMismatch = true` to own the migration semantics (#2754). **Namespaced memory restore**: shared `MemoryBank` is no longer wiped on resume; new `snapshotForAgent` / `restoreForAgent` touch only the resuming agent's slot, so the documented shared-workspace topology stops destroying unrelated agents' state (#2755). **Tool-result JSON escaping**: `wrapUntrustedToolResult` now routes through the central `toJsonString` escaper, fixing missing U+0000–U+001F handling and producing valid JSON for binary / OCR / captured-terminal tool output (#2756). **`PipelineEvent.ToolHallucinated`**: first-class audit event when the model emits a tool name not in the skill's allowlist — distinct from policy-denied or executor errors; observable via `onToolHallucinated { name, args, allowedTools -> }` and through `observe()` (#2757). **`onBudgetExceeded` broadened**: the handler now fires consistently on TURNS / DURATION / TOKENS / CONSECUTIVE_TOOL too, not just TOOL_CALLS; `BudgetDecision.Extend(newLimit)` raises the cap (units: integer count except DURATION = milliseconds) and the loop continues (#2750). Plus docs and release-notes refresh: README dep coordinate, RELEASE_NOTES.md body, provider-count consistency, unknown-tool documentation, MCP server output description.

**0.6.3 — Prompt caching + Koog regression net (#2655).** Vendor-neutral caching DSL: `agent { caching { enabled = true; cacheSystemPrompt = true; cacheToolDefs = true; cacheConversation = Rolling; ttl = 1.hours; cacheable("doc-id") { ... } } }`. Drives Anthropic explicit `cache_control` breakpoints (#2658), OpenAI / DeepSeek automatic prefix caching via stable `prompt_cache_key` (#2659 / #2661), Ollama engine-level KV-cache reuse (#2662), and surfaces cache reads + writes + hit rate on `TokenUsage` (#2663). Prefix-stability guard detects silent cache-busters — timestamps, UUIDs, non-deterministic ordering inside cacheable segments — and warns before the first non-cached run (#2657). Koog issue-set regression suite (#2474) makes unknown tool calls recoverable, fixes MCP `@Generable` output JSON serialization, surfaces enum allowed values, and rebuilds sealed deserialization through type discriminator. `SessionHistory` accessors land for after-action review (#2485).

**0.6.1 — bundled into 0.6.3.** Reasoning/thinking stream (#2406) — `model { reasoning(budgetTokens = 1024, effort = MEDIUM) }` surfaces `AgentEvent.Reasoning` as a channel separate from the answer `Token` stream; Claude / DeepSeek / Ollama emit text, OpenAI Chat Completions reports counts only. `onBudgetExceeded` for TOOL_CALLS (#2412) — handler returns `Stop` (throw) or `Extend(newLimit)` (raise cap and continue). `onToolDenied` (#2395) — typed audit event for policy-denied tool calls. Typed parameter schemas (#2379) — `tool<Args, Result>` builds a `@Generable`-backed JSON Schema instead of inferring from prose. Snapshot/resume foundation (#2416) — internal seam; the public surface for end users lands in #2749 on a feature branch.

**0.6.0 — audit platform on top of the v0.5.0 streaming base.** Permission manifest (#1912): `:agents-kt-manifest` emits deterministic JSON/YAML capability graphs for agents and compositions, masks provider secrets, verifies high-risk widening in CI, and attaches the manifest SHA-256 to runtime audit context. Token usage telemetry, JSONL audit export, OTel / LangSmith / Langfuse bridges. DeepSeek joins Ollama / Anthropic / OpenAI as the fourth built-in `ModelClient`. Declarative tool policy (records risk + filesystem / network / environment scope; enforcement still #1916). Provider constrained decoding for `@Generable` outputs.

**0.5.0 platform underneath.** Streaming runtime (`agent.session(input).events: Flow<AgentEvent<OUT>>`), MCP-as-skills unification (`mcp.toolSkills()` + `promptSkills()` + `resourceSkills()`), `McpServer` HTTP / stdio with bearer auth + Host/Origin allowlists + per-principal tool policy. The 0.4 line (kotlin-reflect compileOnly, KSP `@Generable`, BouncyCastle hardening, wrap operator, original three providers) is included.

Use Maven Central for published artifacts and tags for immutable release points.

## License

[MIT](LICENSE) — Deep-Code.AI
