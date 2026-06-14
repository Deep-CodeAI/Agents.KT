rootProject.name = "agents-kt"

include(":agents-kt-ksp")
include(":agents-kt-observability")
include(":agents-kt-otel")
include(":agents-kt-langsmith")
include(":agents-kt-langfuse")
include(":agents-kt-manifest")
include(":agents-kt-rag")
include(":agents-kt-rag-langchain4j")
include(":agents-kt-rag-spring-ai")
// #1923: standalone CLI (manifest generate / inspect / verify) — the "externally"
// half of 0.7.0, so non-Gradle consumers (CI gates, ops, regulators) can produce and
// verify the deterministic permission manifest from a binary.
include(":agents-kt-cli")
// #2885 (epic #2882): custom detekt rules that gate tool executor bodies
// (ToolBodyForbiddenApis + the #2884 capability extractor). A static-analysis
// module — depends on detekt-api, not the runtime.
include(":agents-kt-detekt")
// #1718: consumer-shaped smoke test whose classpath explicitly excludes
// kotlin-reflect. Asserts the contract that v0.4.6 promises.
include(":agents-kt-no-reflect-test")
// #4505: in-process Whisper STT backend — the `WhisperBackend` native seam,
// a weights-free `WhisperModelResolver` (download + checksum at runtime, never
// bundled), and `WhisperJniSttClient`. The separate-module pattern for native
// modality backends; the whisper.cpp JNI lib is the consumer's to add.
include(":agents-kt-whisper-jni")
