package agents_engine.agntcy

/**
 * `agents_engine/agntcy/OasfValidationException.kt` — #4519 (PRD §12.6). Thrown by [fromOasfRecord] when a
 * record is structurally invalid or internally inconsistent: a missing required field (name /
 * schema_version), an unknown schema *major*, a skill/domain entry with neither id nor name
 * (`at_least_one`), or a taxonomy id that contradicts its name. Fail-closed — recommended-but-missing
 * fields only warn; anything that makes the record untrustworthy throws.
 */
class OasfValidationException(message: String) : RuntimeException(message)
