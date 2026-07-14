import com.google.protobuf.gradle.id

plugins {
    kotlin("jvm")
    id("com.google.protobuf") version "0.10.0"
}

group = "ai.deep-code"
version = rootProject.version

repositories {
    mavenCentral()
}

dependencyLocking {
    lockAllConfigurations()
}

configurations.all {
    resolutionStrategy {
        force(
            "org.bouncycastle:bcprov-jdk18on:1.85",
            "org.bouncycastle:bcpg-jdk18on:1.85",
            "org.bouncycastle:bcpkix-jdk18on:1.85",
            "org.bouncycastle:bcutil-jdk18on:1.85",
        )
    }
}

private val grpcVersion = "1.82.1"
private val protobufVersion = "4.35.1"
private val grpcKotlinVersion = "1.5.0"

dependencies {
    api(project(":"))

    // #4520 — generated gRPC stubs + runtime. Kept in this feature module so the (large) grpc/protobuf
    // dependency graph never reaches core.
    api("io.grpc:grpc-stub:$grpcVersion")
    api("io.grpc:grpc-protobuf:$grpcVersion")
    api("io.grpc:grpc-kotlin-stub:$grpcKotlinVersion")
    api("com.google.protobuf:protobuf-kotlin:$protobufVersion")
    // Canonical google.protobuf.Struct <-> JSON converter (the DIR record payload is a Struct).
    api("com.google.protobuf:protobuf-java-util:$protobufVersion")
    // grpc-kotlin streaming stubs are Flow-based — coroutines must be on the compile classpath.
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    runtimeOnly("io.grpc:grpc-netty-shaded:$grpcVersion")

    // javax.annotation.Generated, referenced by protoc-gen-grpc-java output (compile-time only).
    compileOnly("org.apache.tomcat:annotations-api:6.0.53")

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    // In-process gRPC server/channel for hermetic round-trip tests (no network, no real DIR daemon).
    testImplementation("io.grpc:grpc-inprocess:$grpcVersion")
}

protobuf {
    protoc { artifact = "com.google.protobuf:protoc:$protobufVersion" }
    plugins {
        id("grpc") { artifact = "io.grpc:protoc-gen-grpc-java:$grpcVersion" }
        id("grpckt") { artifact = "io.grpc:protoc-gen-grpc-kotlin:$grpcKotlinVersion:jdk8@jar" }
    }
    generateProtoTasks {
        all().forEach { task ->
            task.plugins {
                id("grpc")
                id("grpckt")
            }
            task.builtins { id("kotlin") }
        }
    }
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}
