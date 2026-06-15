package agents_engine.agntcy

import agents_engine.core.Agent
import agents_engine.mcp.McpJson
import java.util.logging.Logger

private val log: Logger = Logger.getLogger("agents_engine.agntcy.OasfRecord")

/**
 * `agents_engine/agntcy/OasfRecord.kt` — #4518 (PRD §12.6). Serialize an [Agent] to an
 * [OASF](https://github.com/agntcy/oasf) 1.0.0 record: AGNTCY's content-addressed discovery metadata,
 * the third exporter beside the A2A AgentCard (`toAgentCard()`, §12.5) and native `agent.json`
 * (`toAgentJson`, §12.2). The native typed agent stays the source of truth; this is a projection over
 * it, exactly parallel to `toAgentCard()`.
 *
 * **Skills are taxonomy entries, not free text.** Only skills annotated with `.oasf("path")` (see
 * [agents_engine.core.Skill.oasf]) become OASF `skills[]` — each resolved to its uid via
 * [OasfTaxonomy]. Un-annotated skills, and annotated paths not in the vendored taxonomy, are omitted
 * with a logged warning (they're still in `agent.json`, which carries free-form skills).
 *
 * **Determinism.** Keys are emitted in a fixed order and the same inputs serialize byte-identically.
 * Wall-clock fields ([createdAt]) and authorship ([authors], [locators]) are caller-supplied rather
 * than sampled, so the record stays reproducible (no hidden `now()`).
 *
 * Record signing (Sigstore/cosign over OCI) is external to the record JSON and is deferred (PRD §12.6).
 */
fun Agent<*, *>.toOasfRecord(
    version: String,
    authors: List<String> = emptyList(),
    locators: List<OasfLocator> = emptyList(),
    domains: List<String> = emptyList(),
    description: String? = null,
    createdAt: String? = null,
    annotations: Map<String, String> = emptyMap(),
): String {
    val skillRecords = skills.values
        .sortedBy { it.name }
        .mapNotNull { skill ->
            val path = skill.oasfPath
            if (path == null) {
                log.warning("OASF: skill \"${skill.name}\" has no .oasf(path) — omitted from OASF skills[].")
                return@mapNotNull null
            }
            val uid = OasfTaxonomy.skillUid(path)
            if (uid == null) {
                log.warning("OASF: skill \"${skill.name}\" path \"$path\" not in vendored OASF taxonomy — omitted.")
                return@mapNotNull null
            }
            linkedMapOf<String, Any?>("name" to path, "id" to uid)
        }

    val domainRecords = domains
        .sorted()
        .mapNotNull { path ->
            val uid = OasfTaxonomy.domainUid(path)
            if (uid == null) {
                log.warning("OASF: domain \"$path\" is not in the vendored OASF taxonomy — omitted.")
                return@mapNotNull null
            }
            linkedMapOf<String, Any?>("name" to path, "id" to uid)
        }

    val record = LinkedHashMap<String, Any?>()
    record["name"] = name
    record["version"] = version
    record["schema_version"] = OasfTaxonomy.SCHEMA_VERSION
    if (description != null) record["description"] = description
    record["authors"] = authors
    if (createdAt != null) record["created_at"] = createdAt
    record["skills"] = skillRecords
    record["domains"] = domainRecords
    record["locators"] = locators.map { linkedMapOf<String, Any?>("type" to it.type, "urls" to it.urls) }
    record["modules"] = emptyList<Any?>()
    record["annotations"] = annotations

    return McpJson.encode(record)
}
