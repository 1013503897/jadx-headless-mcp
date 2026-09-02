plugins {
    kotlin("jvm") version "2.4.10"
    application
    id("com.gradleup.shadow") version "9.6.1"
}

group = "com.atxx"
version = "0.7.0"

repositories {
    mavenCentral()
    google()
}

val jadxVersion = "1.5.6"
val mcpKotlinSdkVersion = "0.15.0"
// Must match the Ktor version the MCP SDK is built against (see kotlin-sdk-server .module metadata).
val ktorVersion = "3.5.1"
val slf4jVersion = "2.0.18"
val junitVersion = "5.11.3"

dependencies {
    implementation("io.github.skylot:jadx-core:$jadxVersion")
    implementation("io.github.skylot:jadx-dex-input:$jadxVersion")
    implementation("io.github.skylot:jadx-java-input:$jadxVersion")
    implementation("io.github.skylot:jadx-java-convert:$jadxVersion")
    implementation("io.github.skylot:jadx-smali-input:$jadxVersion")
    implementation("io.github.skylot:jadx-raung-input:$jadxVersion")
    implementation("io.github.skylot:jadx-xapk-input:$jadxVersion")
    implementation("io.github.skylot:jadx-kotlin-metadata:$jadxVersion")

    implementation("io.modelcontextprotocol:kotlin-sdk:$mcpKotlinSdkVersion")

    // Ktor engine for the Streamable-HTTP transport (--transport http). The MCP SDK provides the
    // mcpStreamableHttp() route and auto-installs SSE + ContentNegotiation, but needs a server
    // engine to actually listen; CIO is the lightweight pure-Kotlin engine.
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-cio:$ktorVersion")

    implementation("org.slf4j:slf4j-api:$slf4jVersion")
    implementation("org.slf4j:slf4j-simple:$slf4jVersion")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:$junitVersion")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Single source of truth for the version string: generate a BuildInfo.kt from the Gradle
// `version` above so Main.kt no longer hard-codes it (previously two places to keep in sync).
val generateBuildInfo by tasks.registering {
    val outDir = layout.buildDirectory.dir("generated/buildinfo/kotlin")
    val ver = version.toString()
    inputs.property("version", ver)
    outputs.dir(outDir)
    doLast {
        val f = outDir.get().file("com/atxx/jhmcp/BuildInfo.kt").asFile
        f.parentFile.mkdirs()
        f.writeText(
            "package com.atxx.jhmcp\n\n" +
                "/** Generated from the Gradle `version` — do not edit by hand. */\n" +
                "internal object BuildInfo {\n" +
                "    const val VERSION = \"$ver\"\n" +
                "}\n"
        )
    }
}

kotlin {
    jvmToolchain(17)
    sourceSets.named("main") {
        kotlin.srcDir(generateBuildInfo)
    }
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "skipped", "failed") }
}

application {
    mainClass.set("com.atxx.jhmcp.MainKt")
    applicationName = "jadx-headless-mcp"
    applicationDefaultJvmArgs = listOf(
        "-Xms128M",
        "-XX:MaxRAMPercentage=60.0",
        "-Dorg.slf4j.simpleLogger.logFile=System.err",
        "-Dorg.slf4j.simpleLogger.defaultLogLevel=warn",
        "-Dorg.slf4j.simpleLogger.showDateTime=false",
        "-Dorg.slf4j.simpleLogger.showThreadName=false",
        "-Dorg.slf4j.simpleLogger.levelInBrackets=true"
    )
}

// Windows 长 classpath 修复:CMD `set CLASSPATH=` 有 8191 字符上限,展开后的显式 jar 列表会超限
// 导致 "The input line is too long" → java 起不来 → MCP 断连。改用目录通配 lib\*(java 自行展开)。
tasks.named<CreateStartScripts>("startScripts") {
    doLast {
        windowsScript.writeText(
            windowsScript.readText().replace(Regex("(?m)^set CLASSPATH=.*$")) { "set CLASSPATH=%APP_HOME%\\lib\\*" }
        )
    }
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}

tasks.register<JavaExec>("bench") {
    group = "verification"
    description = "Run measurement harness against an APK. Usage: ./gradlew bench --args=\"/path/to.apk\""
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.atxx.jhmcp.BenchKt")
}

tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    archiveClassifier.set("all")
    mergeServiceFiles()
    // ShadowJar defaults to DuplicatesStrategy.EXCLUDE, which silently drops the duplicate
    // META-INF/services/jadx.api.plugins.JadxPlugin files from jadx-dex-input, jadx-java-input,
    // jadx-java-convert, jadx-smali-input, jadx-raung-input and jadx-xapk-input BEFORE
    // mergeServiceFiles() can merge them. The released fat jar then registers only
    // KotlinMetadataPlugin, so no dex/java/smali input plugin is loaded at runtime: every APK
    // resolves to just the resources-derived R class (1 class) and bare dex files to 0 classes.
    // INCLUDE routes every copy into the ServiceFileTransformer, which concatenates the entries.
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
}
