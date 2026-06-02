package agents_engine.core

/**
 * #2805 — bespoke YAML round-trip used ONLY for the policy snapshot
 * embedded in the permission manifest. NOT a general-purpose YAML parser:
 * it knows only the closed shape `parsePolicyMap` ↔ `toManifestYaml`
 * produces. Adopting SnakeYAML would be heavier and would re-open a
 * supply-chain surface (#1916 sister concern) for one internal use.
 *
 * Depth constants name the legal indent levels for that closed shape:
 * top-level keys, sub-sections, leaf scalars/list-headers, and (only on
 * `filesystem`) the leaf level under `read:` / `write:`.
 */
internal object ManifestYaml {
    // #2805 — named depth constants replace the literal `0/2/4/6` in
    // `when (indent)` so the shape is documented inline instead of mined
    // from the `+2 per level` indent convention by the next reader.
    private const val DEPTH_TOPLEVEL = 0
    private const val DEPTH_SECTION = 2
    private const val DEPTH_LEAF = 4
    private const val DEPTH_FILESYSTEM_LEAF = 6

    fun quote(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    fun parsePolicyMap(yaml: String): Map<String, Any?> {
        val root = linkedMapOf<String, Any?>()
        var section = ""
        var filesystemSide = ""
        var listTarget: MutableList<String>? = null

        yaml.lineSequence().forEach { raw ->
            val line = raw.trimEnd()
            if (line.isBlank()) return@forEach
            val indent = line.takeWhile { it == ' ' }.length
            val text = line.trim()
            if (text.startsWith("- ")) {
                listTarget?.add(unquote(text.removePrefix("- ").trim()))
                return@forEach
            }

            when (indent) {
                DEPTH_TOPLEVEL -> {
                    listTarget = null
                    if (text.startsWith("risk:")) {
                        root["risk"] = text.substringAfter(':').trim()
                    } else if (text.endsWith(":")) {
                        section = text.removeSuffix(":")
                        root[section] = linkedMapOf<String, Any?>()
                    }
                }
                DEPTH_SECTION -> {
                    listTarget = null
                    val sectionMap = root.getOrPutMap(section)
                    if (section == "filesystem" && text.endsWith(":")) {
                        filesystemSide = text.removeSuffix(":")
                        sectionMap[filesystemSide] = linkedMapOf<String, Any?>()
                    } else {
                        readScalarOrListHeader(sectionMap, text)?.let { listTarget = it }
                    }
                }
                DEPTH_LEAF -> {
                    val target = if (section == "filesystem") {
                        root.getOrPutMap(section).getOrPutMap(filesystemSide)
                    } else {
                        root.getOrPutMap(section)
                    }
                    readScalarOrListHeader(target, text)?.let { listTarget = it }
                }
                DEPTH_FILESYSTEM_LEAF -> {
                    val target = root.getOrPutMap(section).getOrPutMap(filesystemSide)
                    readScalarOrListHeader(target, text)?.let { listTarget = it }
                }
            }
        }
        return root
    }

    private fun readScalarOrListHeader(target: MutableMap<String, Any?>, text: String): MutableList<String>? {
        val key = text.substringBefore(':')
        val value = text.substringAfter(':', missingDelimiterValue = "").trim()
        return if (value.isEmpty()) {
            val list = mutableListOf<String>()
            target[key] = list
            list
        } else {
            target[key] = unquote(value)
            null
        }
    }

    private fun MutableMap<String, Any?>.getOrPutMap(key: String): MutableMap<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        return getOrPut(key) { linkedMapOf<String, Any?>() } as MutableMap<String, Any?>
    }

    private fun unquote(value: String): String {
        if (value.length < 2 || value.first() != '"' || value.last() != '"') return value
        val body = value.substring(1, value.length - 1)
        val out = StringBuilder()
        var i = 0
        while (i < body.length) {
            val ch = body[i++]
            if (ch == '\\' && i < body.length) {
                out.append(body[i++])
            } else {
                out.append(ch)
            }
        }
        return out.toString()
    }
}
