package agents_engine.model

/**
 * #2470 — base64-encoded image payload for vision input. The caller is
 * responsible for the encoding so the adapter can splat the bytes onto
 * the wire without re-encoding per provider. Wire MIME is closed via
 * the [ImagePart.WireMime] sealed type — `String` mime is intentionally
 * not accepted in the public ctor.
 *
 * Small, allocation-cheap. Equatability: `base64` is a `String`, so
 * structural equals/hashCode work — unlike `ByteArray`, which uses
 * identity equals (the trap we avoid by base64-encoding upfront).
 */
data class ImagePart(
    /** Base64-encoded image bytes, no `data:` URL prefix. Adapter formats per-provider. */
    val base64: String,
    /** Closed wire MIME — `image/png`, `image/jpeg`, `image/gif`, `image/webp`. */
    val wireMime: WireMime,
) {
    sealed interface WireMime {
        val value: String

        object Png : WireMime { override val value: String = "image/png" }
        object Jpeg : WireMime { override val value: String = "image/jpeg" }
        object Gif : WireMime { override val value: String = "image/gif" }
        object Webp : WireMime { override val value: String = "image/webp" }
    }
}
