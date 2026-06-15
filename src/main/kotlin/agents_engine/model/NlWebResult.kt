package agents_engine.model

/**
 * One result from an NLWeb `/ask` response (#4541): a ranked match backed by the
 * site's schema.org-structured content. [schemaType] is the `@type` lifted from
 * the result's `schema_object` (e.g. `Recipe`, `PodcastEpisode`) when present.
 */
data class NlWebResult(
    val url: String,
    val name: String? = null,
    val site: String? = null,
    val score: Double? = null,
    val description: String? = null,
    val schemaType: String? = null,
)
