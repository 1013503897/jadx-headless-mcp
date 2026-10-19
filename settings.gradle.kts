plugins {
    // Auto-provision the jvmToolchain(17) JDK when the host only has a newer runtime.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "jadx-headless-mcp"
