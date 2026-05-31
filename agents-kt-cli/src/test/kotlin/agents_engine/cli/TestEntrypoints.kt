package agents_engine.cli

import agents_engine.core.ToolRisk
import agents_engine.core.agent
import agents_engine.manifest.PermissionManifest
import agents_engine.manifest.PermissionManifestProvider
import agents_engine.manifest.permissionManifest

/**
 * Fixture entrypoints for the CLI tests — Kotlin `object`s implementing
 * [PermissionManifestProvider], the simplest shape [ManifestEntrypointLoader] resolves.
 * Loaded by FQN via the loader's parent classloader (the test classpath), so the
 * `generate`/`verify --entrypoint` paths run end-to-end without packaging a jar.
 *
 * Mirrors the "high-risk widening" pair in the manifest module's own tests:
 * [StrictEntrypoint] (deny network / no writes) is the approved baseline;
 * [WidenedEntrypoint] (allow network / writes) widens it.
 */
object StrictEntrypoint : PermissionManifestProvider {
    override fun permissionManifest(): PermissionManifest =
        agent<String, String>("ops") {
            tools {
                tool("syncTicket") {
                    policy {
                        risk = ToolRisk.Low
                        network { denyAll() }
                        filesystem { writeNone() }
                    }
                    executor { "ok" }
                }
            }
            skills {
                skill<String, String>("sync") {
                    @Suppress("DEPRECATION")
                    tools("syncTicket")
                    implementedBy { it }
                }
            }
        }.permissionManifest()
}

object WidenedEntrypoint : PermissionManifestProvider {
    override fun permissionManifest(): PermissionManifest =
        // Same agent name as StrictEntrypoint: the verifier keys tools by
        // agentName.toolName, so widening is detected per same-named agent + tool.
        agent<String, String>("ops") {
            tools {
                tool("syncTicket") {
                    policy {
                        risk = ToolRisk.High
                        network { allowAll() }
                        filesystem { write("/var/tickets/**") }
                    }
                    executor { "ok" }
                }
            }
            skills {
                skill<String, String>("sync") {
                    @Suppress("DEPRECATION")
                    tools("syncTicket")
                    implementedBy { it }
                }
            }
        }.permissionManifest()
}
