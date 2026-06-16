package agents_engine.agntcy.dir

/**
 * `agents_engine/agntcy/dir/DirQueryType.kt` — #4520 (PRD §12.6). The facet a DIR search query matches on,
 * mirroring the OASF record fields DIR indexes (`agntcy.dir.search.v1.RecordQueryType`). Used with
 * [DirQuery] for `DirClient.searchRecords` / `searchCids` (local content search). The coarser network
 * [DirClient.routeSearch] accepts the skill/locator/domain/module subset (others are rejected as
 * not-routable).
 */
enum class DirQueryType {
    NAME,
    VERSION,
    SKILL_ID,
    SKILL_NAME,
    LOCATOR,
    MODULE_NAME,
    DOMAIN_ID,
    DOMAIN_NAME,
    CREATED_AT,
    AUTHOR,
    SCHEMA_VERSION,
    MODULE_ID,
    VERIFIED,
    TRUSTED,
    ANNOTATION,
}
