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
