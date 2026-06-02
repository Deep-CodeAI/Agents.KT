package agents_engine.manifest

data class ManifestVerificationResult(
    val findings: List<ManifestFinding>,
) {
    val ok: Boolean get() = findings.isEmpty()
}
