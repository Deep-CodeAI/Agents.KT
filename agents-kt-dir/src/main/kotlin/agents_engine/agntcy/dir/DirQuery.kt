package agents_engine.agntcy.dir

/**
 * `agents_engine/agntcy/dir/DirQuery.kt` — #4520 (PRD §12.6). One DIR search predicate: match records whose
 * [type] facet equals [value] (e.g. `DirQuery(DirQueryType.SKILL_NAME, "agent_orchestration/multi_agent_planning")`).
 * Multiple queries passed together are AND-combined by the directory.
 */
data class DirQuery(
    val type: DirQueryType,
    val value: String,
)
