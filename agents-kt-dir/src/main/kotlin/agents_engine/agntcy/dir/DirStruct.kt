package agents_engine.agntcy.dir

import com.google.protobuf.Struct
import com.google.protobuf.util.JsonFormat

/**
 * `agents_engine/agntcy/dir/DirStruct.kt` — #4520 (PRD §12.6). Converts between an OASF record's JSON and the
 * `google.protobuf.Struct` that DIR's `Record.data` carries — DIR stores the record body as an opaque Struct,
 * so the JSON is the contract (no OASF protos needed). Uses protobuf's canonical [JsonFormat] so the
 * Struct↔JSON mapping matches what every other DIR SDK produces.
 *
 * Struct fields are unordered and numbers are doubles, so the round trip preserves the record's *content*,
 * not byte-for-byte key order. JsonFormat prints whole numbers without a trailing `.0`, so an OASF
 * `{"id":1003}` survives as `1003`.
 */
internal fun jsonToStruct(json: String): Struct {
    val builder = Struct.newBuilder()
    JsonFormat.parser().merge(json, builder)
    return builder.build()
}

internal fun structToJson(struct: Struct): String =
    JsonFormat.printer().omittingInsignificantWhitespace().print(struct)
