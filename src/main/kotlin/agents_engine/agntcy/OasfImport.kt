package agents_engine.agntcy

import agents_engine.generation.LenientJsonParser
import java.util.logging.Logger

private val importLog: Logger = Logger.getLogger("agents_engine.agntcy.OasfImport")

/**
 * `agents_engine/agntcy/OasfImport.kt` — #4519 (PRD §12.6). Parse + validate an OASF 1.0.0 record JSON into
 * the typed [OasfRecord] — the read side of [toOasfRecord]. Fail-closed on anything that makes the record
 * untrustworthy (throws [OasfValidationException]); merely-recommended-but-missing fields only warn, so a
 * record this library exported round-trips cleanly.
 *
 * Validation:
 * - **Required:** `name`, `schema_version`. Missing → reject.
 * - **Schema version:** major must be `1` (the pinned generation); a different major (e.g. `2.0.0`) is
 *   rejected, a different *minor* warns. (Unknown majors may have rules we don't implement.)
 * - **Skills / domains:** each entry must satisfy OASF's `at_least_one: [id, name]`; with both present the
 *   id must match the vendored [OasfTaxonomy] for that name (exact path — no fuzzy matching upstream), else
 *   reject. With one present, the other is resolved from the taxonomy when known (else a warning; custom or
 *   newer-than-vendored paths are kept, not invented).
 * - **Recommended:** `version`, `authors`, `created_at`, `description`, `skills` — warn when absent.
 */
fun fromOasfRecord(json: String): OasfRecord {
    val root = LenientJsonParser.parse(json) as? Map<*, *>
        ?: throw OasfValidationException("OASF record is not a JSON object")

    val name = root.requiredString("name")
    val schemaVersion = root.requiredString("schema_version")
    validateSchemaVersion(schemaVersion)

    val version = (root["version"] as? String).also {
        if (it == null) importLog.warning("OASF record \"$name\" has no version (recommended).")
    }
    if (root["authors"].asStringList().isEmpty()) {
        importLog.warning("OASF record \"$name\" has no authors (recommended).")
    }

    return OasfRecord(
        name = name,
        version = version,
        schemaVersion = schemaVersion,
        description = root["description"] as? String,
        authors = root["authors"].asStringList(),
        createdAt = root["created_at"] as? String,
        skills = parseClassifications(root["skills"], "skill", OasfTaxonomy::skillUid, ::reverseSkill),
        domains = parseClassifications(root["domains"], "domain", OasfTaxonomy::domainUid, ::reverseDomain),
        locators = parseLocators(root["locators"]),
        annotations = (root["annotations"] as? Map<*, *>)
            ?.entries?.associate { (k, v) -> k.toString() to v.toString() } ?: emptyMap(),
    )
}

private fun validateSchemaVersion(schemaVersion: String) {
    val vendored = OasfTaxonomy.SCHEMA_VERSION
    if (schemaVersion.substringBefore('.') != vendored.substringBefore('.')) {
        throw OasfValidationException(
            "OASF record schema_version \"$schemaVersion\" has an unknown major; this build supports $vendored.",
        )
    }
    if (schemaVersion != vendored) {
        importLog.warning("OASF schema_version $schemaVersion differs from vendored $vendored.")
    }
}

private fun Map<*, *>.requiredString(key: String): String =
    (this[key] as? String)?.takeIf { it.isNotBlank() }
        ?: throw OasfValidationException("OASF record missing required field: $key")

private fun parseClassifications(
    raw: Any?,
    kind: String,
    forward: (String) -> Int?,
    reverse: (Int) -> String?,
): List<OasfClassification> {
    val list = raw as? List<*> ?: return emptyList()
    return list.map { entry ->
        val obj = entry as? Map<*, *>
            ?: throw OasfValidationException("OASF $kind entry is not an object: $entry")
        val name = obj["name"] as? String
        val id = (obj["id"] as? Number)?.toInt()
        resolveClassification(kind, name, id, forward, reverse)
    }
}

private fun resolveClassification(
    kind: String,
    name: String?,
    id: Int?,
    forward: (String) -> Int?,
    reverse: (Int) -> String?,
): OasfClassification = when {
    name == null && id == null ->
        throw OasfValidationException("OASF $kind entry has neither id nor name (at_least_one constraint)")

    name != null && id != null -> {
        val expected = forward(name)
        if (expected != null && expected != id) {
            throw OasfValidationException("OASF $kind \"$name\" declares id $id but the taxonomy uid is $expected")
        }
        OasfClassification(name, id)
    }

    name != null -> {
        val resolved = forward(name)
        if (resolved == null) importLog.warning("OASF $kind \"$name\" is not in the vendored taxonomy; id left null.")
        OasfClassification(name, resolved)
    }

    else -> {
        val resolvedName = reverse(id!!)
        if (resolvedName == null) importLog.warning("OASF $kind id $id not in the vendored taxonomy; name left null.")
        OasfClassification(resolvedName, id)
    }
}

private fun parseLocators(raw: Any?): List<OasfLocator> {
    val list = raw as? List<*> ?: return emptyList()
    return list.mapNotNull { entry ->
        val obj = entry as? Map<*, *> ?: return@mapNotNull null
        val type = obj["type"] as? String ?: return@mapNotNull null
        OasfLocator(type, obj["urls"].asStringList())
    }
}

private fun reverseSkill(id: Int): String? = OasfTaxonomy.skillEntries().entries.firstOrNull { it.value == id }?.key

private fun reverseDomain(id: Int): String? = OasfTaxonomy.domainEntries().entries.firstOrNull { it.value == id }?.key

private fun Any?.asStringList(): List<String> = (this as? List<*>)?.mapNotNull { it as? String } ?: emptyList()
