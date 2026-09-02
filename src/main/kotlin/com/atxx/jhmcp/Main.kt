package com.atxx.jhmcp

import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import org.slf4j.LoggerFactory
import java.io.PrintStream

private val log = LoggerFactory.getLogger("jhmcp.Main")

private enum class TransportMode { STDIO, HTTP }

private data class Config(
    val apkPath: String?,
    val maxSourceBytes: Int,
    val codeScanCap: Int,
    val decompileTimeoutMs: Long,
    val transport: TransportMode,
    val host: String,
    val port: Int,
    val path: String,
    val allowedHosts: List<String>,
    val dnsRebindingProtection: Boolean,
)

private fun parseArgs(args: Array<String>): Config {
    var apkPath: String? = null
    var maxSourceBytes = 60_000
    var codeScanCap = 0
    var decompileTimeoutMs = JadxSession.DEFAULT_DECOMPILE_TIMEOUT_MS
    var transport = TransportMode.STDIO
    var host = "127.0.0.1"
    var port = 8080
    var path = "/mcp"
    val allowedHosts = mutableListOf<String>()
    var dnsRebindingProtection = true
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
            "--transport" -> {
                require(i + 1 < args.size) { "--transport requires a value (stdio|http)" }
                transport = when (args[i + 1].lowercase()) {
                    "stdio" -> TransportMode.STDIO
                    "http", "streamable-http" -> TransportMode.HTTP
                    else -> {
                        System.err.println("Unknown --transport '${args[i + 1]}' (expected: stdio|http)\n$USAGE")
                        kotlin.system.exitProcess(2)
                    }
                }
                i += 2
            }
            "--host" -> {
                require(i + 1 < args.size) { "--host requires a value" }
                host = args[i + 1]
                i += 2
            }
            "--port" -> {
                require(i + 1 < args.size) { "--port requires a value" }
                port = args[i + 1].toInt()
                require(port in 1..65535) { "--port must be in 1..65535" }
                i += 2
            }
            "--path" -> {
                require(i + 1 < args.size) { "--path requires a value" }
                path = args[i + 1]
                require(path.startsWith("/")) { "--path must start with '/'" }
                i += 2
            }
            "--allowed-host" -> {
                require(i + 1 < args.size) { "--allowed-host requires a value" }
                allowedHosts.add(args[i + 1])
                i += 2
            }
            "--no-dns-rebinding-protection" -> {
                dnsRebindingProtection = false
                i += 1
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
    return Config(
        apkPath, maxSourceBytes, codeScanCap, decompileTimeoutMs,
        transport, host, port, path, allowedHosts.toList(), dnsRebindingProtection,
    )
}

private const val USAGE = """
Usage: jadx-headless-mcp [--transport stdio|http] [--apk <path>] [options]

Headless JADX-based MCP server for Android APK static analysis.
Speaks MCP over stdio (default) or Streamable HTTP (--transport http) for remote clients.

Transport:
  --transport <stdio|http>  MCP transport (default: stdio). 'http' serves the Streamable-HTTP
                            transport so a remote MCP client (a different machine) can drive
                            this server. NOTE: 'load_apk' paths are always resolved on THIS
                            host — the APK must live on the machine running jadx.
  --host <addr>             [http] bind address (default: 127.0.0.1; use 0.0.0.0 to accept
                            connections from other machines)
  --port <n>                [http] listen port (default: 8080)
  --path <p>                [http] endpoint path (default: /mcp)
  --allowed-host <h>        [http] extra Host header value accepted by the DNS-rebinding check
                            (repeatable; loopback hosts are always allowed). Pass the hostname/IP
                            remote clients use to reach this server.
  --no-dns-rebinding-protection
                            [http] disable Host/Origin validation (trusted networks / behind a
                            reverse proxy only)

Analysis:
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
    // corrupt MCP's JSON-RPC stdout stream (stdio transport). Capture the real stdout for the
    // transport and route everything else to stderr. Harmless in HTTP mode (where stdout is unused).
    val realOut = System.out
    System.setOut(System.err)

    val cfg = parseArgs(args)
    val holder = SessionHolder(cfg.maxSourceBytes, cfg.codeScanCap, cfg.decompileTimeoutMs)
    Runtime.getRuntime().addShutdownHook(Thread {
        runCatching { runBlocking { holder.unload() } }
    })

    // Build a fully-wired MCP server bound to the shared holder. Called once for stdio, and once
    // per MCP session for HTTP — every instance shares the single holder, so the loaded APK
    // persists across sessions (this stays "one APK per process").
    fun buildServer(): Server {
        val server = Server(
            serverInfo = Implementation(name = "jadx-headless-mcp", version = BuildInfo.VERSION),
            options = ServerOptions(
                capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = null))
            )
        )
        registerTools(server, holder)
        return server
    }

    if (cfg.apkPath != null) {
        runBlocking {
            runCatching { holder.load(cfg.apkPath) }
                .onFailure { System.err.println("[jhmcp] eager load failed: ${it.message}") }
        }
    }

    when (cfg.transport) {
        TransportMode.STDIO -> runStdio(::buildServer, realOut)
        TransportMode.HTTP -> runHttp(cfg, ::buildServer)
    }
}

/** Classic single-client stdio transport (unchanged default). Blocks until the client disconnects. */
private fun runStdio(buildServer: () -> Server, realOut: PrintStream) = runBlocking {
    val server = buildServer()
    val transport = StdioServerTransport(
        System.`in`.asSource().buffered(),
        realOut.asSink().buffered()
    ) {}
    val done = Job()
    server.onClose { done.complete() }
    server.createSession(transport)
    done.join()
}

/**
 * Streamable-HTTP transport for remote clients (--transport http). Serves POST/GET/DELETE at
 * [Config.path] on a Ktor CIO engine. The APK still lives on THIS host; a remote client only
 * sends `load_apk` with a path valid here.
 */
private fun runHttp(cfg: Config, buildServer: () -> Server) {
    // With DNS-rebinding protection on, the SDK validates the request Host header against an
    // allow-list. When the user adds custom hosts, merge them with the loopback defaults so
    // localhost keeps working; when they add none, pass null and let the SDK apply its defaults.
    val allowedHosts: List<String>? = when {
        !cfg.dnsRebindingProtection -> null
        cfg.allowedHosts.isEmpty() -> null
        else -> (listOf("localhost", "127.0.0.1", "[::1]") + cfg.allowedHosts).distinct()
    }
    System.err.println(
        "[jhmcp] Streamable-HTTP MCP endpoint: http://${cfg.host}:${cfg.port}${cfg.path} " +
            "(dns-rebinding-protection=${cfg.dnsRebindingProtection})"
    )
    if (cfg.dnsRebindingProtection && (cfg.host == "0.0.0.0" || cfg.host == "::")) {
        System.err.println(
            "[jhmcp] NOTE: bound to a wildcard address with DNS-rebinding protection ON. Remote " +
                "clients are rejected unless their Host header is allow-listed — pass --allowed-host " +
                "<addr> for each hostname/IP clients use, or --no-dns-rebinding-protection on a " +
                "trusted network."
        )
    }
    embeddedServer(CIO, host = cfg.host, port = cfg.port) {
        mcpStreamableHttp(
            path = cfg.path,
            enableDnsRebindingProtection = cfg.dnsRebindingProtection,
            allowedHosts = allowedHosts,
        ) {
            buildServer()
        }
    }.start(wait = true)
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
