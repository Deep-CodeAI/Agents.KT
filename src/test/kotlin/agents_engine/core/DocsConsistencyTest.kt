package agents_engine.core

import agents_engine.model.ModelProvider
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

// Docs-consistency guard. An external 0.7.23 review found the docs lagging the
// runtime in exactly these spots: provider counts frozen at four/six while
// ModelProvider.entries had seven, a documented first-match routing fallback
// that 0.7.21 (#3087) replaced with a fail-loud exception, and a HITL guide
// referencing a Decision variant that never existed. Each check here pins a
// doc claim to the code surface it describes, so the next provider / Decision
// variant / routing change fails the build until the docs move with it.
class DocsConsistencyTest {

    private fun doc(relative: String): String {
        val path = Path.of(relative)
        assertTrue(Files.exists(path), "expected $relative to exist (tests run from the repo root)")
        return Files.readString(path)
    }

    // Index = the number it spells; entries.size indexes straight into it.
    private val countWords = listOf(
        "zero", "one", "two", "three", "four", "five", "six",
        "seven", "eight", "nine", "ten", "eleven", "twelve",
    )

    @Test
    fun `providers_md lists every ModelProvider entry`() {
        val providersDoc = doc("docs/providers.md")
        val missing = ModelProvider.entries.filterNot { providersDoc.contains(it.name, ignoreCase = false) }
        assertTrue(missing.isEmpty(), "docs/providers.md is missing ModelProvider entries: $missing")
    }

    @Test
    fun `provider count words track ModelProvider entries size`() {
        val word = countWords[ModelProvider.entries.size]
        val claims = listOf(
            "docs/providers.md" to "`ModelProvider.entries` has **$word** values",
            "docs/model-and-tools.md" to "$word providers ship today",
            "docs/model-and-tools.md" to "All $word providers share the `ModelClient` interface",
            "SECURITY.md" to "$word first-party providers",
        )
        for ((file, claim) in claims) {
            assertTrue(
                doc(file).contains(claim, ignoreCase = true),
                "$file must state the current provider count (${ModelProvider.entries.size}): " +
                    "expected to find \"$claim\" — update the doc when providers change",
            )
        }
    }

    @Test
    fun `docs only reference Decision variants that exist`() {
        val realVariants = Decision::class.sealedSubclasses.mapNotNull { it.simpleName }.toSet()
        // Lookbehind: don't match the suffix of sibling types (HumanDecision.Rejected,
        // LlmErrorDecision.Rethrow, BudgetDecision.Extend) — only the bare Decision type.
        val referencePattern = Regex("""(?<![A-Za-z])Decision\.([A-Z][A-Za-z]*)""")
        val docFiles = Files.list(Path.of("docs")).use { stream ->
            stream.filter { it.toString().endsWith(".md") }.toList()
        } + listOf(Path.of("README.md"), Path.of("SECURITY.md"))
        val phantoms = docFiles.flatMap { file ->
            referencePattern.findAll(Files.readString(file))
                .map { it.groupValues[1] }
                .filterNot { it in realVariants }
                .map { "$file -> Decision.$it" }
        }
        assertTrue(
            phantoms.isEmpty(),
            "docs reference Decision variants that don't exist (have: $realVariants): $phantoms",
        )
    }

    @Test
    fun `living docs contain no known-stale security or status phrases`() {
        // Each pattern is a claim that was true once and got fixed in a truth-surface
        // pass (0.7.24 / 0.7.25); reappearing means a doc regressed to the stale state.
        // Historical documents (CHANGELOG, RELEASE_NOTES, premortems, prd) are exempt —
        // they record what WAS true.
        val banned = listOf(
            Regex("""(?i)sandboxing isn.t shipped""") to "Layer 1/2 enforcement shipped in 0.7.0 (#2890/#1916)",
            Regex("""(?i)no tool sandboxing""") to "subprocess tools are sandboxed via processTool (#2914)",
            Regex("""(?i)audit evidence, not""") to "declared ToolPolicy is enforced since 0.7.0, not just reviewable",
            Regex("""(?i)isn.t shipped yet""") to "name the version/issue instead of an undated 'yet'",
            Regex("""(?i)the JSONL exporter lands""") to "exportJsonl shipped (#1914)",
            Regex("""(?i)all three first-party""") to "seven providers stream; three native + four inherited SSE",
            Regex("""(?i)\b(four|five|six) (model )?(providers|adapters)\b""") to
                "provider count is ModelProvider.entries.size",
        )
        val historical = setOf("prd.md")
        val docFiles = Files.list(Path.of("docs")).use { stream ->
            stream.filter { p ->
                val name = p.fileName.toString()
                name.endsWith(".md") && !name.startsWith("premortem-") && name !in historical
            }.toList()
        } + listOf(Path.of("README.md"), Path.of("SECURITY.md"))
        val hits = docFiles.flatMap { file ->
            val text = Files.readString(file)
            banned.mapNotNull { (pattern, hint) ->
                pattern.find(text)?.let { "$file: \"${it.value}\" — $hint" }
            }
        }
        assertTrue(hits.isEmpty(), "stale claims resurfaced in living docs:\n${hits.joinToString("\n")}")
    }

    @Test
    fun `routing docs document fail-loud ambiguity not first-match`() {
        val modelAndTools = doc("docs/model-and-tools.md")
        val ambiguousRow = modelAndTools.lines().filter { it.contains("Multiple candidates, no model") }
        assertTrue(ambiguousRow.isNotEmpty(), "docs/model-and-tools.md lost its routing decision table")
        assertTrue(
            ambiguousRow.all { it.contains("SkillRoutingException") },
            "the ambiguous-routing row must document SkillRoutingException (#3087), " +
                "not a first-match fallback: $ambiguousRow",
        )
    }
}
