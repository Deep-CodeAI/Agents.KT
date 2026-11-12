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
| `sdlc/` and `tasks/` | Adopted Tatenergo.AI snapshot. Framework work stays in `src/` and the `agents-kt-*` modules. |

JDK 21 or newer. Kotlin version and the dependency line are the ones the README Current Release section states. The Gradle wrapper is the build. `./gradlew build` is the environment check in [Building From Source](https://github.com/Deep-CodeAI/Agents.KT/wiki/Building-From-Source).
