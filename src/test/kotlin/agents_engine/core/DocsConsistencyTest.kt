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
