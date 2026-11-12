# AI factories on Agents.KT

Handoff for a partner agent. This file is the project picture. Shipped behavior lives in [README.md](../README.md) and [release-metadata.yaml](../release-metadata.yaml). When this file and those two disagree about a version, a provider, or a protocol, those two win.

## Aim

Agents.KT is the runtime for building an AI factory. A factory is a repeatable line: a typed job goes in, bounded stations do one job each, and a typed result comes out. The same line shape builds two kinds of agent.

| Factory | The job | The tools | The result |
| --- | --- | --- | --- |
| Software | A spec, a ticket, a file, or a user turn | Compilers, repos, HTTP, MCP servers, other agents | A patch, a document, a decision, or a tool result |
| Embodied | A mission, a pose, or a sensor frame | Cameras, drivers, grippers, navigation, motion primitives | A command a controller can run, or a stop |

One runtime serves both. The body is a set of tools and typed messages. It is a second product of the same factory, and it is a second framework.

## What a station is

A station is `Agent<IN, OUT>`: one input type, one output type, one job. A skill on that station allowlists the tools that station may call. The compiler checks that one station's output is the next station's input.

A line is composition:

| Line | Code | Use |
| --- | --- | --- |
| Sequence | `a then b` | Software: parse, then generate, then review. Embodied: perceive, then plan, then act. |
| Same input, concurrent stations | `a / b` | Two reviewers, or two sensors, on one workpiece. |
| Council | `forum { }` | Several stations, one captain, one verdict. |
| Repeat until done | `.loop { }` | Refine a patch, or replan while the goal is open. The loop has an exit. |
| Route on type | `.branch { }` | Send a sealed result to the station that owns that case, including a stop. |
| Hand off | `triage handoff { }` | Pass the typed input to a specialist. The specialist does not receive the source history. |

Each station instance sits on one line. A second use is a new instance from a factory function. Stations pass work through typed input and output, or through `MemoryBank`.

## What the factory freezes before a shift

Before a line runs, the factory can show the boundary:

- The permission manifest lists agents, skills, tools, policies, providers, and budgets, and hashes that graph.
- A budget caps turns, tool calls, time, and tokens. `maxAgentDepth` caps nested calls.
- A tool policy records filesystem, network, and environment scope. Declared filesystem paths are gated in the JVM. Subprocess tools can run in an OS sandbox.
- Runtime events carry the manifest hash. A JSONL log and a tamper-evident tool ledger record what the line did.
- A human gate is an interceptor or a HITL interrupt. For an embodied line, a stop is a station outcome, not a sentence in a prompt.

The deployer still owns legal classification, rate limits on public MCP, prompt-injection detection, and any safety controller under the actuators. The runtime emits evidence and enforces the boundaries declared in code. [docs/threat-model.md](threat-model.md) is the table of which layer enforces which boundary.

## Software factory, as it stands

This repository is that factory's runtime. Kotlin on JDK 21+. Maven coordinate `ai.deep-code:agents-kt`. License MIT, Deep-Code.AI. Product site [agents-kt.dev](https://agents-kt.dev/). Source [github.com/Deep-CodeAI/Agents.KT](https://github.com/Deep-CodeAI/Agents.KT).

A software line is a library inside a JVM service, an MCP server, an A2A peer, or a same-JVM swarm. Local models start with Ollama. Cloud providers are opt-in. The provider count and the protocol status (`mcp`, `a2a`, `agui`, `nlweb`, `x402`, `agntcy`) are the fields in `release-metadata.yaml`.

Core code is `src/main/kotlin/agents_engine/`. Feature modules are the `agents-kt-*` projects in `settings.gradle.kts`. `sdlc/` and `tasks/` are an adopted Tatenergo.AI snapshot. Factory work goes in `src/` and the `agents-kt-*` modules.

## Embodied factory, as a target

The embodied line uses the same station, the same allowlist, and the same manifest. A camera frame is an input type. A motion primitive is a tool. A pose is an output type. The partner agent designs that line in types and tools.

This repository has no robot, ROS, or actuator package. Do not treat a body driver as shipped code. Attach a body by giving the act station tools whose arguments are commands the existing controller already accepts, with a budget, a policy, and a branch whose stop case runs before motion.

## What the partner agent does with this

1. Read [README.md](../README.md) for the security model and the shipped list.
2. Read `currentRelease`, `providers`, and `protocols` in [release-metadata.yaml](../release-metadata.yaml) before stating a version.
3. Design a line as `Agent<IN, OUT>` stations. One station, one job.
4. Choose software tools or body tools. Keep both behind the skill allowlist.
5. Put a stop, a human gate, and a budget on any line that can move code into production or move a body.
6. Verify runtime changes with `./gradlew test`. The in-repo guide is [docs/testing.md](testing.md). The learning path is the [wiki](https://github.com/Deep-CodeAI/Agents.KT/wiki).

Contributor rules for editing this repository are in [AGENTS.md](../AGENTS.md).
