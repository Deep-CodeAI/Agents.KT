package agents_engine.agntcy

/**
 * `agents_engine/agntcy/OasfTaxonomy.kt` — #4518 (PRD §12.6). The vendored OASF 1.0.0 skills/domains
 * taxonomy, loaded from the `oasf/skills-*.tsv` / `oasf/domains-*.tsv` resources. OASF skills/domains
 * are not free text: each is a
 * `{name, id}` where `id` is the taxonomy uid. The uids are explicitly assigned per node (top-level
 * categories are multiples of 100; deeper levels follow a per-level breadcrumb), so this is a lookup
 * table, not a computed formula — `path -> uid`, resolved offline and reproducibly.
 *
 * Slice 1 seeds the confirmed core of the tree; slice 2 vendors the complete `agntcy/oasf` schema and
 * adds a build-time cross-check against `schema.oasf.outshift.com`. Unknown paths resolve to `null`,
 * which `toOasfRecord` treats as "free-form skill" (omitted from the OASF record with a warning).
 */
object OasfTaxonomy {
    const val SCHEMA_VERSION: String = "1.0.0"

    private val skills: Map<String, Int> = loadTsv("/oasf/skills-1.0.0.tsv")
    private val domains: Map<String, Int> = loadTsv("/oasf/domains-1.0.0.tsv")

    /** Resolve an OASF skill path (e.g. `"agent_orchestration/multi_agent_planning"`) to its uid, or null. */
    fun skillUid(path: String): Int? = skills[path.trim().trim('/')]

    /** Resolve an OASF domain path to its uid, or null. */
    fun domainUid(path: String): Int? = domains[path.trim().trim('/')]

    private fun loadTsv(resource: String): Map<String, Int> {
        val text = OasfTaxonomy::class.java.getResourceAsStream(resource)?.bufferedReader()?.use { it.readText() }
            ?: error("OASF taxonomy resource missing: $resource")
        val map = LinkedHashMap<String, Int>()
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEach
            val tab = line.indexOf('\t')
            require(tab > 0) { "Malformed OASF taxonomy line in $resource: \"$raw\" (expected <path>\\t<uid>)" }
            val path = line.substring(0, tab).trim()
            val uid = line.substring(tab + 1).trim().toIntOrNull()
                ?: error("Non-numeric uid in $resource: \"$raw\"")
            require(path !in map) { "Duplicate OASF path in $resource: \"$path\"" }
            map[path] = uid
        }
        return map
    }
}
