package agents_engine.observability

import java.time.ZoneId
import java.time.ZoneOffset

sealed interface JsonlRotation {
    data object None : JsonlRotation

    data class Size(val maxBytes: Long) : JsonlRotation

    data class Daily(val zoneId: ZoneId = ZoneOffset.UTC) : JsonlRotation
}
