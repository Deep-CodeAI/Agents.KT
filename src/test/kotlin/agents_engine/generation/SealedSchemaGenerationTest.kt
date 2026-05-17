package agents_engine.generation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

// Third batch for #1975. Targets the sealed-hierarchy schema/description
// generators that the existing GenerableSupportTest doesn't exercise deeply:
//
// - variantJsonSchema:255, 260, 759  — 7 mutants on the per-variant emitter
// - sealedJsonSchema:243              — `if (i > 0) append(",")` separator
// - sealedLlmDescription:167          — `if (genDescription.isNotEmpty())` guard
// - toLlmDescription:138              — sealed-dispatch in the public entry
//
// These are the "byte-identical to runtime" surfaces #1975 calls out as
// load-bearing for prompt-cache determinism (and for KSP parity).

@Generable("A decision a user can make")
sealed interface ReviewDecision {
    @Generable("Approve the request") data class Approved(val by: String, val notes: String?) : ReviewDecision
    @Generable("Reject with a reason") data class Rejected(val by: String, val reason: String) : ReviewDecision
    @Generable("Defer to later — no fields") data object Deferred : ReviewDecision
}

@Generable
sealed interface UndescribedRoot {
    @Generable data class Variant(val n: Int) : UndescribedRoot
}

@Generable("Empty root")
sealed interface EmptyRoot

class SealedSchemaGenerationTest {

    // ── sealedJsonSchema separator + variant ordering (line 243) ──────────────

    @Test fun `sealed JSON schema wraps oneOf around all variants`() {
        val schema = ReviewDecision::class.jsonSchema()
        assertTrue(schema.startsWith("""{"oneOf":["""), "must lead with oneOf array")
        assertTrue(schema.endsWith("]}"), "must close with array + outer brace")
    }

    @Test fun `sealed JSON schema places comma BETWEEN variants, not before first or after last`() {
        // Kills ConditionalsBoundaryMutator on line 243: `if (i > 0) append(",")`
        // Mutant flips `>` to `>=` which would prepend a comma before variant 0
        // — `[,{"type":...},{"type":...}]` — invalid JSON.
        val schema = ReviewDecision::class.jsonSchema()
        assertFalse(schema.contains("[,"), "leading comma is broken JSON: $schema")
        assertFalse(schema.contains(",]"), "trailing comma is broken JSON: $schema")
        // Three variants → exactly two commas between variant objects.
        val variantCommas = Regex("\\},\\{").findAll(schema).count()
        assertEquals(2, variantCommas, "ReviewDecision has 3 variants → exactly 2 inter-variant commas, got $variantCommas in $schema")
    }

    // ── variantJsonSchema: type discriminator + properties (lines 255, 260) ──

    @Test fun `each variant carries type discriminator with const matching simpleName`() {
        // variantJsonSchema:254 emits the const string. Kills mutants that drop
        // the const or use a wrong source.
        val schema = ReviewDecision::class.jsonSchema()
        assertTrue(
            schema.contains(""""type":{"type":"string","const":"Approved"}"""),
            "missing Approved type discriminator in: $schema",
        )
        assertTrue(schema.contains(""""const":"Rejected""""))
        assertTrue(schema.contains(""""const":"Deferred""""))
    }

    @Test fun `variant properties include all primary-ctor params`() {
        // variantJsonSchema:255 forEach loop emits property entries.
        // The forEach is mutated by `negated conditional` (skipping iterations).
        // Three Approved params expected: type (discriminator), by, notes.
        val schema = ReviewDecision::class.jsonSchema()
        // The "Approved" variant should reference "by" and "notes" in its properties.
        val approvedFragment = schema.substringAfter(""""const":"Approved"""").substringBefore("""{"type":"object"""")
        assertTrue(approvedFragment.contains(""""by"""), "Approved schema missing 'by' field")
        assertTrue(approvedFragment.contains(""""notes"""), "Approved schema missing 'notes' field")
    }

    @Test fun `required list includes type and only non-nullable non-optional params`() {
        // variantJsonSchema:260 filter — kills the "skip filter" mutant
        // (would include nullable fields like Approved.notes in required).
        val schema = ReviewDecision::class.jsonSchema()
        // Approved.notes is nullable → must NOT be in its variant's required list.
        // Approved.by is non-null → MUST be in its required list.
        val approvedFragment = schema.substringAfter(""""const":"Approved"""")
            .substringBefore("""{"type":"object"""")
        val requiredSection = approvedFragment.substringAfter(""""required":[""", "").substringBefore("""]""", "")
        assertTrue(requiredSection.contains(""""type""""), "type discriminator must always be required")
        assertTrue(requiredSection.contains(""""by""""), "non-nullable 'by' must be required")
        assertFalse(requiredSection.contains(""""notes""""), "nullable 'notes' must NOT be required, got: $requiredSection")
    }

    @Test fun `data-object variant has only the type discriminator and required`() {
        // Deferred has no ctor params. variantJsonSchema:251 — if ctor is null,
        // no properties are emitted beyond the discriminator.
        // sealedSubclasses ordering is NOT source-order; can't substringBefore("]}")
        // across the whole schema. Instead, assert the immediate sequence after
        // the Deferred discriminator: `}},"required":["type"],"additionalProperties":false}`
        // (no comma → no extra property, no comma in required → no extra required).
        val schema = ReviewDecision::class.jsonSchema()
        assertTrue(
            schema.contains(""""const":"Deferred"}},"required":["type"],"additionalProperties":false}"""),
            "Deferred should have no ctor params: properties should hold ONLY the type discriminator, " +
                "required list should hold ONLY \"type\". Actual schema: $schema",
        )
    }

    // ── sealedLlmDescription: optional-description guard (line 167) ──────────

    @Test fun `sealed description includes Generable description when present`() {
        // Line 167: `if (genDescription.isNotEmpty())` — mutant flips to always-true
        // or always-false. Test asserts the description text appears.
        val desc = ReviewDecision::class.toLlmDescription()
        assertTrue(
            desc.contains("A decision a user can make"),
            "Generable description must appear in sealed output: $desc",
        )
    }

    @Test fun `sealed description omits empty-description gap`() {
        // Same line 167, other side. UndescribedRoot has no description text
        // → no blank line + description block; just `## UndescribedRoot` followed by
        // the variants section.
        val desc = UndescribedRoot::class.toLlmDescription()
        assertTrue(desc.startsWith("## UndescribedRoot"))
        // Should NOT have a `description` text — only the variants intro.
        assertTrue(desc.contains("Choose one of the following variants:"))
    }

    @Test fun `sealed description lists all variants`() {
        val desc = ReviewDecision::class.toLlmDescription()
        // Each variant should be enumerated by name in the variants section.
        assertTrue(desc.contains("Approved"), "missing Approved variant in: $desc")
        assertTrue(desc.contains("Rejected"))
        assertTrue(desc.contains("Deferred"))
    }

    // ── toLlmDescription: sealed dispatch (line 138) ─────────────────────────

    @Test fun `sealed root routes to sealedLlmDescription not dataClassLlmDescription`() {
        // toLlmDescription:137 `if (isSealed) sealedLlmDescription() else dataClassLlmDescription()`.
        // Mutant negates the conditional → routes sealed to dataClass path,
        // which would NOT contain "Choose one of the following variants:".
        val desc = ReviewDecision::class.toLlmDescription()
        assertTrue(
            desc.contains("Choose one of the following variants:"),
            "sealed dispatch broken — must include variants intro: $desc",
        )
    }

    @Test fun `non-sealed data class routes to dataClassLlmDescription not sealedLlmDescription`() {
        // The other side of the dispatch.
        val desc = IntWrap::class.toLlmDescription()
        assertFalse(
            desc.contains("Choose one of the following variants:"),
            "non-sealed routed to sealed path — wrong dispatch: $desc",
        )
        assertTrue(desc.startsWith("## IntWrap"))
    }
}
