package agents_engine.agntcy

/**
 * `agents_engine/agntcy/OasfClassification.kt` — #4519 (PRD §12.6). A resolved OASF taxonomy entry on an
 * imported record: a skill or domain as `{name, id}`. OASF's `base_skill` carries the constraint
 * `at_least_one: [id, name]`, so at least one is always present after [fromOasfRecord]; when only one was
 * supplied, the other is filled in from the vendored [OasfTaxonomy] (exact-path, no fuzzy matching).
 */
data class OasfClassification(
    val name: String?,
    val id: Int?,
)
