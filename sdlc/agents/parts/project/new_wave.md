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
