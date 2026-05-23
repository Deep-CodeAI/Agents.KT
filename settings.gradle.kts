rootProject.name = "agents-kt"

include(":agents-kt-ksp")
include(":agents-kt-observability")
include(":agents-kt-otel")
include(":agents-kt-langsmith")
include(":agents-kt-manifest")
// #1718: consumer-shaped smoke test whose classpath explicitly excludes
// kotlin-reflect. Asserts the contract that v0.4.6 promises.
include(":agents-kt-no-reflect-test")
