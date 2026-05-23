# Agents.KT vs Other Agent Frameworks

A side-by-side for teams choosing a framework. Written with the constraint of being honest about what each ecosystem is good at — these tools largely complement each other; the right pick depends on your stack, constraints, and what you're optimizing for.

## TL;DR

| | Agents.KT | LangChain | Semantic Kernel | AutoGen | Raw MCP |
|---|---|---|---|---|---|
| **Language** | Kotlin (JVM) | Python (+ JS port) | C# (+ Python, Java) | Python | Any |
| **Typing** | Compile-time `Agent<IN, OUT>` boundaries | Runtime `Any` | Runtime + nominal interfaces | Runtime | Wire-level JSON |
| **Composition** | DSL operators (`then`, `/`, `*`, `wrap`, `branch`, `loop`) — checked at compile | LCEL `|` operator (runtime types) | "Planners" + "Plugins" | Multi-agent conversation graph | None (you build it) |
| **Tool surface** | Typed `Tool<IN, OUT>` + MCP client/server first-class | LangChain Tools, MCP via adapter | Plugins (semantic + native functions) | Functions + tool-use messages | MCP-native |
| **Runtime model** | In-JVM library + MCP server + standalone JAR | In-process Python | In-process .NET / Python | In-process Python | Whatever transport you pick |
| **Local-first** | Yes — Ollama default, no API key required | Yes (via Ollama integration) | Yes (via various local connectors) | Yes (via various) | Yes — transport-agnostic |
| **Deployment shape** | Library / hosted MCP server / autonomous JAR — one DSL, three modes | Library | Library | Library | Wire protocol |
| **License** | MIT | MIT | MIT | CC-BY-4.0 (Microsoft Research) | MIT |
| **Maturity (early 2026)** | 0.5.0 — production-usable for narrow scopes; APIs still moving | 0.3.x — mature, large ecosystem | 1.x — stable, large enterprise adoption | 0.4.x — research project graduating | Spec 2025-03-26, multiple SDKs |

## Where Agents.KT wins

**Compile-time type contracts.** Every composition boundary is `compiler-checked`. `parseAgent then planAgent then solveAgent` fails at compile if the output types don't chain. LangChain's `prompt | model | parser` chains the same way at the API level but is checked at runtime via duck-typed Python. AutoGen and SK do not check type contracts between agents.

**Pure JVM.** No Python sidecar, no `subprocess.run("python")`, no bundling a Python wheel inside your Spring app. Kotlin idioms, Gradle build, single-deploy JAR. Matters when your org's existing AI work is in Python ML pipelines but the agent layer needs to live inside the existing Kotlin/Java service.

**MCP as a first-class native shape.** `mcp.toolSkills()` / `promptSkills()` / `resourceSkills()` turn every MCP capability into a `Skill` consumable in the agent DSL. `McpServer.from(agent) { expose(...) }` exposes an agent as an MCP server in one line. The InternalsAgent (see `docs/internals-agent.md`) is the dogfooding example: the framework documents itself via its own MCP server.

**Single source for three deployment modes.** Same agent definition runs as (a) an in-process library function, (b) a hosted MCP HTTP server, (c) an autonomous JAR with picocli-shaped `--port`/`--expose` flags. The progression matches how agents earn independence; the only thing changing is the wiring around the agent, not the agent itself.

**Race-safe by construction.** Single-placement rule (an agent instance may participate in at most one structure) caught at construction. Freeze-after-construction prevents drift via held references. `wrap`'s `effectivePrompt` parameter avoids the race of mutating shared agent state in concurrent pipelines.

## Where Agents.KT loses

**Ecosystem.** LangChain has 700+ integrations (vector stores, retrievers, embedders, agents, callbacks). Agents.KT has 4 LLM providers (Ollama, Anthropic, OpenAI, DeepSeek) and you write the rest. If your job is "wire up 12 SaaS APIs into a prompt pipeline by Friday," LangChain is the right tool, not this one.

**Python AI/ML interop.** If your team already has Python notebooks for embedding generation, fine-tuning, eval harnesses — running an Agents.KT layer next to them is a context switch. SK's Python flavor or LangChain stay in the same language.

**Multi-agent research surfaces.** AutoGen's strength is the conversation graph between agents — `GroupChat`, `ConversableAgent` with custom turn-taking, complex role-play patterns. Agents.KT's `Forum` operator is the equivalent shape but with fewer pre-built conversation patterns. If you're doing research-style multi-agent debate with 5+ heterogeneous agents and need fine-grained turn control, AutoGen has more out-of-the-box.

**Maturity.** v0.5.0 is the streaming-runtime release; v0.6.0 ships per-file IDE-skills. APIs are stable enough to build on (we don't break things gratuitously, and breakage gets a CHANGELOG entry + migration note) but pre-1.0 reservations are real. LangChain has lived through more breaking-change cycles and has scar tissue from them.

**Vector stores / retrievers / embedders.** Not first-class today. Implement via the `Tool<IN, OUT>` interface or wrap a Java client library (Qdrant, Pinecone, pgvector). LangChain has these as native types with retry / chunking / metadata baked in.

## By Dimension

### Typing

| Framework | What "typed" means |
|---|---|
| **Agents.KT** | `Agent<IN, OUT>` is a generic interface. `agentA then agentB` requires `agentA.OUT == agentB.IN` and the compiler enforces it. `@Generable` data classes generate JSON Schema for LLM structured-output. Internals use `Any?` for the wire (tool args are `Map<String, Any?>`); the typed shell sits over an untyped core. |
| **LangChain** | Pydantic models for structured outputs and tool schemas — runtime validation. Chain compositions are Python `|` operator with duck-typed args; runtime errors when types don't match. |
| **Semantic Kernel** | Nominal interfaces (`IPlugin`, `ISKFunction`). Plugin functions have typed parameters via attributes. Composition is mediated by a planner — types between plugins are inferred / coerced. |
| **AutoGen** | Untyped. Agents pass messages (strings + structured payloads) via the conversation API. |
| **Raw MCP** | Wire-typed via JSON Schema in `tools/list` results. Your language's type system either reads those schemas or doesn't. |

**Pick Agents.KT if:** "the compiler told me before I shipped" matters more than "the framework integrates X out-of-the-box."

### Composition

| Framework | How agents compose |
|---|---|
| **Agents.KT** | Six operators: `then` (sequential), `/` (parallel fan-out), `*` (forum), `wrap` (prompt override), `.branch {}` (typed routing), `.loop {}` (feedback). Single-placement rule = each agent in at most one structure. |
| **LangChain** | LCEL: `prompt | model | parser` is the canonical chain. RunnableLambda, RunnableMap, RunnableParallel for forks. Composition is functional but types are runtime-resolved. |
| **Semantic Kernel** | Planners pick a sequence of plugins to invoke. Manual orchestration via `kernel.InvokeAsync`. Less of a DSL, more an SDK. |
| **AutoGen** | Conversation graph between agents. `GroupChat` manages turn-taking; you write the agent personas + rules. |
| **Raw MCP** | Not applicable — MCP is a tool-call wire protocol, not a composition framework. Your runtime decides how to use the tool catalog. |

### Tool surface

| Framework | What tools look like |
|---|---|
| **Agents.KT** | `tool<Args, Result>("name") { args -> ... }` returns a `Tool<Args, Result>` handle. `skill.tools(addTool, multiplyTool)` is compile-time-checked (typed `Tool` refs, not strings). MCP servers are reachable via `mcp { server("foo") { url = ... } }`. |
| **LangChain** | `@tool` decorator on a Python function, or subclass `BaseTool`. Args via Pydantic. |
| **Semantic Kernel** | `[KernelFunction]` attribute on a method. Parameters via `[KernelFunctionParameter]`. |
| **AutoGen** | Function registered with `register_function(...)` on an agent. OpenAI function-call shape. |
| **Raw MCP** | `tools/list` returns descriptors with JSON-Schema input. Your client wraps them. |

### Deployment

| Framework | Deploy shapes |
|---|---|
| **Agents.KT** | Library import / hosted via `McpServer.from(agent)` / autonomous via `McpRunner.serve(agent, args)`. Future: GraalVM native binary (Phase 2), jlink runtime bundle. |
| **LangChain** | Library import. Servers via LangServe (FastAPI-shaped) or your own glue. |
| **Semantic Kernel** | Library import. Hosting via standard ASP.NET (C#) or whatever your Python web framework is. |
| **AutoGen** | Library import. Hosting via your own glue. |
| **Raw MCP** | Whatever transport you pick (HTTP / stdio / TCP). The spec covers wire format only. |

### Local-first

All four mature frameworks support local LLMs (Ollama, llama.cpp, vLLM) via adapter modules. Agents.KT's default is Ollama with no API key required — you can `./gradlew runInternalsAgent` and have a functioning MCP server in one command. LangChain and SK assume cloud-default but degrade gracefully. AutoGen does too.

### MCP support

| Framework | MCP integration depth |
|---|---|
| **Agents.KT** | First-class. Client (`mcp { server() }`), server (`McpServer.from()`), capability-as-skill shortcuts (`toolSkills()` / `promptSkills()` / `resourceSkills()`), standalone runner (`McpRunner`). 2025-03-26 spec conformance. |
| **LangChain** | Adapter modules (community-maintained). Not a first-class concept; bolted-on. |
| **Semantic Kernel** | MCP plugin in preview. |
| **AutoGen** | MCP client support via community modules. |
| **Raw MCP** | This IS MCP. Use it as the lingua franca; pick the framework on the consumer side based on the language / typing preference. |

### Observability

| Framework | Hooks |
|---|---|
| **Agents.KT** | `onSkillChosen`, `onToolUse`, `onKnowledgeUsed`, `onError`, `onBudgetThreshold`, plus the unified `Agent.observe { event -> }` sealed-event view. Streaming session events via `agent.session(input).events: Flow<AgentEvent<OUT>>`. OpenTelemetry adapter via `:agents-kt-otel` (#1908) and LangSmith run-tree adapter via `:agents-kt-langsmith` (#1909). |
| **LangChain** | `Callbacks` interface, LangSmith integration as the canonical observability story. |
| **Semantic Kernel** | Built-in OpenTelemetry, custom kernel hooks. |
| **AutoGen** | Conversation history is the observation surface. Custom callbacks via the agent API. |
| **Raw MCP** | None at the protocol level. |

### Budget controls

| Framework | What you can cap |
|---|---|
| **Agents.KT** | `maxTurns`, `maxToolCalls`, `maxDuration`, `perToolTimeout`, `maxTokens`, `maxConsecutiveSameTool` — pre-cap warnings via `onBudgetThreshold`. All caps surface as `BudgetExceededException` with a `BudgetReason`. |
| **LangChain** | `max_iterations` on agent executors. Per-tool timeouts via tool implementation. |
| **Semantic Kernel** | Planner step limits; per-function timeout via underlying invocation. |
| **AutoGen** | `max_consecutive_auto_reply` on agents. |
| **Raw MCP** | None — that's the runtime's job. |

## Choosing

A few shortcuts that point at one framework over the others:

- **"Our backend is Spring Boot / Ktor / Quarkus."** → Agents.KT. Single-language stack matters at deploy time.
- **"We need 50 vector-store / retriever / embedder integrations next quarter."** → LangChain. Ecosystem wins.
- **"We're a .NET shop with Azure OpenAI."** → Semantic Kernel. Stays in-stack.
- **"We're researching multi-agent conversation dynamics."** → AutoGen. Built for the question.
- **"We just need to expose tools to Claude / Cursor / ChatGPT."** → Raw MCP. Lowest layer; pick the framework that consumes it.
- **"We want the compiler to catch boundary mistakes before they hit prod."** → Agents.KT.
- **"We want to ship one JAR to k8s with no Python."** → Agents.KT.
- **"We want a curated PromptTemplate library and battle-tested chains for the common patterns."** → LangChain.

## What this comparison is NOT

- A benchmark. Performance comparisons across these tools mostly measure "how much overhead does the framework add over a direct provider call?" — the answer is "a few ms" for all of them; rounding error vs LLM call latency. Pick on ergonomics, not throughput.
- A correctness audit. None of these frameworks "checks" your prompts. They give you primitives for building agents; the agents are still as good (or as bad) as the prompts and tool design behind them.
- An endorsement. We use Agents.KT because we built it for our own constraints. If yours are different, pick differently. The frameworks listed here are all good at what they do — none of them is a bad choice for the use case they were designed for.

## Status notes (2026-05)

- **Agents.KT 0.6.0** — permission manifests, JSONL audit export, OTel / LangSmith bridges, constrained decoding, and DeepSeek shipped.
- **LangChain 0.3.x** — stable, ecosystem mature. LCEL is the recommended composition surface.
- **Semantic Kernel 1.x** — stable, MCP integration in preview.
- **AutoGen 0.4.x** — major architectural rewrite landed; the new core/agentchat split is recent.
- **MCP spec 2025-03-26** — covered by both this framework and the official Python / TypeScript SDKs.

If anything here ages out, file an issue or PR — the comparison should track reality, not historical reality.
