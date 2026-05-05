# KSP — Design Note: Lifting Runtime Checks to Compile Time

**Status:** Draft. **Owner:** kskobeltsyn. **Date:** 2026-05-03. **Target release:** 0.3.0 (additive).

## Problem

Agents.KT performs **72 runtime `require` / `check` / `error(...)` invocations** across `src/main/kotlin/agents_engine/` (counted 2026-05-03). Roughly half are *construction-time DSL validations* — they fire when the user calls `agent { ... }`, *before any LLM round-trip*. They protect against typos, name collisions, missing skills, and structural mistakes. They are correct, but they are paid:

1. **Every JVM start** of every consumer of the library.
2. **At first invocation**, not at the consumer's compile time. A typo in `tools("writeFle")` bombs in CI test runs, not in the IDE.
3. **Through `kotlin-reflect`** in many cases (`findAnnotation<Generable>()`, `KClass.isSubclassOf`), pulling a 3 MB dependency into the published artifact.

KSP is the natural lift. This note inventories what's actually liftable, what isn't, and proposes a phased path.

## Inventory

The 72 sites cluster into four buckets. The per-site references below use `file:line`.

### Bucket A — Construction-time DSL validations (KSP-liftable)

These run when the agent / skill / tools block is built. Their inputs are static at the consumer's compile time — the user wrote literal strings, literal classes, literal counts. KSP can see all of it.

| Check | Site(s) | Liftable? |
|---|---|---|
| Tool-name uniqueness within `tools { }` | `model/ToolDef.kt:69, 83, 94, 106, 132` | **Yes** — names are `String` literals at the call site; KSP can fold across a builder block |
| Tool-name not in `RESERVED_MEMORY_TOOL_NAMES` | `core/Agent.kt:59`, `model/ToolDef.kt:51` | **Yes** — set is constant |
| Skill-name uniqueness within agent | `core/Agent.kt:330` | **Yes** if skills are declared as properties; partial if skills are inline `skill<...>(name, ...)` with literal strings |
| Skill→tool reference resolution (`tools("writeFile")`) | `core/Agent.kt:404` | **Yes** if KSP indexes `tool(...)` definitions and `tools(...)` references in the same module — the highest-leverage win |
| `+autoTool("name")` reference resolution | `core/Agent.kt:410` | **Yes** — same mechanism |
| Skill produces agent's OUT type | `core/Agent.kt:397` | **Yes** — KSP can read OUT-type parameter and skill `outType` declarations |
| `@Generable` annotation present on typed-tool `Args` | `model/ToolDef.kt:137` | **Yes** — annotation presence is a KSP staple |
| `Args` is not sealed | `model/ToolDef.kt:141` | **Yes** — KSP `KSClassDeclaration.classKind` |
| Threshold in `0.0..1.0` (`onBudgetThreshold`) | `core/Agent.kt:177, 193` | **Yes**, when literal — `agent.onBudgetThreshold(0.8)` is liftable; an arg from a config `Double` is not |
| `Loop.maxIterations > 0` | `composition/loop/Loop.kt:21` | **Yes**, when literal |
| Forum: captain not in participants | `composition/forum/Forum.kt:147, 156, 170` | **Yes** if agent declarations are property-level |
| Forum: `forumReturnAllowed` ⊆ all agents | `composition/forum/Forum.kt:184` | **Yes** — same |
| Forum: no `forum_return` collision in toolMap | `composition/forum/Forum.kt:118` | **Yes** — same |
| MCP DSL: duplicate server name | `mcp/AgentMcpDsl.kt:52` | **Yes** — names are literals |
| MCP DSL: exactly one transport selected | `mcp/AgentMcpDsl.kt:78, 82, 87` | **Yes** — chosen by which method was called |
| `Branch`: cases cover sealed hierarchy | `composition/branch/BranchBuilder.kt:77` | **Yes** — *and we already do compile-time exhaustiveness for `when` in Kotlin*. KSP can match that for our builder. |
| Pipeline: duplicate stage names | `model/AgenticLoop.kt:59` | **Yes** |
| `slash(name)` non-blank | `runtime/LiveShow.kt:439` | **Yes** when literal |

**Total in Bucket A:** ~30 of 72.

### Bucket B — Freeze-after-construction guards

These are post-construction mutator guards: `Agent.checkFrozen` (at `core/Agent.kt:133`) and `Skill.checkFrozen` (at `core/Skill.kt:40`) — fire if anyone tries to add a tool, skill, or knowledge entry *after* the agent finished building.

KSP doesn't help here directly — the right fix is a **type split**: `AgentBuilder` (mutators) and `Agent` (no mutators), where `agent { }` returns the latter. We've effectively done this via the `frozen` boolean and `@PublishedApi internal var`, but the type-level version is cleaner. Out of KSP scope; tracked separately.

**Total in Bucket B:** 2.

### Bucket C — `@Generable` reflection paths (already on roadmap)

Runtime walks via `kotlin-reflect`:

- `argsClass.constructFromMap(rawArgs)` — `model/ToolDef.kt:147`, `mcp/McpServer.kt:235`
- Typed-output parsing — `model/AgenticLoop.kt:171, 202`
- Forum return-value parsing — `composition/forum/Forum.kt:88, 94, 97`
- `GenerableSupport` — schema gen, lenient parser, `toLlmDescription()`, `PartiallyGenerated<T>`

These are the **payloads** the existing roadmap entry (Phase 2 KSP processor) targets. They are larger than the validation lift but already scoped — see `docs/roadmap.md:39`, `README.md:67, 142`.

**Total in Bucket C:** ~25 reflection call sites + the entire `GenerableSupport` codepath.

### Bucket D — Inherently runtime

Cannot be lifted; the inputs only exist at runtime.

- LLM response parsing — `model/AgenticLoop.kt:171, 202, 259` (model-produced JSON / tool-calls)
- MCP wire-format parsing — `mcp/McpClient.kt:46, 48, 53, 87, 89, 92, 94, 104, 123`, `mcp/HttpMcpTransport.kt:45, 49, 56`, `mcp/LineDelimitedMcpTransport.kt:49`
- MCP server skill dispatch (`isAgentic`, deserialize from JSON) — `mcp/McpServer.kt:174, 182, 222, 235`
- Agent placement in composition (already-placed guard) — `core/Agent.kt:234`
- Skill-selection dispatch (selected name not in candidates) — `core/Agent.kt:288, 292, 300`
- Branch dispatch with no `onElse` — `composition/branch/Branch.kt:37, 50`
- Swarm `absorb` invariants (sibling shape) — `runtime/Swarm.kt:67, 70, 82` — *liftable in principle if we knew sibling identities at compile time, but the whole point of Swarm is ServiceLoader discovery at runtime, so no.*

**Total in Bucket D:** ~15.

## What KSP buys us, by bucket

| Bucket | Sites | KSP impact | Effort |
|---|---|---|---|
| A — DSL validation | ~30 | **Move to compile-time errors** with red squiggles in IDE | High value, medium effort — needs DSL annotations + index |
| B — Freeze guards | 2 | Zero (type-split is the fix) | Out of scope for KSP |
| C — `@Generable` reflection | ~25 + GenerableSupport | **Eliminate `kotlin-reflect` for happy path**; typed `PartiallyGenerated<T>` | High value, high effort — *already roadmapped* |
| D — Runtime parsing | ~15 | Zero (inputs are runtime) | N/A |

**Total liftable: ~55 of 72 (76%).** Of those, ~30 are pure DSL validation that doesn't need any deserialisation work — quick wins.

## DSL changes needed for Bucket A

The biggest unlock is **typed cross-references**. Right now:

```kotlin
val writeFile = tool("write_file", "...") { ... }  // returns ToolDef? No — it goes into a list
skill<...> { tools("write_file") }                  // string lookup, validated at agent build()
```

For KSP to resolve `"write_file"` ↔ `tool("write_file", ...)`, both have to be in the same compilation unit and indexed by KSP. That works today, but it's awkward — KSP would need to:

1. Walk every call to `agents_engine.model.tool(...)` (or `tools.tool(...)`) and collect literal name strings.
2. Walk every call to `Skill.tools(...)` and collect literal name strings.
3. Cross-reference, fail compilation on unknowns.

The *cleaner* option is to switch to typed refs — already on the roadmap as `tools(writeFile, compile)` (see `README.md:69`). With typed refs, KSP isn't even strictly required for cross-reference checking — Kotlin's type system handles it. KSP is then narrower: just enforce `@Generable` shape, name-uniqueness within a block, threshold ranges.

Recommendation: **typed refs first, KSP-validation second**. KSP becomes the catcher for things the type system can't see (literal-only validations: name format, range bounds, sealed-coverage).

## Phasing

### Phase 0 — Inventory & feasibility (this note)
- ✅ Catalog all 72 check sites
- ✅ Bucket them
- Decision point: typed-refs-first or KSP-first?

### Phase 1 — Typed tool/skill references (no KSP)
- Refactor `tools("name")` → `tools(toolRef1, toolRef2)`
- Make `tool(...)` return a typed handle (`Tool<Args, Result>`)
- Skill `tools(...)` accepts handles, not strings
- Compile-time error on typos; eliminates `core/Agent.kt:404` and `:410`
- *No KSP module yet.* This is a DSL evolution.

### Phase 2 — KSP module: `:agents-kt-ksp`
- New Gradle module — `org.jetbrains.kotlin.ksp` + `SymbolProcessorProvider`
- Validate `@Generable` shape at compile time (annotation present, not sealed, primary constructor exists, all params primitive/`@Generable`/`List<>`)
- Validate `agent { }` block static invariants (Bucket A residue: thresholds, loop counts, slash-name format)
- Generate `*GeneratedSchema.kt` companion files — JSON Schema + `toLlmDescription()` + lenient parser, all stamped at compile time
- Runtime path keeps reflection as fallback when KSP isn't applied → consumers without KSP still work

### Phase 3 — Drop `kotlin-reflect` from runtime classpath
- Once KSP-generated schema is the default and reflection is the fallback, mark reflection path as opt-in
- Move `kotlin-reflect` to `compileOnly` in the published POM
- Document in `docs/generation.md`

## Open questions

1. **Library-level API:** how does the Gradle plugin tell KSP which classes to process? `@Generable` is the obvious entry; `agent { }` is harder because it's a builder block, not a declaration. Options: (a) generate per-`@Generable`-class schema + leave block validation to a separate KSP visitor, (b) annotate the agent function (`@Agent fun coder() = agent { ... }`) — probably (a) first, (b) later.
2. **Multi-module:** if a consumer declares `@Generable Foo` in module A and uses it in module B's `agent { }`, KSP only sees within-module. We'd need to **publish** the generated schema next to the class, or fall back to reflection across module boundaries.
3. **Incremental KSP:** must work with Gradle build cache and incremental compilation — design generated file naming + symbol IDs accordingly.
4. **Native CLI / GraalVM:** roadmap also lists a GraalVM native binary. KSP-generated schemas help here a lot (no reflection metadata to register). Worth designing in lockstep.

## Recommendation

Start with **Phase 1 (typed refs)** before opening the KSP module. It eliminates the highest-frequency runtime failure mode (`tools("typo")`) without introducing a new build-tool dependency, and it simplifies what the eventual KSP processor has to do. KSP can then focus narrowly on `@Generable` schema gen — which is the actually-hard part — instead of being forced to also do cross-reference indexing across the whole agent block.

If Phase 1 lands cleanly, Phase 2 (the `:agents-kt-ksp` module) becomes a 0.3.0 release. Phase 3 (drop `kotlin-reflect`) is 0.4.0.
