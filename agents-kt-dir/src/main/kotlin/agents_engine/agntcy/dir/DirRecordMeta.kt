package agents_engine.agntcy.dir

/**
 * `agents_engine/agntcy/dir/DirRecordMeta.kt` — #4520 (PRD §12.6). The lightweight metadata DIR resolves
 * for a record via `StoreService.Lookup` (the `RecordMeta` proto), without pulling its full payload.
 */
data class DirRecordMeta(
    val cid: String,
    val annotations: Map<String, String>,
    val schemaVersion: String,
    val createdAt: String,
)
