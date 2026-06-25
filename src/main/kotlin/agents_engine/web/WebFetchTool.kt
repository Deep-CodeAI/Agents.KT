package agents_engine.web

import agents_engine.core.ToolRisk
import agents_engine.core.toolPolicy
import agents_engine.model.ToolDef
import java.net.URI

fun webFetchTool(
    backend: RenderBackend,
    options: WebFetchOptions = WebFetchOptions(),
): ToolDef {
    val normalized = options.normalized()
    normalized.validateFor(backend)
    return ToolDef(
        name = "web_fetch",
        description = "Fetch one bounded web URL and return page content or a blocked result.",
        argsType = WebFetchArgs::class,
        untrustedOutput = true,
        risk = ToolRisk.MEDIUM,
        policy = normalized.policy(),
    ) { args ->
        val url = normalized.fixedUrl ?: args["url"]?.toString().orEmpty()
        fetchWebContent(backend, normalized, url)
    }
}

private fun fetchWebContent(
    backend: RenderBackend,
    options: WebFetchOptions,
    url: String,
): WebContent {
    val requestUrl = parseHttpUrl(url)
        ?: return WebContent.Blocked(BlockReason.INVALID_URL, url.trim())
    if (!options.permits(requestUrl)) {
        return WebContent.Blocked(BlockReason.OFF_ALLOWLIST, url.trim())
    }

    val response = backend.fetch(WebRequest(requestUrl.toString(), options.captureScreenshot))
    return response.blockedBy(options) ?: WebContent.Page(
        url = response.finalUrl,
        contentType = response.contentType.orEmpty(),
        body = response.body,
        screenshot = response.screenshot,
    )
}

private fun WebFetchOptions.validateFor(backend: RenderBackend) {
    require(allowedHosts.isNotEmpty() || fixedUrl != null) {
        "web_fetch requires at least one allowed host or fixed URL"
    }
    require(maxBytes > 0) { "web_fetch maxBytes must be positive" }
    require(allowedContentTypes.isNotEmpty()) {
        "web_fetch requires at least one allowed content type"
    }
    if (captureScreenshot) {
        require(WebCapability.SCREENSHOT in backend.capabilities) {
            "web_fetch screenshot requested, but ${backend.name} backend does not advertise screenshot capability"
        }
    }
    fixedUrl?.let {
        require(parseHttpUrl(it) != null) { "web_fetch fixedUrl must be an absolute http(s) URL" }
    }
}

private fun WebFetchOptions.normalized(): WebFetchOptions =
    copy(
        allowedHosts = allowedHosts.map(::normalizeHost).distinct(),
        fixedUrl = fixedUrl?.trim()?.takeIf { it.isNotEmpty() },
        allowedContentTypes = allowedContentTypes.mapTo(linkedSetOf(), ::normalizeContentType),
    )

private fun WebFetchOptions.policy() = toolPolicy {
    risk = ToolRisk.Medium
    network {
        policyHosts().forEach { allow(it) }
    }
}

private fun WebFetchOptions.policyHosts(): List<String> =
    (allowedHosts + listOfNotNull(fixedUrl?.let { parseHttpUrl(it)?.host?.lowercase() })).distinct()

private fun WebFetchOptions.permits(uri: URI): Boolean {
    val fixed = fixedUrl?.let(::parseHttpUrl)
    if (fixed != null) {
        return fixed.normalizeForCompare() == uri.normalizeForCompare()
    }
    return uri.host.lowercase() in allowedHosts
}

private fun WebResponse.blockedBy(options: WebFetchOptions): WebContent.Blocked? {
    if (options.respectRobotsTxt && !robotsAllowed) {
        return WebContent.Blocked(BlockReason.ROBOTS_TXT, finalUrl)
    }
    if (byteSize > options.maxBytes) {
        return WebContent.Blocked(BlockReason.SIZE_LIMIT, finalUrl)
    }
    if (normalizeContentType(contentType.orEmpty()) !in options.allowedContentTypes) {
        return WebContent.Blocked(BlockReason.CONTENT_TYPE, finalUrl)
    }
    return null
}

internal fun normalizeContentType(value: String): String =
    value.substringBefore(';').trim().lowercase()

internal fun normalizeHost(value: String): String {
    val raw = value.trim()
    require(raw.isNotEmpty()) { "web_fetch host must not be blank" }
    val uri = parseHost(raw) ?: error("web_fetch host must be a host or absolute URL: $value")
    return uri.host.lowercase()
}

private fun parseHost(value: String): URI? =
    parseUri(if ("://" in value) value else "https://$value")?.takeIf { it.host != null }

private fun parseHttpUrl(value: String): URI? {
    val uri = parseUri(value.trim()) ?: return null
    val scheme = uri.scheme?.lowercase()
    return uri.takeIf { (scheme == "http" || scheme == "https") && it.host != null }
}

private fun parseUri(value: String): URI? =
    runCatching { URI(value) }.getOrNull()

private fun URI.normalizeForCompare(): String =
    normalize().toString()
