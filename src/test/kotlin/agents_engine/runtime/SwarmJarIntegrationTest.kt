package agents_engine.runtime

import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import javax.tools.JavaCompiler
import javax.tools.StandardLocation
import javax.tools.ToolProvider
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end integration for #984 — compiles a real AgentProvider Java source
 * on the fly, packages it into a real JAR, loads it via [URLClassLoader] (with
 * the test classpath as parent so the loaded class can see [AgentProvider] and
 * the framework's [agents_engine.core.Agent] machinery), and verifies that
 * [Swarm.discover] finds the agent through `ServiceLoader`.
 *
 * Uses `javax.tools.JavaCompiler` (always shipped with the JDK), so no extra
 * Gradle dependency is needed. The provider source is intentionally Java
 * (not Kotlin) so we don't need to drag the Kotlin compiler into the test JVM.
 */
@OptIn(kotlin.io.path.ExperimentalPathApi::class)
class SwarmJarIntegrationTest {

    @Test
    fun `compile, JAR-package, classload, and discover a sibling agent`() {
        val workDir = createTempDirectory("swarm-jar-it-")
        try {
            // 1. Write the Java source for an AgentProvider implementation.
            //    The build() method returns an Agent constructed via the
            //    framework's agent() / Skill DSL — same classes the test JVM
            //    already has on its classpath.
            val src = workDir.resolve("ExternalAgentProvider.java")
            src.writeText(
                """
                package external;
                import agents_engine.runtime.AgentProvider;
                import agents_engine.core.Agent;
                public class ExternalAgentProvider implements AgentProvider {
                    @Override
                    public Agent<?, ?> build() {
                        // Build via the public Java-friendly factory in
                        // SwarmJarFixtureBridge — keeps this Java source small
                        // and avoids fighting Kotlin's reified-generics from
                        // a Java caller.
                        return agents_engine.runtime.SwarmJarFixtureBridge.makeAgent("jar-sibling");
                    }
                }
                """.trimIndent()
            )

            // 2. Compile the source with javax.tools.JavaCompiler.
            val classesDir = workDir.resolve("classes").apply { createDirectories() }
            val compiler: JavaCompiler = requireNotNull(ToolProvider.getSystemJavaCompiler()) {
                "no JDK Java compiler available — running on a JRE? need a JDK"
            }
            val fileManager = compiler.getStandardFileManager(null, null, Charsets.UTF_8)
            fileManager.use { fm ->
                fm.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, listOf(classesDir))
                // Use the test JVM's classpath so AgentProvider, Agent, and
                // SwarmJarFixtureBridge resolve.
                fm.setLocation(
                    StandardLocation.CLASS_PATH,
                    System.getProperty("java.class.path").split(File.pathSeparator).map { File(it) },
                )
                val units = fm.getJavaFileObjectsFromPaths(listOf(src))
                val task = compiler.getTask(null, fm, null, null, null, units)
                val ok = task.call() ?: false
                assertTrue(ok, "Java compilation must succeed")
            }

            // 3. Write the META-INF/services descriptor next to the compiled class.
            val servicesDir = classesDir.resolve("META-INF/services").apply { createDirectories() }
            servicesDir.resolve("agents_engine.runtime.AgentProvider")
                .writeText("external.ExternalAgentProvider\n")

            // 4. Pack everything into a real JAR file.
            val jarPath = workDir.resolve("external-agent.jar")
            packJar(classesDir, jarPath)

            // 5. URLClassLoader over the JAR with the test classpath as parent.
            //    Parent-first delegation gives the loaded provider access to
            //    AgentProvider / Agent / SwarmJarFixtureBridge.
            val loader = URLClassLoader(
                arrayOf(jarPath.toUri().toURL()),
                this::class.java.classLoader,
            )

            // 6. Run the real Swarm.discover with that classloader.
            val discovered = Swarm.discover(loader)
            val names = discovered.map { it.name }
            assertTrue(
                "jar-sibling" in names,
                "expected the JAR-loaded sibling in discovery; got: $names",
            )

            // 7. Sanity: the discovered Agent really invokes — proving the
            //    JAR-loaded code path is end-to-end functional, not just a
            //    name on a list.
            val sibling = discovered.single { it.name == "jar-sibling" }
            @Suppress("UNCHECKED_CAST")
            val asString = sibling as agents_engine.core.Agent<String, String>
            assertEquals("from-jar:hello", asString.invoke("hello"))

            // 8. Absorb it onto a captain and verify the absorbed tool works.
            val captain = agents_engine.core.agent<String, String>("captain") {
                skills { skill<String, String>("op", "op") { implementedBy { "x" } } }
            }
            captain.absorb(sibling)
            val tool = captain.toolMap["jar-sibling"]!!
            assertEquals("from-jar:world", tool.executor(mapOf("query" to "world")).toString())
        } finally {
            workDir.deleteRecursively()
        }
    }

    private fun packJar(classesDir: Path, jarPath: Path) {
        val manifest = Manifest().apply { mainAttributes.putValue("Manifest-Version", "1.0") }
        Files.newOutputStream(jarPath).use { fos ->
            JarOutputStream(fos, manifest).use { jar ->
                Files.walk(classesDir).use { paths ->
                    for (path in paths) {
                        if (Files.isDirectory(path)) continue
                        val rel = classesDir.relativize(path).toString().replace(File.separatorChar, '/')
                        jar.putNextEntry(JarEntry(rel))
                        Files.newInputStream(path).use { it.copyTo(jar) }
                        jar.closeEntry()
                    }
                }
            }
        }
    }
}

/**
 * Bridge for [SwarmJarIntegrationTest] — the JAR-compiled Java source calls
 * here so the test source itself stays small and Kotlin stays in the test
 * sources (not inside the dynamically compiled JAR).
 */
internal object SwarmJarFixtureBridge {
    @JvmStatic
    fun makeAgent(name: String): agents_engine.core.Agent<*, *> =
        agents_engine.core.agent<String, String>(name) {
            skills {
                skill<String, String>("op", "op") {
                    implementedBy { "from-jar:$it" }
                }
            }
        }
}
