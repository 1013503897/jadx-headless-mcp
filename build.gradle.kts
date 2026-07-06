plugins {
    kotlin("jvm") version "2.4.0"
    application
    id("com.gradleup.shadow") version "9.5.1"
}

group = "com.atxx"
version = "0.1.0"

repositories {
    mavenCentral()
    google()
}

val jadxVersion = "1.5.5"
val mcpKotlinSdkVersion = "0.13.0"
val slf4jVersion = "2.0.18"

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

    implementation("org.slf4j:slf4j-api:$slf4jVersion")
    implementation("org.slf4j:slf4j-simple:$slf4jVersion")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
}

kotlin {
    jvmToolchain(17)
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
}
