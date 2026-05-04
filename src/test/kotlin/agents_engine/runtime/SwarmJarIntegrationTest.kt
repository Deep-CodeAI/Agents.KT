package agents_engine.runtime

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
import kotlin.test.assertTrue

/**
 * End-to-end integration for #984 — really compiles, packages, and classloads
 * sibling JARs, then runs `Swarm.discover` against them.
 *
 * Two scenarios:
 *  - single-JAR — basic round-trip
 *  - multi-JAR (the actual swarm UX) — drop two independently-compiled JARs
 *    into one folder, point a `URLClassLoader` at all of them, verify both
 *    are discovered, both invoke, and a captain can absorb both
 *
 * Uses `javax.tools.JavaCompiler` (always shipped with the JDK), so no extra
 * Gradle dependency. Provider sources are intentionally Java (not Kotlin) so
 * we don't drag the Kotlin compiler into the test JVM.
 */
@OptIn(kotlin.io.path.ExperimentalPathApi::class)
class SwarmJarIntegrationTest {

    @Test
    fun `compile, JAR-package, classload, and discover one sibling`() {
        val workDir = createTempDirectory("swarm-jar-it-single-")
        try {
            val jar = compileSiblingJar(
                workDir = workDir,
                javaPackage = "external",
                providerSimpleName = "ExternalAgentProvider",
                agentName = "jar-sibling",
                jarFileName = "external-agent.jar",
            )
            val loader = URLClassLoader(arrayOf(jar.toUri().toURL()), this::class.java.classLoader)
            val discovered = Swarm.discover(loader)

            val names = discovered.map { it.name }
            assertTrue("jar-sibling" in names, "expected the JAR-loaded sibling; got: $names")

            val sibling = discovered.single { it.name == "jar-sibling" }
            @Suppress("UNCHECKED_CAST")
            val asString = sibling as agents_engine.core.Agent<String, String>
            assertEquals("from-jar:hello", asString.invoke("hello"))

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

    @Test
    fun `multi-JAR — drop several jars into a folder and discover them all`() {
        // The original swarm UX: many separate agent JARs sit in one folder,
        // a captain process points its classloader at all of them. Each JAR
        // brings its own provider. ServiceLoader iterates every entry.
        val workDir = createTempDirectory("swarm-jar-it-multi-")
        val jarFolder = workDir.resolve("agents").apply { createDirectories() }
        try {
            // Each "agent" is a separately-compiled JAR with its own
            // package and provider class — proving they don't share build
            // artifacts and could realistically come from different teams.
            val alphaJar = compileSiblingJar(
                workDir = workDir.resolve("alpha-build").apply { createDirectories() },
                javaPackage = "swarm.alpha",
                providerSimpleName = "AlphaProvider",
                agentName = "alpha-jar-agent",
                jarFileName = "alpha-agent.jar",
                outputDir = jarFolder,
            )
            val betaJar = compileSiblingJar(
                workDir = workDir.resolve("beta-build").apply { createDirectories() },
                javaPackage = "swarm.beta",
                providerSimpleName = "BetaProvider",
                agentName = "beta-jar-agent",
                jarFileName = "beta-agent.jar",
                outputDir = jarFolder,
            )

            // Point a single URLClassLoader at both JARs from the folder —
            // exactly what a captain main() would do on startup if it
            // shell-globbed the folder, or what a launch script does with
            // `java -cp 'agents/*'`.
            val loader = URLClassLoader(
                arrayOf(alphaJar.toUri().toURL(), betaJar.toUri().toURL()),
                this::class.java.classLoader,
            )

            val discovered = Swarm.discover(loader)
            val names = discovered.map { it.name }.toSet()

            // The URLClassLoader's parent is the test classloader, which has
            // the in-test fixture provider registered. So discovery yields
            // both JAR siblings PLUS that fixture. We assert only that the
            // JAR siblings are present — the multi-JAR scenario is what's
            // under test, not classloader hierarchy details.
            assertTrue(
                "alpha-jar-agent" in names,
                "alpha JAR should be discovered; got: $names",
            )
            assertTrue(
                "beta-jar-agent" in names,
                "beta JAR should be discovered; got: $names",
            )

            // Each sibling really runs — the JAR boundary is fully traversable.
            val alpha = discovered.single { it.name == "alpha-jar-agent" }
            val beta = discovered.single { it.name == "beta-jar-agent" }

            @Suppress("UNCHECKED_CAST")
            val alphaStr = alpha as agents_engine.core.Agent<String, String>

            @Suppress("UNCHECKED_CAST")
            val betaStr = beta as agents_engine.core.Agent<String, String>

            assertEquals("from-jar:ping", alphaStr.invoke("ping"))
            assertEquals("from-jar:pong", betaStr.invoke("pong"))

            // The captain absorbs both — each becomes a tool, each callable
            // independently. This is the full swarm UX.
            val captain = agents_engine.core.agent<String, String>("captain") {
                skills { skill<String, String>("op", "op") { implementedBy { "x" } } }
            }
            captain.absorb(alpha)
            captain.absorb(beta)

            assertTrue("alpha-jar-agent" in captain.toolMap)
            assertTrue("beta-jar-agent" in captain.toolMap)

            val alphaTool = captain.toolMap["alpha-jar-agent"]!!
            val betaTool = captain.toolMap["beta-jar-agent"]!!
            assertEquals(
                "from-jar:fromAlpha",
                alphaTool.executor(mapOf("query" to "fromAlpha")).toString(),
            )
            assertEquals(
                "from-jar:fromBeta",
                betaTool.executor(mapOf("query" to "fromBeta")).toString(),
            )
        } finally {
            workDir.deleteRecursively()
        }
    }

    @Test
    fun `multi-JAR — captain glob-loads every jar in a folder`() {
        // Variant: simulate the production UX more closely by glob-loading
        // every *.jar in the folder rather than passing each path explicitly.
        // The captain doesn't need to know how many siblings there are or
        // what they're called — drop a JAR into the folder and it joins.
        val workDir = createTempDirectory("swarm-jar-it-glob-")
        val jarFolder = workDir.resolve("agents").apply { createDirectories() }
        try {
            compileSiblingJar(
                workDir = workDir.resolve("a"), javaPackage = "swarm.a",
                providerSimpleName = "AProvider", agentName = "agent-a",
                jarFileName = "a.jar", outputDir = jarFolder,
            )
            compileSiblingJar(
                workDir = workDir.resolve("b"), javaPackage = "swarm.b",
                providerSimpleName = "BProvider", agentName = "agent-b",
                jarFileName = "b.jar", outputDir = jarFolder,
            )
            compileSiblingJar(
                workDir = workDir.resolve("c"), javaPackage = "swarm.c",
                providerSimpleName = "CProvider", agentName = "agent-c",
                jarFileName = "c.jar", outputDir = jarFolder,
            )

            // Glob the folder — what a launch script would do.
            val urls = Files.list(jarFolder).use { stream ->
                stream
                    .filter { it.toString().endsWith(".jar") }
                    .map { it.toUri().toURL() }
                    .toList()
            }.toTypedArray()
            assertEquals(3, urls.size, "should have globbed three JARs")

            val loader = URLClassLoader(urls, this::class.java.classLoader)
            val discovered = Swarm.discover(loader)
            val names = discovered.map { it.name }.toSet()

            // Assert all three JAR-supplied agents are present — the parent
            // classloader's in-test fixture may also appear; we don't care
            // about it for this assertion.
            assertTrue("agent-a" in names, "missing agent-a; got: $names")
            assertTrue("agent-b" in names, "missing agent-b; got: $names")
            assertTrue("agent-c" in names, "missing agent-c; got: $names")
        } finally {
            workDir.deleteRecursively()
        }
    }

    /**
     * Compile a Java AgentProvider source, write its META-INF/services
     * descriptor, and pack into a JAR file. Returns the JAR path.
     *
     * Each call is hermetic — its own work dir, its own package, its own
     * provider class — so multiple invocations produce independent JARs.
     */
    private fun compileSiblingJar(
        workDir: Path,
        javaPackage: String,
        providerSimpleName: String,
        agentName: String,
        jarFileName: String,
        outputDir: Path = workDir,
    ): Path {
        Files.createDirectories(workDir)

        val src = workDir.resolve("$providerSimpleName.java")
        src.writeText(
            """
            package $javaPackage;
            import agents_engine.runtime.AgentProvider;
            import agents_engine.core.Agent;
            public class $providerSimpleName implements AgentProvider {
                @Override
                public Agent<?, ?> build() {
                    return agents_engine.runtime.SwarmJarFixtureBridge.makeAgent("$agentName");
                }
            }
            """.trimIndent()
        )

        val classesDir = workDir.resolve("classes").apply { createDirectories() }
        val compiler: JavaCompiler = requireNotNull(ToolProvider.getSystemJavaCompiler()) {
            "no JDK Java compiler — running on a JRE? need a JDK"
        }
        compiler.getStandardFileManager(null, null, Charsets.UTF_8).use { fm ->
            fm.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, listOf(classesDir))
            fm.setLocation(
                StandardLocation.CLASS_PATH,
                System.getProperty("java.class.path").split(File.pathSeparator).map { File(it) },
            )
            val units = fm.getJavaFileObjectsFromPaths(listOf(src))
            val task = compiler.getTask(null, fm, null, null, null, units)
            val ok = task.call() ?: false
            assertTrue(ok, "Java compilation must succeed for $providerSimpleName")
        }

        val servicesDir = classesDir.resolve("META-INF/services").apply { createDirectories() }
        servicesDir.resolve("agents_engine.runtime.AgentProvider")
            .writeText("$javaPackage.$providerSimpleName\n")

        val jarPath = outputDir.resolve(jarFileName)
        Files.createDirectories(outputDir)
        packJar(classesDir, jarPath)
        return jarPath
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
 * sources (not inside the dynamically compiled JARs). The same bridge is
 * shared by all on-the-fly compiled JARs in the multi-JAR tests.
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
