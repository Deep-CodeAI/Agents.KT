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

        skill<String, String>(
            name = "model_inlinetoolcallparser_kt",
            description = "Source-file knowledge for agents_engine/model/InlineToolCallParser.kt — parses {\"tool\":\"name\",\"arguments\":{...}} text into ToolCall and the reverse JSON encoder. Used by providers without native function-calling that instruct the LLM to emit inline tool-call JSON. Lenient parsing via generation.LenientJsonParser, strict encoding. Call when the IDE LLM needs to reason about how LLM text becomes a ToolCall.",
        ) {
            implementedBy { _ -> loadResource("internals-agent/model/InlineToolCallParser.md") }
        }

        skill<String, String>(
            name = "model_llmchunk_kt",
            description = "Source-file knowledge for agents_engine/model/LlmChunk.kt — provider-level streaming chunk union (TextDelta, ToolCallStarted/ArgumentsDelta/Finished, End) per #1722. Narrow — no agentic concepts. Flow shape is [TextDelta]* [Started Δ* Finished]* End. Default ModelClient.chatStream wraps non-streaming chat with this shape. callId honored from ToolCall when present (#1739). Call when the IDE LLM needs to reason about LLM streaming.",
        ) {
            implementedBy { _ -> loadResource("internals-agent/model/LlmChunk.md") }
        }

        skill<String, String>(
            name = "model_llmproviderexception_kt",
            description = "Source-file knowledge for agents_engine/model/LlmProviderException.kt — single-class file (#702). Boundary error for LLM-provider protocol failures (auth, capability, model-not-found, malformed request, 4xx/5xx). Distinguished from IllegalStateException (output parse) and BudgetExceededException. All three shipped clients throw this. Call when the IDE LLM needs to reason about retry policy for provider failures.",
        ) {
            implementedBy { _ -> loadResource("internals-agent/model/LlmProviderException.md") }
        }

        skill<String, String>(
            name = "model_modelclient_kt",
            description = "Source-file knowledge for agents_engine/model/ModelClient.kt — the LLM transport fun interface and shared types (LlmMessage, ToolCall with callId #1739, TokenUsage #963, LlmResponse.Text/ToolCalls). Default chatStream wraps non-streaming chat with LlmChunk emission. Three shipped impls: Ollama, Claude, OpenAI. Custom clients implement the SAM. Call when the IDE LLM needs to reason about adding a new LLM provider or testing with a fake client.",
        ) {
            implementedBy { _ -> loadResource("internals-agent/model/ModelClient.md") }
        }

        skill<String, String>(
            name = "model_modelconfig_kt",
            description = "Source-file knowledge for agents_engine/model/ModelConfig.kt — the model { } DSL slot. ModelProvider enum (OLLAMA/ANTHROPIC/OPENAI), immutable ModelConfig with masked-apiKey toString (security), ModelBuilder with ollama/claude/openai factory methods, lazy client construction at AgenticLoop time, build() requires apiKey for Anthropic/OpenAI. Call when the IDE LLM needs to reason about configuring an agent's LLM provider.",
        ) {
            implementedBy { _ -> loadResource("internals-agent/model/ModelConfig.md") }
        }

        skill<String, String>(
            name = "model_ollamaclient_kt",
            description = "Source-file knowledge for agents_engine/model/OllamaClient.kt — local Ollama HTTP adapter (default ModelClient). POST /api/chat at localhost:11434, OpenAI-style tool schema (Ollama emulates), parseToolArguments handling Map / JSON-string / null shapes, NDJSON streaming, LlmProviderException on errors (#702), open sendChat seam for tests. Call when the IDE LLM needs to reason about local LLM integration.",
        ) {
            implementedBy { _ -> loadResource("internals-agent/model/OllamaClient.md") }
        }

        skill<String, String>(
            name = "model_ollamapreflight_kt",
            description = "Source-file knowledge for agents_engine/model/OllamaPreflight.kt — fail-fast reachability check (#1132). GET /api/tags with 2s connect / 3s request timeouts. Wire into LiveShowBuilder.precheck so REPL aborts at startup with a clear error naming host:port instead of failing mid-turn behind the spinner. Throws LlmProviderException on IOException or non-2xx. Call when the IDE LLM needs to reason about REPL startup health checks.",
        ) {
            implementedBy { _ -> loadResource("internals-agent/model/OllamaPreflight.md") }
        }

        skill<String, String>(
            name = "model_onerrorbuilder_kt",
            description = "Source-file knowledge for agents_engine/model/OnErrorBuilder.kt — the onError { } tool-failure recovery DSL. Three handler slots (invalidArgs, deserializationError, executionError) returning RepairResult (Fixed / Retry / Escalated / Unrecoverable). RepairScope.fix(agent, retries) delegates repair to a sibling string→string agent. executeAgentFix retry loop handles EscalationException by switching to Escalated. Call when the IDE LLM needs to reason about graceful recovery from broken tool calls.",
        ) {
            implementedBy { _ -> loadResource("internals-agent/model/OnErrorBuilder.md") }
        }

        skill<String, String>(
            name = "model_openaiclient_kt",
            description = "Source-file knowledge for agents_engine/model/OpenAiClient.kt — OpenAI Chat Completions adapter (#1656). POST /v1/chat/completions wire mapping: system kept in messages array (vs Anthropic's hoisted field), stringified function.arguments JSON (not object), synthetic call_<n> IDs, parameters spelling (vs Anthropic's input_schema), SSE streaming with [DONE] terminator, openAiBaseUrl override for Azure/regional/proxy, open sendChat seam. Call when the IDE LLM needs to reason about wiring the framework to OpenAI.",
        ) {
            implementedBy { _ -> loadResource("internals-agent/model/OpenAiClient.md") }
        }

        skill<String, String>(
            name = "model_streamingaggregator_kt",
            description = "Source-file knowledge for agents_engine/model/StreamingAggregator.kt — chatOrStream entry point (#1739) the agentic loop calls per turn. emitter==null → client.chat() unchanged; emitter!=null → collect client.chatStream() while emitting AgentEvent.Token / ToolCallStarted / ToolCallArgumentsDelta, rebuild LlmResponse with stable callIds. AgentEventEmitter typealias (non-suspend per #1745). ToolCallFinished fires later in the loop with executor result. Interleaving-safe via callId routing. Call when the IDE LLM needs to reason about streaming plumbing.",
        ) {
            implementedBy { _ -> loadResource("internals-agent/model/StreamingAggregator.md") }
        }

        skill<String, String>(
            name = "model_tooldef_kt",
            description = "Source-file knowledge for agents_engine/model/ToolDef.kt — ToolDef (wire shape: Map<String,Any?>→Any? executor + optional session-aware sessionExecutor #1752 + untrustedOutput sandbox flag + argsType KClass for typed coercion), Tool<Args,Result> compile-time-checked handle (#1015/#1016) returned by tool(...) builders. argsType drives constructFromMap deserialization with @Generable. errorHandler slot wired by onError { }. Call when the IDE LLM needs to reason about declaring tools or about typed-vs-stringly-typed tool refs.",
        ) {
            implementedBy { _ -> loadResource("internals-agent/model/ToolDef.md") }
        }

        skill<String, String>(
            name = "model_toolerror_kt",
            description = "Source-file knowledge for agents_engine/model/ToolError.kt — sealed ToolError (InvalidArgs / DeserializationError / ExecutionError / EscalationError), Severity enum (LOW/MEDIUM/HIGH/CRITICAL), EscalationException + ToolExecutionException. The wire format consumed by the onError { } DSL. Call when the IDE LLM needs to reason about classifying or handling tool failures.",
        ) {
            implementedBy { _ -> loadResource("internals-agent/model/ToolError.md") }
        }

        // ── generation/ ────────────────────────────────────────────────
        skill<String, String>(
            name = "generation_annotations_kt",
            description = "Source-file knowledge for agents_engine/generation/Annotations.kt — the three annotations behind @Generable types: @Generable marker (CLASS, RUNTIME), @LlmDescription override (verbatim multi-line description), @Guide per-field/per-variant guidance. Read by both runtime reflection (GenerableSupport) and KSP processor. Call when the IDE LLM needs to reason about declaring an LLM-generable type.",
        ) {
            implementedBy { _ -> loadResource("internals-agent/generation/Annotations.md") }
        }

        skill<String, String>(
            name = "generation_generablesupport_kt",
            description = "Source-file knowledge for agents_engine/generation/GenerableSupport.kt — runtime support for @Generable. Three surfaces (jsonSchema, toLlmDescription, constructFromMap/fromLlmOutput/toLlmInput). Two-path dispatch: KSP-generated __GeneratedSchema lookup first (#1701-#1704 zero-reflection), reflection fallback otherwise. ConcurrentHashMap caching with MISS sentinel. Sealed-interface discriminator handling. Call when the IDE LLM needs to reason about typed structured-output coercion.",
        ) {
            implementedBy { _ -> loadResource("internals-agent/generation/GenerableSupport.md") }
        }

        skill<String, String>(
            name = "generation_lenientjsonparser_kt",
            description = "Source-file knowledge for agents_engine/generation/LenientJsonParser.kt — tolerant JSON parser for LLM output. Strips markdown fences, removes trailing commas, extracts first balanced {...}/[...] from explanatory text. MAX_NESTING_DEPTH=64 guards StackOverflowError (#854 — Error not Exception so try/catch can't catch it). Returns null on any failure (never throws). Call when the IDE LLM needs to reason about parsing LLM-emitted JSON.",
        ) {
            implementedBy { _ -> loadResource("internals-agent/generation/LenientJsonParser.md") }
        }

        skill<String, String>(
            name = "generation_partiallygenerated_kt",
            description = "Source-file knowledge for agents_engine/generation/PartiallyGenerated.kt — immutable accumulator for fields arriving incrementally from an LLM stream. withField folds in deltas (returns new instance), toComplete delegates to constructFromMap and returns T? or null when required fields missing. Typed property access is a planned KSP Phase 2 affordance. Call when the IDE LLM needs to reason about streaming structured-output consumption.",
        ) {
            implementedBy { _ -> loadResource("internals-agent/generation/PartiallyGenerated.md") }
        }

        skill<String, String>(
            name = "generation_reflectionfallback_kt",
            description = "Source-file knowledge for agents_engine/generation/ReflectionFallback.kt — withReflection inline wrapper for graceful degradation (#1705 #1718). Catches LinkageError (incl. NoClassDefFoundError) and KotlinReflectionNotSupportedError → returns null. Other exceptions propagate (real bugs aren't swallowed). Enables compileOnly kotlin-reflect when consumers apply :agents-kt-ksp. Call when the IDE LLM needs to reason about reflection-vs-KSP dispatch or no-reflect environments.",
        ) {
            implementedBy { _ -> loadResource("internals-agent/generation/ReflectionFallback.md") }
        }

        // ── composition/branch/ ────────────────────────────────────────
        skill<String, String>(
            name = "composition_branch_branch_kt",
            description = "Source-file knowledge for agents_engine/composition/branch/Branch.kt — the routing operator. Branch<IN, OUT> runs a source agent then dispatches on result type/null/else to a registered route. Order matters — first matching route wins. Suspend executors (#638) compose with agents/pipelines. Session-aware sessionExecutor + routedAgentName (#1748). Call when the IDE LLM needs to reason about type-dispatch routing.",
        ) {
            implementedBy { _ -> loadResource("internals-agent/composition/branch/Branch.md") }
        }

        skill<String, String>(
            name = "composition_branch_branchbuilder_kt",
            description = "Source-file knowledge for agents_engine/composition/branch/BranchBuilder.kt — the Branch DSL. on<T>() then agent / then pipeline, onNull(), orElse(). Each then marks the target placed (single-placement) and wires sessionExecutor (#1748) via runAgentInSession or pipeline.effectiveSessionExec. ReflectionFallback for the cast lambda. Call when the IDE LLM needs to reason about how Branch routes are assembled.",
        ) {
            implementedBy { _ -> loadResource("internals-agent/composition/branch/BranchBuilder.md") }
        }

        skill<String, String>(
            name = "composition_branch_branchsessionextension_kt",
            description = "Source-file knowledge for agents_engine/composition/branch/BranchSessionExtension.kt — branch.session(input) (#1748). Source agent streams first (agentId=source.name), matched route streams with routedAgentName, terminal Completed uses routedAgentName. Routes built outside BranchBuilder fall back gracefully. Channel.BUFFERED + SupervisorJob + Dispatchers.Default. Call when the IDE LLM needs to reason about streaming a branch.",
        ) {
            implementedBy { _ -> loadResource("internals-agent/composition/branch/BranchSessionExtension.md") }
        }

        // ── composition/forum/ ─────────────────────────────────────────
        skill<String, String>(
            name = "composition_forum_forum_kt",
            description = "Source-file knowledge for agents_engine/composition/forum/Forum.kt — the deliberation operator. Forum<IN,OUT> fans input out to N heterogeneous Agent<IN,*> participants concurrently, collects as ForumTranscript<IN>, optional captain synthesizes final OUT. ParticipantContribution(agentName, output: Any?). @Mention text routing via onMentionEmitted. coroutineScope concurrency (#638). Call when the IDE LLM needs to reason about multi-agent voting/debate/ensemble.",
        ) {
            implementedBy { _ -> loadResource("internals-agent/composition/forum/Forum.md") }
        }

        skill<String, String>(
            name = "composition_forum_forumsessionextension_kt",
            description = "Source-file knowledge for agents_engine/composition/forum/ForumSessionExtension.kt — forum.session(input). Participants run concurrently via runAgentInSession; events interleave on shared channel demultiplexable by agentId. Captain runs after deliberation completes; its events stream too. Terminal Completed carries captain's output or the transcript. Call when the IDE LLM needs to reason about streaming a forum.",
        ) {
            implementedBy { _ -> loadResource("internals-agent/composition/forum/ForumSessionExtension.md") }
        }

        // ── composition/loop/ ──────────────────────────────────────────
        skill<String, String>(
            name = "composition_loop_loop_kt",
            description = "Source-file knowledge for agents_engine/composition/loop/Loop.kt — feedback-loop operator. execution(input) → output, next(output)→IN? derives next input (null terminates), maxIterations=1000 default with require(>0). Suspend execution (#638) composes with operators. sessionExec for streaming iterations (#1749), loopAgentId for terminal Completed. Call when the IDE LLM needs to reason about iterative refinement.",
        ) {
            implementedBy { _ -> loadResource("internals-agent/composition/loop/Loop.md") }
        }

        skill<String, String>(
            name = "composition_loop_loopsessionextension_kt",
            description = "Source-file knowledge for agents_engine/composition/loop/LoopSessionExtension.kt — loop.session(input) (#1749). Iterations run serially (loops are sequential — events interleave one iteration at a time). Same termination rules as non-streaming. maxIterations breach → Failed. Constructed outside factory functions falls back to non-streaming execution. Call when the IDE LLM needs to reason about streaming a loop.",
        ) {
            implementedBy { _ -> loadResource("internals-agent/composition/loop/LoopSessionExtension.md") }
        }

        // ── composition/parallel/ ──────────────────────────────────────
        skill<String, String>(
            name = "composition_parallel_parallel_kt",
            description = "Source-file knowledge for agents_engine/composition/parallel/Parallel.kt — concurrent fan-out via / operator. Parallel<IN,OUT> runs N branches concurrently returning List<OUT>. Same IN and OUT required. coroutineScope (#638) — caller owns scope/cancellation/dispatcher. sessionExecutions for per-branch session streaming (#1750). Sibling cancel on failure. Call when the IDE LLM needs to reason about homogeneous concurrent execution vs heterogeneous Forum.",
        ) {
            implementedBy { _ -> loadResource("internals-agent/composition/parallel/Parallel.md") }
        }

        skill<String, String>(
            name = "composition_parallel_parallelsessionextension_kt",
            description = "Source-file knowledge for agents_engine/composition/parallel/ParallelSessionExtension.kt — parallel.session(input) (#1750). Branches launched concurrently via async; events interleave by arrival on shared channel demultiplexable by agentId. awaitAll() before terminal Completed(List<OUT>) — result order preserved. Sibling cancellation on failure. sessionExecutions=null → fall back to executions without emitter. Call when the IDE LLM needs to reason about streaming a parallel.",
        ) {
            implementedBy { _ -> loadResource("internals-agent/composition/parallel/ParallelSessionExtension.md") }
        }

        // ── composition/pipeline/ ──────────────────────────────────────
        skill<String, String>(
            name = "composition_pipeline_pipeline_kt",
            description = "Source-file knowledge for agents_engine/composition/pipeline/Pipeline.kt — sequential composition via then infix. Many then overloads (Agent/Pipeline/Forum/Loop/Parallel/Branch). Suspend execution lambda lets cross-operator chains run in one coroutine without nested runBlocking (#638). sessionExec (#1745) declared BEFORE execution for trailing-lambda binding safety. effectiveSessionExec falls back to execution when null. Single-placement enforcement. Call when the IDE LLM needs to reason about chaining agents into a pipeline.",
        ) {
            implementedBy { _ -> loadResource("internals-agent/composition/pipeline/Pipeline.md") }
        }

        skill<String, String>(
            name = "composition_pipeline_pipelinesessionextension_kt",
            description = "Source-file knowledge for agents_engine/composition/pipeline/PipelineSessionExtension.kt — pipeline.session(input) (#1745). Runs effectiveSessionExec — explicit sessionExec streams inner agents, null fallback runs execution surfacing only terminal events. Terminal Completed uses last agent's name. Channel.BUFFERED + SupervisorJob + Dispatchers.Default. Known gap: un-converted then overloads don't stream inner events. Call when the IDE LLM needs to reason about streaming a pipeline.",
        ) {
            implementedBy { _ -> loadResource("internals-agent/composition/pipeline/PipelineSessionExtension.md") }
        }

        // ── composition/wrap/ ──────────────────────────────────────────
        skill<String, String>(
            name = "composition_wrap_wrap_kt",
            description = "Source-file knowledge for agents_engine/composition/wrap/Wrap.kt — teacher-student prompt override (#1698 / 'wrap' / PRD's '>>' operator). teacher wrap student returns Pipeline where teacher's String output becomes student's system prompt for that one call. Race-safe via effectivePrompt passthrough (#1707) — student's baked prompt never mutated. Two framings: education (specialize generalist) and security (lock task surface). Call when the IDE LLM needs to reason about dynamic prompt overrides.",
        ) {
            implementedBy { _ -> loadResource("internals-agent/composition/wrap/Wrap.md") }
        }

        // Future skills (one per src file) land here as their child issues
        // (#1876 → #1900) get worked. Keep entries grouped by package to
        // mirror the source tree's structure for readability.
    }
}
