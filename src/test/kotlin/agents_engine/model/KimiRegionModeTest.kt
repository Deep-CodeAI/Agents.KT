package agents_engine.model

import kotlin.test.Test
import kotlin.test.assertEquals

// #4883 — Kimi/Moonshot runs two independent platforms; the region is a first-class, typed choice.
class KimiRegionModeTest {

    @Test
    fun `KimiRegion exposes both platform base URLs`() {
        assertEquals("https://api.moonshot.cn", KimiRegion.CHINA.baseUrl)
        assertEquals("https://api.moonshot.ai", KimiRegion.INTERNATIONAL.baseUrl)
    }

    @Test
    fun `region source values match the KimiClient constants`() {
        assertEquals(KimiClient.CHINA_BASE_URL, KimiRegion.CHINA.baseUrl)
        assertEquals(KimiClient.INTERNATIONAL_BASE_URL, KimiRegion.INTERNATIONAL.baseUrl)
    }

    @Test
    fun `kimi DSL without a region preserves the China default`() {
        val builder = ModelBuilder()
        builder.kimi("moonshot-v1-8k")
        assertEquals(KimiClient.DEFAULT_BASE_URL, builder.kimiBaseUrl, "no region => China default unchanged")
        assertEquals(ModelProvider.KIMI, builder.provider)
        assertEquals("moonshot-v1-8k", builder.name)
    }

    @Test
    fun `kimi DSL with INTERNATIONAL region selects the moonshot ai base URL`() {
        val builder = ModelBuilder()
        builder.kimi("moonshot-v1-8k", region = KimiRegion.INTERNATIONAL)
        assertEquals("https://api.moonshot.ai", builder.kimiBaseUrl)
    }

    @Test
    fun `kimi DSL with CHINA region selects the moonshot cn base URL`() {
        val builder = ModelBuilder()
        builder.kimi("moonshot-v1-128k", region = KimiRegion.CHINA)
        assertEquals("https://api.moonshot.cn", builder.kimiBaseUrl)
    }
}
