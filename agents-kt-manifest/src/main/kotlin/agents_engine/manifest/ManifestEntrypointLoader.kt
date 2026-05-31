package agents_engine.manifest

import agents_engine.composition.branch.Branch
import agents_engine.composition.forum.Forum
import agents_engine.composition.loop.Loop
import agents_engine.composition.parallel.Parallel
import agents_engine.composition.pipeline.Pipeline
import agents_engine.core.Agent
import agents_engine.mcp.McpServer
import java.io.File
import java.net.URLClassLoader

/**
 * Loads a [PermissionManifest] from a user entrypoint class by reflection, given a
 * runtime classpath. Gradle-free, so both the `agentManifest`/`verifyAgentManifest`
 * Gradle tasks (#manifest) and the standalone native CLI (#1923) share one loader —
 * a non-Gradle consumer (CI gate, ops, a regulator) can generate/verify the same
 * deterministic manifest a build would.
 *
 * The entrypoint class may, in order of preference:
 *  1. be a Kotlin `object` implementing [PermissionManifestProvider];
 *  2. expose a static no-arg `permissionManifest()` method;
 *  3. expose an instance no-arg `permissionManifest()` (Kotlin object or no-arg ctor).
 *
 * The returned value is coerced to a [PermissionManifest] — it may itself be a
 * manifest, or any composition (`Agent`, `Pipeline`, …) whose `.permissionManifest()`
 * extension is applied.
 */
object ManifestEntrypointLoader {

    fun load(className: String, runtimeClasspath: Set<File>): PermissionManifest {
        val urls = runtimeClasspath.map { it.toURI().toURL() }.toTypedArray()
        URLClassLoader(urls, javaClass.classLoader).use { loader ->
            val klass = Class.forName(className, true, loader)
            val kotlinObject = kotlinObjectInstance(klass)
            if (kotlinObject is PermissionManifestProvider) {
                return kotlinObject.permissionManifest()
            }

            val staticMethod = klass.methods.firstOrNull { method ->
                method.name == "permissionManifest" &&
                    method.parameterCount == 0 &&
                    java.lang.reflect.Modifier.isStatic(method.modifiers)
            }
            if (staticMethod != null) {
                return coerceManifest(staticMethod.invoke(null))
            }

            val instance = kotlinObject ?: noArgInstance(klass)
            val instanceMethod = klass.methods.firstOrNull { method ->
                method.name == "permissionManifest" && method.parameterCount == 0
            } ?: throw IllegalArgumentException(
                "Manifest entrypoint $className must implement PermissionManifestProvider " +
                    "or expose a no-arg permissionManifest() method.",
            )
            return coerceManifest(instanceMethod.invoke(instance))
        }
    }

    private fun kotlinObjectInstance(klass: Class<*>): Any? =
        runCatching { klass.getField("INSTANCE").get(null) }.getOrNull()

    private fun noArgInstance(klass: Class<*>): Any =
        klass.getDeclaredConstructor().also { it.isAccessible = true }.newInstance()

    private fun coerceManifest(value: Any?): PermissionManifest =
        when (value) {
            is PermissionManifest -> value
            is Agent<*, *> -> value.permissionManifest()
            is Pipeline<*, *> -> value.permissionManifest()
            is Parallel<*, *> -> value.permissionManifest()
            is Forum<*, *> -> value.permissionManifest()
            is Loop<*, *> -> value.permissionManifest()
            is Branch<*, *> -> value.permissionManifest()
            is McpServer -> value.permissionManifest()
            else -> throw IllegalArgumentException(
                "permissionManifest() returned ${value?.let { it::class.qualifiedName } ?: "null"}; " +
                    "expected PermissionManifest, Agent, or an Agents.KT composition.",
            )
        }
}
