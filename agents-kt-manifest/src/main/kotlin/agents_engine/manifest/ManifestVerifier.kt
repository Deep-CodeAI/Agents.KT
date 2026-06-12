package agents_engine.manifest

internal object ManifestVerifier {
    fun verify(current: PermissionManifest, baseline: PermissionManifest): ManifestVerificationResult {
        val findings = mutableListOf<ManifestFinding>()

        // #3875 — manifest format evolved (v2 adds the schemas section). A
        // version difference is informational: older baselines still verify.
        val currentVersion = current.toMap()["agentsKtManifestVersion"]
        val baselineVersion = baseline.toMap()["agentsKtManifestVersion"]
        if (currentVersion != baselineVersion) {
            findings += ManifestFinding(
                code = "manifest.version.changed",
                severity = "info",
                path = "agentsKtManifestVersion",
                message = "Manifest format version changed ($baselineVersion -> $currentVersion); " +
                    "sections added by newer versions are not compared against this baseline.",
            )
        }
        val currentTools = current.toolsByKey()
        val baselineTools = baseline.toolsByKey()

        currentTools.forEach { (key, currentTool) ->
            val name = currentTool["name"]?.toString() ?: key
            val baselineTool = baselineTools[key]
            val currentRisk = currentTool.riskValue()
            if (baselineTool == null) {
                if (currentRisk >= RISK_HIGH) {
                    findings += ManifestFinding(
                        code = "tool.added.high-risk",
                        severity = "high",
                        path = "tools.$key",
                        message = "New high-risk tool \"$name\" was added.",
                    )
                }
                return@forEach
            }

            if (currentRisk > baselineTool.riskValue() && currentRisk >= RISK_HIGH) {
                findings += ManifestFinding(
                    code = "tool.risk.increased",
                    severity = "high",
                    path = "tools.$key.risk",
                    message = "Tool \"$name\" risk increased from ${baselineTool["risk"]} to ${currentTool["risk"]}.",
                )
            }

            execWidening(currentTool, baselineTool)?.let { detail ->
                findings += ManifestFinding(
                    code = "tool.exec.widened",
                    severity = "high",
                    path = "tools.$key.policy.exec",
                    message = "Tool \"$name\" exec access widened ($detail).",
                )
            }

            networkWidening(currentTool, baselineTool)?.let { detail ->
                findings += ManifestFinding(
                    code = "tool.network.widened",
                    severity = "high",
                    path = "tools.$key.policy.network",
                    message = "Tool \"$name\" network access widened ($detail).",
                )
            }

            filesystemWidening(currentTool, baselineTool, "write")?.let { detail ->
                findings += ManifestFinding(
                    code = "tool.filesystem.write.widened",
                    severity = "high",
                    path = "tools.$key.policy.filesystem.write",
                    message = "Tool \"$name\" filesystem write access widened ($detail).",
                )
            }

            filesystemWidening(currentTool, baselineTool, "read")?.let { detail ->
                findings += ManifestFinding(
                    code = "tool.filesystem.read.widened",
                    severity = "medium",
                    path = "tools.$key.policy.filesystem.read",
                    message = "Tool \"$name\" filesystem read access widened ($detail).",
                )
            }

            environmentWidening(currentTool, baselineTool)?.let { detail ->
                findings += ManifestFinding(
                    code = "tool.environment.widened",
                    severity = "medium",
                    path = "tools.$key.policy.environment",
                    message = "Tool \"$name\" environment access widened ($detail).",
                )
            }
        }

        return ManifestVerificationResult(findings)
    }

    private const val RISK_HIGH = 3

    /**
     * Tools keyed by `agentName.toolName`, so two agents with a same-named tool do
     * not collide. The old name-only `putIfAbsent` silently dropped the second tool,
     * hiding per-agent policy differences in a multi-agent manifest.
     */
    @Suppress("UNCHECKED_CAST")
    private fun PermissionManifest.toolsByKey(): Map<String, Map<String, Any?>> {
        val result = linkedMapOf<String, Map<String, Any?>>()
        val agents = toMap()["agents"] as? List<*> ?: return result
        agents.forEachIndexed { index, rawAgent ->
            val agent = rawAgent as? Map<*, *> ?: return@forEachIndexed
            val agentName = agent["name"]?.toString() ?: "agent$index"
            val tools = agent["tools"] as? List<*> ?: return@forEachIndexed
            tools.forEach { rawTool ->
                val tool = rawTool as? Map<*, *> ?: return@forEach
                val name = tool["name"]?.toString() ?: return@forEach
                result["$agentName.$name"] = tool as Map<String, Any?>
            }
        }
        return result
    }

    private fun Map<String, Any?>.riskValue(): Int =
        when (this["risk"]?.toString()?.lowercase()) {
            "critical" -> 4
            "high" -> 3
            "medium" -> 2
            "low" -> 1
            else -> 0
        }

    // #2887 — deny/unspecified (0) -> allow (1); any rank increase is a widening.
    private fun execRank(mode: String): Int = if (mode == "allow") 1 else 0

    private fun execWidening(current: Map<String, Any?>, baseline: Map<String, Any?>): String? {
        val cm = current.policySection("exec")["mode"]?.toString()?.lowercase() ?: "unspecified"
        val bm = baseline.policySection("exec")["mode"]?.toString()?.lowercase() ?: "unspecified"
        return if (execRank(cm) > execRank(bm)) "mode $bm -> $cm" else null
    }

    private fun networkRank(mode: String): Int =
        when (mode) {
            "allowall" -> 2
            "hosts" -> 1
            else -> 0
        }

    /**
     * Network widening = the current policy reaches something the baseline did not: a
     * stricter→looser mode jump (denyAll/unspecified → hosts → allowAll), or — within
     * `hosts` mode — a host the baseline did not list. Compares the actual host **set**,
     * not a coarse score, so `["api.internal"] → ["api.internal", "evil.example"]` is caught.
     */
    private fun networkWidening(current: Map<String, Any?>, baseline: Map<String, Any?>): String? {
        val cm = current.policySection("network")["mode"]?.toString()?.lowercase() ?: "unspecified"
        val bm = baseline.policySection("network")["mode"]?.toString()?.lowercase() ?: "unspecified"
        if (networkRank(cm) > networkRank(bm)) return "mode $bm -> $cm"
        if (cm == "hosts" && bm == "hosts") {
            val added = stringList(current.policySection("network")["hosts"]) -
                stringList(baseline.policySection("network")["hosts"]).toSet()
            if (added.isNotEmpty()) return "added host(s) $added"
        }
        return null
    }

    // Filesystem widening = the current policy grants a path glob the baseline did not:
    // a non-glob baseline (none / writeNone / unspecified) gaining globs, or a glob the
    // baseline's set did not contain. Compares the actual glob SET (not a coarse count),
    // so replacing a narrow upload-folder glob with a root-level glob is caught. Pure
    // narrowing (only removing globs) is not flagged; semantic glob-coverage subset
    // checking is a later refinement, so an added glob string is conservatively treated
    // as added authority (flagged for review). (Slash-star globs avoided here: Kotlin
    // block comments nest, so a glob like the root wildcard would open a nested comment.)
    private fun filesystemWidening(current: Map<String, Any?>, baseline: Map<String, Any?>, side: String): String? {
        val cur = current.policySection("filesystem").mapValue(side)
        val base = baseline.policySection("filesystem").mapValue(side)
        val curMode = cur["mode"]?.toString()?.lowercase() ?: "unspecified"
        val baseMode = base["mode"]?.toString()?.lowercase() ?: "unspecified"
        val curGlobs = stringList(cur["globs"])
        val baseGlobs = stringList(base["globs"]).toSet()
        if (curMode == "globs" && baseMode != "globs" && curGlobs.isNotEmpty()) return "now grants $curGlobs"
        val added = curGlobs - baseGlobs
        if (added.isNotEmpty()) return "added glob(s) $added"
        return null
    }

    /**
     * Environment widening = the current policy exposes a variable the baseline did not:
     * a non-`vars` baseline gaining variables, or a variable name the baseline's set did
     * not contain. Compares the actual variable **set**.
     */
    private fun environmentWidening(current: Map<String, Any?>, baseline: Map<String, Any?>): String? {
        val cur = current.policySection("environment")
        val base = baseline.policySection("environment")
        val curMode = cur["mode"]?.toString()?.lowercase() ?: "unspecified"
        val baseMode = base["mode"]?.toString()?.lowercase() ?: "unspecified"
        val curVars = stringList(cur["variables"])
        val baseVars = stringList(base["variables"]).toSet()
        if (curMode == "vars" && baseMode != "vars" && curVars.isNotEmpty()) return "now exposes $curVars"
        val added = curVars - baseVars
        if (added.isNotEmpty()) return "added variable(s) $added"
        return null
    }

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.policySection(name: String): Map<String, Any?> =
        ((this["policy"] as? Map<*, *>)?.get(name) as? Map<String, Any?>) ?: emptyMap()

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.mapValue(name: String): Map<String, Any?> =
        (this[name] as? Map<String, Any?>) ?: emptyMap()

    private fun stringList(value: Any?): List<String> =
        when (value) {
            is Iterable<*> -> value.map { it.toString() }
            is Array<*> -> value.map { it.toString() }
            null -> emptyList()
            else -> listOf(value.toString())
        }
}
