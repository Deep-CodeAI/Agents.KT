package agents_engine.manifest

data class ManifestVerificationResult(
    val findings: List<ManifestFinding>,
) {
    // #3875 — "info" findings (e.g. manifest format version changes) do not
    // fail verification; every widening/risk finding is severity "high".
    val ok: Boolean get() = findings.none { it.severity != "info" }
}
