plugins {
    kotlin("jvm")
}

group = "ai.deep-code"
version = rootProject.version

repositories {
    mavenCentral()
}

dependencyLocking {
    lockAllConfigurations()
}

dependencies {
    // detekt-api provides Rule / RuleSetProvider / the Kotlin PSI surface. compileOnly:
    // consumers get it transitively from the detekt plugin at analysis time.
    compileOnly("io.gitlab.arturbosch.detekt:detekt-api:1.23.7")
    testImplementation(kotlin("test"))
    testImplementation("io.gitlab.arturbosch.detekt:detekt-api:1.23.7")
    testImplementation("io.gitlab.arturbosch.detekt:detekt-test:1.23.7")
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}
