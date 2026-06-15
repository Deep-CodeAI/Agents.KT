package agents_engine.agntcy

import agents_engine.generation.LenientJsonParser
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.test.assertEquals

// #4518 slice 2 (PRD §12.6) — build-time cross-check: the vendored OASF taxonomy TSVs
// (resources/oasf/*.tsv) must match the hosted schema at schema.oasf.outshift.com. Catches drift
// between our vendored snapshot and upstream. Tagged live-cloud-api (runs in the default suite) but
// self-skips via assumeTrue when the schema server is unreachable, so an offline checkout stays green.
class OasfTaxonomyCrossCheckTest {

    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

    private fun fetch(kind: String): Map<String, Int>? {
        val req = HttpRequest.newBuilder()
            .uri(URI.create("https://schema.oasf.outshift.com/api/$kind"))
            .timeout(Duration.ofSeconds(15))
            .header("Accept", "application/json")
            .GET().build()
        val resp = runCatching { http.send(req, HttpResponse.BodyHandlers.ofString()) }.getOrNull() ?: return null
        if (resp.statusCode() != 200) return null
        val root = LenientJsonParser.parse(resp.body()) as? Map<*, *> ?: return null
        return root.values.mapNotNull { entryAny ->
            val entry = entryAny as? Map<*, *> ?: return@mapNotNull null
            val uid = (entry["uid"] as? Number)?.toInt() ?: return@mapNotNull null
            val enum = ((entry["attributes"] as? Map<*, *>)?.get("name") as? Map<*, *>)?.get("enum") as? Map<*, *>
            val path = enum?.keys?.singleOrNull() as? String ?: return@mapNotNull null
            path to uid
        }.toMap()
    }

    @Test
    @Tag("live-cloud-api")
    fun `vendored skills taxonomy matches the hosted OASF schema`() {
        val upstream = fetch("skills")
        assumeTrue(upstream != null, "skipping: schema.oasf.outshift.com/api/skills unreachable")
        assertEquals(upstream, OasfTaxonomy.skillEntries(), "vendored oasf/skills TSV drifted from upstream")
    }

    @Test
    @Tag("live-cloud-api")
    fun `vendored domains taxonomy matches the hosted OASF schema`() {
        val upstream = fetch("domains")
        assumeTrue(upstream != null, "skipping: schema.oasf.outshift.com/api/domains unreachable")
        assertEquals(upstream, OasfTaxonomy.domainEntries(), "vendored oasf/domains TSV drifted from upstream")
    }
}
