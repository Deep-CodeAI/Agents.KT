package agents_engine.agntcy

/**
 * `agents_engine/agntcy/OasfLocator.kt` — #4518 (PRD §12.6). An OASF record `locator`: where the
 * agent's artifact can be obtained. [type] is the OASF locator type (e.g. `"source_code"`,
 * `"docker_image"`, `"binary"`, `"helm_chart"`); [urls] are the addresses for that type. Serialized
 * verbatim into the OASF record's `locators[]` and (additively) into `agent.json`'s `spec.locators`.
 */
data class OasfLocator(
    val type: String,
    val urls: List<String>,
)
