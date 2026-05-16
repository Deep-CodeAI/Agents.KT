package agents_engine.runtime.internals

import agents_engine.core.Agent
import agents_engine.core.agent
import agents_engine.core.loadResource

/**
 * #1837 — Agents.KT InternalsAgent: a self-hosting docs agent whose skills
 * correspond to source files in the framework. Exposed via
 * `McpServer.from(buildInternalsAgent())` so IDE-side AI agents (Cursor,
 * Claude Desktop) can query the framework's own structure as tools.
 *
 * **Design.** Each skill is `implementedBy { _ -> loadResource("internals-agent/<path>.md") }`
 * — a pure data fetch. The IDE's LLM (not ours) decides which skill to
 * call based on the skill's `description` (sold to the LLM as a tool);
 * the skill returns the curated KDoc adjunct as text content. No
 * framework-side LLM round-trip is needed — the InternalsAgent has no
 * `model { }` configured because its skills don't use one.
 *
 * **Adjunct files.** Each skill loads from `src/main/resources/internals-agent/<package>/<File>.md`.
 * These are curated markdown summaries kept synchronized with each source
 * file's top-of-file `/** ... */` description (a deliberate-redundancy
 * choice: the .md is what the IDE LLM sees, the KDoc is what a human
 * reading the source sees, and they say the same thing).
 *
 * **Per-file children.** Skills are added incrementally as the v0.6.0
 * per-file children of #1837 get worked. The pattern is:
 * 1. Pick the next open child (e.g., `redmine_get_issue 1839` for
 *    `core/Skill.kt`).
 * 2. Add the top-of-file KDoc to the source file.
 * 3. Create the adjunct `.md` at `internals-agent/<path>.md`.
 * 4. Register a skill in this file's `skills { }` block.
 * 5. Commit referencing the child issue; close it with the SHA.
 *
 * Running locally: `./gradlew runInternalsAgent` (or via the [Main]
 * companion file). Configure Cursor / Claude Desktop to point at the
 * advertised loopback URL.
 */
fun buildInternalsAgent(): Agent<String, String> = agent<String, String>("agents-kt-internals") {
    skills {
        // ── core/ ──────────────────────────────────────────────────────
        skill<String, String>(
            name = "core_agent_kt",
            description = "Source-file knowledge for agents_engine/core/Agent.kt — the Agent<IN, OUT> class, single-placement rule, invoke / invokeSuspend / session entry points, observability hooks (skillChosenListener, toolUseListener, knowledgeUsedListener, errorListener, budgetThresholdListener), freeze-after-construction contract. Call when the IDE LLM needs to reason about how Agents are constructed, invoked, or observed.",
        ) {
            implementedBy { _ -> loadResource("internals-agent/core/Agent.md") }
        }

        skill<String, String>(
            name = "core_skill_kt",
            description = "Source-file knowledge for agents_engine/core/Skill.kt — the Skill<IN, OUT> unit-of-work class, deterministic vs agentic flavors (implementedBy vs tools(...)), the freeze contract (#668), knowledge entries surfaced via toLlmContext() and knowledgeTools(), memory opt-in (#856 useMemory()), output transformer for typed OUT, auto-description with kotlin-reflect graceful degradation (#1718). Call when the IDE LLM needs to reason about how skills are declared, what they do, or how they're frozen.",
        ) {
            implementedBy { _ -> loadResource("internals-agent/core/Skill.md") }
        }

        skill<String, String>(
            name = "core_memory_kt",
            description = "Source-file knowledge for agents_engine/core/Memory.kt — the MemoryBank ConcurrentHashMap-backed scratch-pad keyed by agent name, the three built-in tools (memory_read / memory_write / memory_search), per-agent vs shared-workspace topologies, maxLines line-history truncation, opt-in mechanics under #856. Call when the IDE LLM needs to reason about how agents persist or share state across turns.",
        ) {
            implementedBy { _ -> loadResource("internals-agent/core/Memory.md") }
        }

        skill<String, String>(
            name = "core_resources_kt",
            description = "Source-file knowledge for agents_engine/core/Resources.kt — the loadResource / loadResourceOrNull classpath helpers (#980): UTF-8 decoding, leading-slash tolerance, fail-fast on missing, contextClassLoader-first lookup. Call when the IDE LLM needs to reason about prompt/knowledge resource loading or InternalsAgent's adjunct mechanism.",
        ) {
            implementedBy { _ -> loadResource("internals-agent/core/Resources.md") }
        }

        skill<String, String>(
            name = "core_pipelineevent_kt",
            description = "Source-file knowledge for agents_engine/core/PipelineEvent.kt — the sealed PipelineEvent (SkillChosen, ToolCalled, KnowledgeLoaded, ErrorOccurred) and the Agent.observe { } extension that chains it over the four per-event listeners additively (#965). Call when the IDE LLM needs to reason about post-hoc observability vs the in-loop AgentEvent stream.",
        ) {
            implementedBy { _ -> loadResource("internals-agent/core/PipelineEvent.md") }
        }

        skill<String, String>(
            name = "core_skillroute_kt",
            description = "Source-file knowledge for agents_engine/core/SkillRoute.kt — the @Generable SkillRoute structured output (skillName, confidence, rationale) the LLM router returns when picking among candidate skills (#641), the skillSelectionConfidenceThreshold (default 0.6), SkillRoutingException, and how rationale surfaces via the routerRationale listener. Call when the IDE LLM needs to reason about multi-skill agents and routing decisions.",
        ) {
            implementedBy { _ -> loadResource("internals-agent/core/SkillRoute.md") }
        }

        // ── model/ ─────────────────────────────────────────────────────
        skill<String, String>(
            name = "model_agenticloop_kt",
            description = "Source-file knowledge for agents_engine/model/AgenticLoop.kt — the multi-turn chat↔tool loop (executeAgentic) at the heart of every agentic-skill invocation. Builds per-skill tool allowlist (skill tools + agent capabilities + #856 memory + knowledge), runs turns until final answer or budget cap, honors maxTurns/maxToolCalls/maxDuration/perToolTimeout/maxTokens/maxConsecutiveSameTool, argument repair up to 8 retries, streaming-aware emitter (#1739), wrap-friendly effectivePrompt (#1707), cumulative TokenUsage (#1740). Call when the IDE LLM needs to reason about how agentic skills actually execute.",
        ) {
            implementedBy { _ -> loadResource("internals-agent/model/AgenticLoop.md") }
        }

        skill<String, String>(
            name = "model_budgetconfig_kt",
            description = "Source-file knowledge for agents_engine/model/BudgetConfig.kt — six caps (maxTurns 8, maxToolCalls 32, maxDuration 5m, perToolTimeout null, maxTokens null #963, maxConsecutiveSameTool null #969), the BudgetBuilder DSL, BudgetReason enum, BudgetExceededException, and pre-cap threshold warnings via onBudgetThreshold. Call when the IDE LLM needs to reason about cost/runaway control for agentic invocations.",
        ) {
            implementedBy { _ -> loadResource("internals-agent/model/BudgetConfig.md") }
        }

        skill<String, String>(
            name = "model_claudeclient_kt",
            description = "Source-file knowledge for agents_engine/model/ClaudeClient.kt — Anthropic Messages API adapter (#1644). LlmMessage→Anthropic JSON wire mapping (system field, tool_use/tool_result blocks with synthetic toolu_<n> IDs, input_schema spelling), streaming via SSE (text_delta and input_json_delta chunks), boundary errors via LlmProviderException (#702), open sendChat seam for tests. Call when the IDE LLM needs to reason about wiring the framework to Anthropic.",
        ) {
            implementedBy { _ -> loadResource("internals-agent/model/ClaudeClient.md") }
        }

        // Future skills (one per src file) land here as their child issues
        // (#1847 → #1900) get worked. Keep entries grouped by package to
        // mirror the source tree's structure for readability.
    }
}
