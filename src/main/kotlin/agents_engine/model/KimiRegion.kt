package agents_engine.model

/**
 * #4883 — Moonshot/Kimi runs two independent platforms with **non-interchangeable** keys: a key
 * issued by one is rejected by the other with `Invalid Authentication` (see [KimiClient.regionAwareError]).
 * Pick the region explicitly — `model { kimi("moonshot-v1-8k", region = KimiRegion.INTERNATIONAL) }` —
 * instead of passing a raw `baseUrl` string. The [baseUrl] is also usable directly:
 * `KimiClient(apiKey, model, baseUrl = KimiRegion.INTERNATIONAL.baseUrl)`.
 */
enum class KimiRegion(val baseUrl: String) {
    /** platform.moonshot.cn — the historical default ([KimiClient.CHINA_BASE_URL]). */
    CHINA(KimiClient.CHINA_BASE_URL),

    /** platform.moonshot.ai ([KimiClient.INTERNATIONAL_BASE_URL]). */
    INTERNATIONAL(KimiClient.INTERNATIONAL_BASE_URL),
}
