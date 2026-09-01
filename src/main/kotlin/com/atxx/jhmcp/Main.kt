package com.atxx.jhmcp

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("jhmcp.Main")

private data class Config(
    val apkPath: String?,
    val maxSourceBytes: Int,
    val codeScanCap: Int,
    val decompileTimeoutMs: Long,
)

private fun parseArgs(args: Array<String>): Config {
    var apkPath: String? = null
    var maxSourceBytes = 60_000
    var codeScanCap = 0
    var decompileTimeoutMs = JadxSession.DEFAULT_DECOMPILE_TIMEOUT_MS
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--apk" -> {
                require(i + 1 < args.size) { "--apk requires a value" }
                apkPath = args[i + 1]
                i += 2
            }
            "--max-source-bytes" -> {
                require(i + 1 < args.size) { "--max-source-bytes requires a value" }
                maxSourceBytes = args[i + 1].toInt()
                i += 2
            }
            "--max-scan" -> {
                require(i + 1 < args.size) { "--max-scan requires a value" }
                codeScanCap = args[i + 1].toInt()
                require(codeScanCap >= 0) { "--max-scan must be >= 0" }
                i += 2
            }
            "--decompile-timeout-ms" -> {
                require(i + 1 < args.size) { "--decompile-timeout-ms requires a value" }
                decompileTimeoutMs = args[i + 1].toLong()
                require(decompileTimeoutMs >= 1_000L) { "--decompile-timeout-ms must be >= 1000" }
                i += 2
            }
            "-h", "--help" -> {
                System.err.println(USAGE)
                kotlin.system.exitProcess(0)
            }
            else -> {
                System.err.println("Unknown argument: ${args[i]}\n$USAGE")
                kotlin.system.exitProcess(2)
            }
        }
    }
    return Config(apkPath, maxSourceBytes, codeScanCap, decompileTimeoutMs)
}

private const val USAGE = """
Usage: jadx-headless-mcp [--apk <path>] [--max-source-bytes N] [--max-scan N] [--decompile-timeout-ms N]

Headless JADX-based MCP server for Android APK static analysis.
Communicates via MCP over stdio.

Options:
  --apk <path>              optional: APK / DEX / JAR to load eagerly at startup.
                            If omitted, use the 'load_apk' tool to load on demand.
  --max-source-bytes <n>    max bytes per source response (default 60000; per-call max_bytes overrides)
                            NOTE: truncation runs AFTER jadx finishes materializing the class —
                            it does NOT stop a hung decompile early. Use --decompile-timeout-ms for that.
  --max-scan <n>            override default cap on classes scanned by search_classes_by_keyword
                            'code' scope (0 = use built-in tiered defaults: 20000 with package,
                            5000 without). Per-call max_scan still overrides this.
  --decompile-timeout-ms <n> hard wall-clock budget per get_class_source / get_smali_of_class /
                            get_method_by_name / code-search class (default 90000). Prevents one
                            fat obfuscated class from blocking the MCP process for hours.
  -h, --help                show this help
"""

fun main(args: Array<String>) {
    // Some logging libraries write an "initializing..." banner to System.out, which would
    // corrupt MCP's JSON-RPC stdout stream. Capture the real stdout for the transport and
    // route everything else to stderr.
    val realOut = System.out
    System.setOut(System.err)

    val cfg = parseArgs(args)
    val holder = SessionHolder(cfg.maxSourceBytes, cfg.codeScanCap, cfg.decompileTimeoutMs)
    Runtime.getRuntime().addShutdownHook(Thread {
        runCatching { runBlocking { holder.unload() } }
    })

    val server = Server(
        serverInfo = Implementation(name = "jadx-headless-mcp", version = BuildInfo.VERSION),
        options = ServerOptions(
            capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = null))
        )
    )
    registerTools(server, holder)

    runBlocking {
        if (cfg.apkPath != null) {
            runCatching { holder.load(cfg.apkPath) }
                .onFailure { System.err.println("[jhmcp] eager load failed: ${it.message}") }
        }
        val transport = StdioServerTransport(
            System.`in`.asSource().buffered(),
            realOut.asSink().buffered()
        ) {}
        val done = Job()
        server.onClose { done.complete() }
        server.createSession(transport)
        done.join()
    }
}

/** Wire up all ~26 MCP tools, grouped by concern into per-topic registration files. */
private fun registerTools(server: Server, holder: SessionHolder) {
    server.registerSessionTools(holder)
    server.registerManifestTools(holder)
    server.registerClassTools(holder)
    server.registerMethodTools(holder)
    server.registerSearchTools(holder)
    server.registerXrefTools(holder)
    server.registerResourceTools(holder)
}
