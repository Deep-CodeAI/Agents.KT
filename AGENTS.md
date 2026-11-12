# Agents.KT

_Source: [sdlc/agents/parts/project/project.md](sdlc/agents/parts/project/project.md)._

## Project

Agents.KT is the JVM runtime for an AI factory. A factory is a line of `Agent<IN, OUT>` stations: one input type, one output type, one job. The same line builds a software agent or an embodied agent. Software tools are compilers, repos, HTTP, and MCP. Body tools are sensors and actuators, attached the same way. The handoff for a partner agent is [docs/ai-factories.md](docs/ai-factories.md). The product site is [agents-kt.dev](https://agents-kt.dev/). The Maven coordinate is `ai.deep-code:agents-kt`. The license is MIT, held by Deep-Code.AI. The repository is [Deep-CodeAI/Agents.KT](https://github.com/Deep-CodeAI/Agents.KT).

The published version, the provider count, and the protocol status live in [release-metadata.yaml](release-metadata.yaml). [README.md](README.md) tracks that file. `ReleaseMetadataConsistencyTest` fails when a surface drifts. The [wiki](https://github.com/Deep-CodeAI/Agents.KT/wiki) is the learning path. When the wiki names an older published version, `release-metadata.yaml` and the README win.

| Path | Holds |
| --- | --- |
| `AGENTS.md` | This law, assembled from `sdlc/agents/parts/project/`. Edit the part, then copy the same text into the matching import block. |
| [docs/ai-factories.md](docs/ai-factories.md) | Partner handoff: one factory, software lines and embodied lines. |
| `README.md` | Product contract: security model, shipped surface, limitations, documentation index. |
| `docs/` | In-repo guides. Start with [docs/testing.md](docs/testing.md), [docs/source-layout.md](docs/source-layout.md), [docs/composition.md](docs/composition.md), [docs/threat-model.md](docs/threat-model.md). The README Documentation list names the rest. |
| [Wiki Home](https://github.com/Deep-CodeAI/Agents.KT/wiki) | Learning path, from Installation through Best Practices. |
| `release-metadata.yaml` | `currentRelease`, `developmentVersion`, `providers`, and `protocols`. |
| `src/main/kotlin/agents_engine/` | Core runtime. |
| `agents-kt-*` | Feature modules named in `settings.gradle.kts`. |

JDK 21 or newer. Kotlin version and the dependency line are the ones the README Current Release section states. The Gradle wrapper is the build. `./gradlew build` is the environment check in [Building From Source](https://github.com/Deep-CodeAI/Agents.KT/wiki/Building-From-Source).

<!-- END IMPORT: sdlc/agents/parts/project/project.md -->

<!-- BEGIN IMPORT: sdlc/agents/parts/project/legacy.md -->

_Source: [sdlc/agents/parts/project/legacy.md](sdlc/agents/parts/project/legacy.md)._

## Authority

Read these, in this order, before changing a claim or a boundary:

1. [release-metadata.yaml](release-metadata.yaml) for a version, a provider count, or a protocol status (`shipped`, `partial`, `experimental`, `spiked`).
2. [README.md](README.md) for the security model, the shipped list, and the known limitations.
3. [docs/threat-model.md](docs/threat-model.md) for which boundary the runtime enforces and which boundary the deployer owns.
4. The matching page under `docs/`.
5. The wiki page for the same topic, as the long-form narrative. [Best Practices](https://github.com/Deep-CodeAI/Agents.KT/wiki/Best-Practices) holds the DO and DON'T list.

### What the runtime owns

- Typed `Agent<IN, OUT>` contracts and the composition operators.
- Per-skill tool authorization and typed tool handles.
- MCP client and server surfaces on the same tool and skill shape.
- Permission manifests, declarative tool policies, and runtime audit correlation.
- JSONL audit export and the OTel, LangSmith, and Langfuse adapters through `ObservabilityBridge`.
- Local-first JVM execution. Ollama is the default local provider. Cloud providers are opt-in.

The README section "What Agents.KT Owns" is the full statement. [docs/permission-manifest.md](docs/permission-manifest.md) and [docs/cli.md](docs/cli.md) cover the manifest and the `agents-kt` CLI (`generate`, `inspect`, `verify`).

### What the deployer owns

The README section "What Agents.KT Does Not Own" is the full statement. The durable split:

| Boundary | Owner |
| --- | --- |
| Legal classification of a use case | Counsel and the compliance team. The runtime emits evidence. |
| Side effects inside a Kotlin tool lambda | The deployer. Layer 1 gates declared filesystem-path arguments. `processTool` adds the Layer 2 OS sandbox for subprocess tools. |
| Public MCP request rate | The gateway, plus `McpServer` auth and policy. |
| Prompt-injection classification | The deployer, through `onBeforeTurn`. |
| Vector storage, an eval product, hosted orchestration | The surrounding system. The runtime is the typed JVM boundary under those integrations. |

A change that widens one of these claims updates the README, the threat model, and `release-metadata.yaml` in the same edit.

<!-- END IMPORT: sdlc/agents/parts/project/legacy.md -->

<!-- BEGIN IMPORT: sdlc/agents/parts/project/new_wave.md -->

_Source: [sdlc/agents/parts/project/new_wave.md](sdlc/agents/parts/project/new_wave.md)._

## Working on the runtime

### Modules

`settings.gradle.kts` is the module list. The root project `agents-kt` holds `src/main/kotlin/agents_engine/`. Feature modules stay out of the core classpath:

| Module | Role |
| --- | --- |
| `agents-kt-ksp` | `@Generable` metadata. `kotlin-reflect` stays optional. |
| `agents-kt-no-reflect-test` | Consumer classpath that excludes `kotlin-reflect`. |
| `agents-kt-manifest` | Deterministic permission manifests. |
| `agents-kt-cli` | `generate`, `inspect`, `verify` outside Gradle. |
| `agents-kt-observability`, `agents-kt-otel`, `agents-kt-langsmith`, `agents-kt-langfuse` | Audit export and bridges. |
| `agents-kt-rag`, `agents-kt-rag-langchain4j`, `agents-kt-rag-spring-ai` | RAG seam. |
| `agents-kt-identity`, `agents-kt-dir` | AGNTCY Identity and DIR. |
| `agents-kt-detekt` | `ToolBodyForbiddenApis` and `ToolCapabilityExtractor`. |
| `agents-kt-whisper-jni` | Optional native Whisper backend. |

### Packages

The wiki [Architecture Overview](https://github.com/Deep-CodeAI/Agents.KT/wiki/Architecture-Overview) describes the original four packages: `core`, `composition`, `model`, `generation`. The tree also has `a2a`, `agui`, `agntcy`, `content`, `mcp`, `nlweb`, `runtime`, `sandbox`, `testing`, `x402`, and `internal`. Match the package that already owns the behavior.

[docs/source-layout.md](docs/source-layout.md) is the file rule. One top-level type per file. The file name is the type name. A sealed type stays with its direct subtypes. `./gradlew checkOneTypePerFile` enforces it. A real sealed-ADT exception goes in `config/one-type-per-file-allowlist.txt` with a `# sealed-ADT: keep` reason. The allowlist only shrinks.

### Agent shape

Follow the wiki [Best Practices](https://github.com/Deep-CodeAI/Agents.KT/wiki/Best-Practices) and [Agent and Type Contract](https://github.com/Deep-CodeAI/Agents.KT/wiki/Agent-and-Type-Contract).

- One agent, one job. A second job is a second agent composed with `then`, `/`, `forum`, `.loop`, `.branch`, or `handoff`.
- Skill and knowledge descriptions say when the model should pick them. Ambiguous skill routing throws `SkillRoutingException`. Predicate `skillSelection { }` stays the path when the input already decides.
- Tool allowlists take typed handles from `tool(...)`. String names stay for built-ins and MCP names discovered at runtime.
- An agentic agent sets `budget { maxTurns = N }`. A loop has an exit. `maxAgentDepth` bounds nested re-entry.
- Each agent instance is placed once. A second placement throws at construction. Reuse goes through a factory. [Single-Placement Rule](https://github.com/Deep-CodeAI/Agents.KT/wiki/Single-Placement-Rule).
- Agents pass data through typed input and output, or through `MemoryBank`. A public signature stays a concrete `IN` and `OUT`.
- Structured model output uses `@Generable` and `@Guide`. A long prompt lives in a classpath resource and loads with `loadResource`.
- Knowledge entries hold no secrets.
- Public API on the 0.x line stays additive. A behavior change is named in the CHANGELOG.

### Verification

[docs/testing.md](docs/testing.md) is the in-repo test guide. The wiki [Building From Source](https://github.com/Deep-CodeAI/Agents.KT/wiki/Building-From-Source) is the environment guide.

| Command | When |
| --- | --- |
| `./gradlew test` | Before a commit. Excludes `live-llm`, `live-mcp`, and `interactive`. |
| `./gradlew :test --tests "pkg.Class"` | One class. Scope the module. A root-wide `test --tests` also hits modules that lack that class. |
| `./gradlew detekt` | With the change. `detekt.yml` holds new code to the rules. The baseline only shrinks. |
| `./gradlew check` | Includes `checkOneTypePerFile`, `checkReadmeVersion`, and `checkDetektBaseline`. |
| `./gradlew integrationTest` | LLM client or agentic-loop work. Needs Ollama. |
| `./gradlew mcpIntegrationTest` | MCP client or server work. Needs `MCP_REDMINE_URL`. |
| `./gradlew pitest` | After a new behavior, to see mutants the tests leave alive. |

Unit tests inject a `ModelClient`. Live tests stay tagged. Linux sandbox tests (`bwrap`, `firejail`) need a Linux kernel and skip on macOS. CI runs them on Ubuntu.

A reported defect gets a regression test that fails on the current code before the fix. An existing assertion stays. When a contract change forces an edit, the test name and the contract that replaced it go in the task note.

<!-- END IMPORT: sdlc/agents/parts/project/new_wave.md -->
