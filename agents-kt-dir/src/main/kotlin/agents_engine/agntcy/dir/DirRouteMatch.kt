package agents_engine.agntcy.dir

/**
 * `agents_engine/agntcy/dir/DirRouteMatch.kt` — #4520 (PRD §12.6). A hit from a network [DirClient.routeSearch]:
 * the matching record's [cid], the [peerId] of the DIR peer announcing it (empty for a local match), and the
 * directory's [matchScore] (how many of the supplied queries it satisfied).
 */
data class DirRouteMatch(
    val cid: String,
    val peerId: String,
    val matchScore: Int,
)
