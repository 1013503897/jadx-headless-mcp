package com.atxx.jhmcp

import io.modelcontextprotocol.kotlin.sdk.server.ClientConnection
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory
import java.io.File

private val log = LoggerFactory.getLogger("jhmcp.Main")
private val json = Json { prettyPrint = false; encodeDefaults = true }

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
    // kotlin-logging 8.x writes its "initializing..." banner to System.out,
    // which would corrupt MCP's JSON-RPC stdout stream. Capture the real
    // stdout for the transport and route everything else to stderr.
    val realOut = System.out
    System.setOut(System.err)

    val cfg = parseArgs(args)
    val holder = SessionHolder(cfg.maxSourceBytes, cfg.codeScanCap, cfg.decompileTimeoutMs)
    Runtime.getRuntime().addShutdownHook(Thread {
        runCatching { runBlocking { holder.unload() } }
    })

    val server = Server(
        serverInfo = Implementation(name = "jadx-headless-mcp", version = "0.4.0"),
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

// ─── tool registration ──────────────────────────────────────────────────────

private fun registerTools(server: Server, holder: SessionHolder) {
    server.addTool(
        name = "status",
        description = "Report whether an APK is loaded and basic info (path, class count, resource count, load duration).",
        inputSchema = ToolSchema(properties = buildJsonObject {})
    ) { _: CallToolRequest ->
        val snap = holder.snapshot()
        okJson(buildJsonObject {
            put("state", snap.state)
            snap.apkPath?.let { put("apk_path", it) }
            snap.classCount?.let { put("class_count", it) }
            snap.resourceCount?.let { put("resource_count", it) }
            snap.loadDurationMs?.let { put("load_duration_ms", it) }
            snap.loadedAtEpochMs?.let { put("loaded_at_epoch_ms", it) }
            snap.decompileTimeoutMs?.let { put("decompile_timeout_ms", it) }
        })
    }

    server.addTool(
        name = "load_apk",
        description = "Load an APK / DEX / JAR. Replaces any currently-loaded file. Blocks until indexing completes (typically a few seconds to a minute depending on size).",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("path") { put("type", "string"); put("description", "Absolute path to the APK / DEX / JAR.") }
            },
            required = listOf("path")
        )
    ) { req: CallToolRequest ->
        val path = req.arguments.strArg("path") ?: return@addTool errorResult("path is required")
        runCatching { holder.load(path) }.fold(
            onSuccess = { r ->
                okJson(buildJsonObject {
                    put("state", "LOADED")
                    put("apk_path", r.apkPath)
                    put("class_count", r.classCount)
                    put("resource_count", r.resourceCount)
                    put("load_duration_ms", r.loadDurationMs)
                    put("decompile_timeout_ms", r.decompileTimeoutMs)
                })
            },
            onFailure = { e -> errorResult("load failed: ${e.message}") }
        )
    }

    server.addTool(
        name = "unload_apk",
        description = "Release the currently-loaded APK and free memory. No-op if nothing is loaded.",
        inputSchema = ToolSchema(properties = buildJsonObject {})
    ) { _: CallToolRequest ->
        val wasLoaded = holder.unload()
        okJson(buildJsonObject {
            put("state", "EMPTY")
            put("was_loaded", wasLoaded)
        })
    }

    server.addTool(
        name = "get_app_info",
        description = "Return package name, version, minSdk/targetSdk, and permissions parsed from AndroidManifest.xml.",
        inputSchema = ToolSchema(properties = buildJsonObject {})
    ) { _: CallToolRequest ->
        val s = holder.current() ?: return@addTool noApkLoaded()
        val xml = s.manifestText
        if (xml == null) errorResult("AndroidManifest.xml not found")
        else okJson(parseManifestSummary(xml))
    }

    server.addTool(
        name = "get_android_manifest",
        description = "Return decoded AndroidManifest.xml. Use section=permissions|activities|services|providers|receivers|application to slice (much cheaper than the full manifest, which can be 50KB+). max_bytes truncates the result.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("section") {
                    put("type", "string")
                    put("description", "One of: permissions, activities, services, providers, receivers, application. Omit for full manifest.")
                }
                putJsonObject("max_bytes") { put("type", "integer"); put("description", "Per-call truncation; overrides server default.") }
            }
        )
    ) { req: CallToolRequest ->
        val s = holder.current() ?: return@addTool noApkLoaded()
        val xml = s.manifestText ?: return@addTool errorResult("AndroidManifest.xml not found")
        val section = req.arguments.strArg("section")?.lowercase()
        val cap = req.arguments.intArg("max_bytes") ?: s.maxSourceBytes
        val sliced = if (section.isNullOrBlank()) xml else sliceManifest(xml, section)
            ?: return@addTool errorResult("unknown section: $section (expected permissions|activities|services|providers|receivers|application)")
        textResult(truncate(sliced, cap))
    }

    server.addTool(
        name = "get_main_activity_class",
        description = "Return the FQN of the LAUNCHER activity (action MAIN + category LAUNCHER).",
        inputSchema = ToolSchema(properties = buildJsonObject {})
    ) { _: CallToolRequest ->
        val s = holder.current() ?: return@addTool noApkLoaded()
        val xml = s.manifestText ?: return@addTool errorResult("AndroidManifest.xml not found")
        val pkg = MANIFEST_PKG_RE.find(xml)?.groupValues?.get(1).orEmpty()
        val activity = findLauncherActivity(xml)
        if (activity == null) errorResult("No LAUNCHER activity found")
        else okJson(buildJsonObject {
            put("class", absoluteClassName(pkg, activity))
            put("raw", activity)
            put("package", pkg)
        })
    }

    server.addTool(
        name = "list_classes",
        description = "Paginated list of class FQNs. Optional prefix narrows to a package subtree (e.g. 'com.applovin').",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("prefix") {
                    put("type", "string")
                    put("description", "Optional FQN prefix, e.g. 'com.applovin'. Matches both equal and dot-suffix.")
                }
                putJsonObject("offset") { put("type", "integer"); put("default", 0) }
                putJsonObject("limit") { put("type", "integer"); put("default", 200) }
            }
        )
    ) { req: CallToolRequest ->
        val s = holder.current() ?: return@addTool noApkLoaded()
        val prefix = req.arguments.strArg("prefix")?.takeIf { it.isNotBlank() }
        val offset = req.arguments.intArg("offset") ?: 0
        val limit = req.arguments.intArg("limit") ?: 200
        val allNames = s.classes.map { it.fullName }
        val filtered = if (prefix == null) allNames
        else allNames.filter { it == prefix || it.startsWith("$prefix.") }
        val total = filtered.size
        val page = filtered.asSequence().drop(offset).take(limit).toList()
        okJson(buildJsonObject {
            put("total", total)
            prefix?.let { put("prefix", it) }
            put("offset", offset)
            put("limit", limit)
            put("items", buildJsonArray { page.forEach { add(it) } })
        })
    }

    server.addTool(
        name = "get_main_application_classes_names",
        description = "Return FQNs of classes whose package matches the AndroidManifest 'package' attribute. Useful as a lightweight 'is the right APK loaded' probe.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("offset") { put("type", "integer"); put("default", 0) }
                putJsonObject("limit") { put("type", "integer"); put("default", 500) }
            }
        )
    ) { req: CallToolRequest ->
        val s = holder.current() ?: return@addTool noApkLoaded()
        val xml = s.manifestText ?: return@addTool errorResult("AndroidManifest.xml not found")
        val pkg = MANIFEST_PKG_RE.find(xml)?.groupValues?.get(1).orEmpty()
        if (pkg.isEmpty()) return@addTool errorResult("manifest 'package' attribute missing")
        val offset = req.arguments.intArg("offset") ?: 0
        val limit = req.arguments.intArg("limit") ?: 500
        val matched = s.classes.asSequence()
            .map { it.fullName }
            .filter { it == pkg || it.startsWith("$pkg.") }
            .toList()
        val page = matched.asSequence().drop(offset).take(limit).toList()
        okJson(buildJsonObject {
            put("package", pkg)
            put("total", matched.size)
            put("offset", offset)
            put("limit", limit)
            put("items", buildJsonArray { page.forEach { add(it) } })
        })
    }

    server.addTool(
        name = "search_classes_by_keyword",
        description = "Search classes across one or more scopes: 'class' (FQN), 'method', 'field' names (all cheap, metadata-only), and 'code' (decompiled source body — expensive: decompiles each scanned class). Always pass 'package' when using 'code' scope on large APKs, otherwise the scan cap will cut you off. Accepts comma-separated 'search_in' (default 'class'). Returns hits with which scope matched and a code snippet when 'code' matches.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("search_term") { put("type", "string") }
                putJsonObject("search_in") {
                    put("type", "string")
                    put("description", "Comma-separated subset of: class,method,field,code. Default 'class'.")
                    put("default", "class")
                }
                putJsonObject("package") {
                    put("type", "string")
                    put("description", "Optional FQN prefix filter, e.g. 'com.applovin'. Strongly recommended with 'code' scope.")
                }
                putJsonObject("offset") { put("type", "integer"); put("default", 0) }
                putJsonObject("count") { put("type", "integer"); put("default", 20) }
                putJsonObject("max_scan") {
                    put("type", "integer")
                    put("description", "Cap on classes scanned. 0 = use defaults (all classes for metadata; 20000 with package + code; 5000 without package + code). Overridable server-wide via --max-scan.")
                    put("default", 0)
                }
            },
            required = listOf("search_term")
        )
    ) { req: CallToolRequest ->
        val s = holder.current() ?: return@addTool noApkLoaded()
        val term = req.arguments.strArg("search_term")
            ?: req.arguments.strArg("keyword")
            ?: return@addTool errorResult("search_term is required")
        if (term.isBlank()) return@addTool errorResult("search_term must not be blank")
        val scopeNames = (req.arguments.strArg("search_in") ?: "class")
            .split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        val unknown = scopeNames.filter { it !in setOf("class", "method", "field", "code", "comment") }
        if (unknown.isNotEmpty()) {
            return@addTool errorResult("unknown search_in scopes: ${unknown.joinToString(",")} (allowed: class,method,field,code; 'comment' currently aliased to code)")
        }
        val scopes = scopeNames.mapNotNull {
            when (it) {
                "class" -> JadxSession.SearchScope.CLASS
                "method" -> JadxSession.SearchScope.METHOD
                "field" -> JadxSession.SearchScope.FIELD
                "code", "comment" -> JadxSession.SearchScope.CODE
                else -> null
            }
        }.toSet().ifEmpty { setOf(JadxSession.SearchScope.CLASS) }
        val pkg = req.arguments.strArg("package")?.takeIf { it.isNotBlank() }
        val offset = req.arguments.intArg("offset") ?: 0
        val count = req.arguments.intArg("count")
            ?: req.arguments.intArg("limit")
            ?: 20
        val maxScan = req.arguments.intArg("max_scan") ?: 0
        val result = s.searchClassesAdvanced(term, scopes, pkg, offset, count, maxScan)
        okJson(buildJsonObject {
            put("search_term", term)
            put("search_in", buildJsonArray { scopes.forEach { add(it.name.lowercase()) } })
            pkg?.let { put("package", it) }
            put("offset", offset)
            put("count", result.hits.size)
            put("scanned", result.scanned)
            put("max_scan_reached", result.maxScanReached)
            put("items", buildJsonArray {
                result.hits.forEach { h ->
                    addJsonObject {
                        put("fqn", h.fqn)
                        put("matched_in", buildJsonArray { h.matchedIn.forEach { add(it.name.lowercase()) } })
                        h.snippet?.let { put("snippet", it) }
                    }
                }
            })
        })
    }

    server.addTool(
        name = "get_class_source",
        description = "Return decompiled Java source. If jadx hits anti-decompile stubs (goto obfuscation, 'Code decompiled incorrectly' / 'Method not decompiled' banners), it transparently returns the class SMALI instead, prefixed with '// [jadx java-decompile failed → smali]' (disable with smali_fallback=false). Accepts inner/\$Companion names. Truncated at max_bytes AFTER jadx finishes; hard-aborts after decompile_timeout_ms (default 90s). Prefer get_class_summary + get_method_by_name/get_method_body for large classes.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("class_name") { put("type", "string"); put("description", "Fully-qualified class name, e.g. com.example.Foo. Inner/Companion forms (Outer\$Inner, Outer.Companion) are accepted.") }
                putJsonObject("max_bytes") { put("type", "integer"); put("description", "Per-call truncation AFTER decompile; does not speed up jadx. Prefer smaller fetches via get_method_by_name.") }
                putJsonObject("smali_fallback") { put("type", "boolean"); put("description", "Auto-fall back to smali when Java decompilation fails (default true). Set false to force the partial Java.") ; put("default", true) }
            },
            required = listOf("class_name")
        )
    ) { req: CallToolRequest ->
        val s = holder.current() ?: return@addTool noApkLoaded()
        val fqn = req.arguments.strArg("class_name") ?: return@addTool errorResult("class_name is required")
        val (cls, err) = s.resolveClassArg(fqn)
        if (cls == null) return@addTool err!!
        val cap = req.arguments.intArg("max_bytes") ?: s.maxSourceBytes
        val fallback = req.arguments.boolArg("smali_fallback") ?: true
        val smart = s.getClassSourceSmart(cls, cap, fallback)
        textResult(smart.text)
    }

    server.addTool(
        name = "get_class_summary",
        description = "Lightweight class skeleton: method signatures, field names, inner class names — no method bodies. Cheaper than get_class_source for navigation; use it to pick which method to drill into.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("class_name") { put("type", "string") }
            },
            required = listOf("class_name")
        )
    ) { req: CallToolRequest ->
        val s = holder.current() ?: return@addTool noApkLoaded()
        val fqn = req.arguments.strArg("class_name") ?: return@addTool errorResult("class_name is required")
        val (cls, err) = s.resolveClassArg(fqn)
        if (cls == null) return@addTool err!!
        okJson(buildJsonObject {
            s.summarizeClass(cls).forEach { (k, v) -> putAny(k, v) }
        })
    }

    server.addTool(
        name = "get_smali_of_class",
        description = "Return smali (DEX disassembly). Smali is often 2-3x source size, so large classes truncate. To page a huge class pass offset (byte offset into the full smali) with max_bytes as the window size; the response header reports total_bytes and next_offset. For a single method prefer get_method_smali. Hard-aborts after decompile_timeout_ms (default 90s). Accepts inner/\$Companion names.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("class_name") { put("type", "string") }
                putJsonObject("max_bytes") { put("type", "integer"); put("description", "Per-call window/truncation size (default server --max-source-bytes).") }
                putJsonObject("offset") { put("type", "integer"); put("description", "Byte offset into the full class smali to start from (for paging). Default 0."); put("default", 0) }
            },
            required = listOf("class_name")
        )
    ) { req: CallToolRequest ->
        val s = holder.current() ?: return@addTool noApkLoaded()
        val fqn = req.arguments.strArg("class_name") ?: return@addTool errorResult("class_name is required")
        val (cls, err) = s.resolveClassArg(fqn)
        if (cls == null) return@addTool err!!
        val cap = req.arguments.intArg("max_bytes") ?: s.maxSourceBytes
        val offset = (req.arguments.intArg("offset") ?: 0).coerceAtLeast(0)
        if (offset == 0) {
            // Backwards-compatible path, but annotate paging info when the class overflows the window.
            val (window, total, next) = s.getClassSmaliWindow(cls, 0, cap)
            if (total > cap) {
                val header = "// [smali window] offset=0 len=${window.length} total_bytes=$total next_offset=$next " +
                    "(pass offset=$next to continue, or use get_method_smali)\n\n"
                textResult(header + window)
            } else {
                textResult(window)
            }
        } else {
            val (window, total, next) = s.getClassSmaliWindow(cls, offset, cap)
            val more = if (next < total) " next_offset=$next" else " (end)"
            val header = "// [smali window] offset=$offset len=${window.length} total_bytes=$total$more\n\n"
            textResult(header + window)
        }
    }

    server.addTool(
        name = "get_methods_of_class",
        description = "List methods of a class. Paginated; use filter to narrow by name (case-insensitive substring); names_only=true for a compact name list.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("class_name") { put("type", "string") }
                putJsonObject("filter") { put("type", "string"); put("description", "case-insensitive substring on method name") }
                putJsonObject("offset") { put("type", "integer"); put("default", 0) }
                putJsonObject("limit") { put("type", "integer"); put("default", 100) }
                putJsonObject("names_only") { put("type", "boolean"); put("default", false) }
            },
            required = listOf("class_name")
        )
    ) { req: CallToolRequest ->
        val s = holder.current() ?: return@addTool noApkLoaded()
        val fqn = req.arguments.strArg("class_name") ?: return@addTool errorResult("class_name is required")
        val (cls, errC) = s.resolveClassArg(fqn)
        if (cls == null) return@addTool errC!!
        val filter = req.arguments.strArg("filter")?.lowercase()
        val offset = req.arguments.intArg("offset") ?: 0
        val limit = req.arguments.intArg("limit") ?: 100
        val namesOnly = req.arguments.boolArg("names_only") ?: false
        // cls.methods can force full decompile on obfuscated classes — use timed summary path
        val resolvedFqn = cls.fullName
        val summary = s.summarizeClass(cls)
        if (summary["error"] != null) {
            return@addTool errorResult(
                "list methods timed out or failed for $resolvedFqn: ${summary["error"]}. " +
                    "Try search_method_by_name or DEX-level tools."
            )
        }
        @Suppress("UNCHECKED_CAST")
        val all = (summary["methods"] as? List<Map<String, Any>>).orEmpty()
        val filtered = if (filter == null) all
        else all.filter { (it["name"] as? String)?.lowercase()?.contains(filter) == true }
        val page = filtered.asSequence().drop(offset).take(limit).toList()
        okJson(buildJsonObject {
            put("class_name", resolvedFqn)
            put("total_matching", filtered.size)
            put("offset", offset)
            put("limit", limit)
            put("count", page.size)
            put("items", buildJsonArray {
                page.forEach { m ->
                    if (namesOnly) add(m["name"] as String)
                    else addJsonObject {
                        put("name", m["name"] as String)
                        put("full_name", "$resolvedFqn.${m["name"]}")
                        put("signature", m["signature"] as? String ?: "")
                        put("is_constructor", m["is_constructor"] as? Boolean ?: false)
                        put("def_pos", (m["def_pos"] as? Number)?.toInt() ?: 0)
                    }
                }
            })
        })
    }

    server.addTool(
        name = "get_method_by_name",
        description = "Return the decompiled Java source of a single method. If overloaded, returns the first match. If jadx fails to decompile the body (anti-decompile stub), transparently returns that method's SMALI with a '// [jadx java-decompile failed → smali]' marker (disable via smali_fallback=false). Accepts inner/\$Companion class names.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("class_name") { put("type", "string") }
                putJsonObject("method_name") { put("type", "string") }
                putJsonObject("smali_fallback") { put("type", "boolean"); put("description", "Fall back to method smali when Java decompile fails (default true)."); put("default", true) }
            },
            required = listOf("class_name", "method_name")
        )
    ) { req: CallToolRequest ->
        val s = holder.current() ?: return@addTool noApkLoaded()
        val fqn = req.arguments.strArg("class_name") ?: return@addTool errorResult("class_name is required")
        val name = req.arguments.strArg("method_name") ?: return@addTool errorResult("method_name is required")
        val m = s.findMethod(fqn, name) ?: return@addTool methodNotFound(s, fqn, name)
        val fallback = req.arguments.boolArg("smali_fallback") ?: true
        textResult(s.getMethodBodySmart(m, s.maxSourceBytes, fallback).text)
    }

    server.addTool(
        name = "get_method_body",
        description = "Return ONE method as Java if it decompiles, else its SMALI — with a JSON envelope reporting which. The method-level counterpart of get_class_source's smali fallback; use it to read a single method out of a huge/obfuscated class without dumping the whole class. Reports mode ('java'|'smali'), fell_back, and any decompile-failure markers. Accepts inner/\$Companion class names and overloaded methods (set overload_index).",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("class_name") { put("type", "string") }
                putJsonObject("method_name") { put("type", "string") }
                putJsonObject("overload_index") { put("type", "integer"); put("description", "0-based index when the name is overloaded (default 0)."); put("default", 0) }
                putJsonObject("smali_fallback") { put("type", "boolean"); put("description", "Fall back to method smali when Java decompile fails (default true)."); put("default", true) }
                putJsonObject("max_bytes") { put("type", "integer"); put("description", "Per-call truncation of the body.") }
            },
            required = listOf("class_name", "method_name")
        )
    ) { req: CallToolRequest ->
        val s = holder.current() ?: return@addTool noApkLoaded()
        val fqn = req.arguments.strArg("class_name") ?: return@addTool errorResult("class_name is required")
        val name = req.arguments.strArg("method_name") ?: return@addTool errorResult("method_name is required")
        val idx = (req.arguments.intArg("overload_index") ?: 0).coerceAtLeast(0)
        val fallback = req.arguments.boolArg("smali_fallback") ?: true
        val cap = req.arguments.intArg("max_bytes") ?: s.maxSourceBytes
        val overloads = s.findMethods(fqn, name)
        if (overloads.isEmpty()) return@addTool methodNotFound(s, fqn, name)
        val m = overloads.getOrNull(idx) ?: return@addTool errorResult(
            "overload_index $idx out of range: $fqn.$name has ${overloads.size} overload(s)"
        )
        val smart = s.getMethodBodySmart(m, cap, fallback)
        okJson(buildJsonObject {
            put("class_name", m.declaringClass?.fullName ?: fqn)
            put("method_name", name)
            put("full_name", m.fullName)
            put("overload_index", idx)
            put("overload_count", overloads.size)
            put("mode", smart.kind)
            put("fell_back", smart.fellBack)
            put("markers", buildJsonArray { smart.markers.forEach { add(it) } })
            put("body", smart.text)
        })
    }

    server.addTool(
        name = "get_method_smali",
        description = "Return the SMALI of a single method (all overloads of the name), sliced out of the class disassembly. Cheap way to read one method from a huge class instead of get_smali_of_class. Accepts inner/\$Companion class names.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("class_name") { put("type", "string") }
                putJsonObject("method_name") { put("type", "string") }
                putJsonObject("max_bytes") { put("type", "integer"); put("description", "Per-call truncation.") }
            },
            required = listOf("class_name", "method_name")
        )
    ) { req: CallToolRequest ->
        val s = holder.current() ?: return@addTool noApkLoaded()
        val fqn = req.arguments.strArg("class_name") ?: return@addTool errorResult("class_name is required")
        val name = req.arguments.strArg("method_name") ?: return@addTool errorResult("method_name is required")
        val cap = req.arguments.intArg("max_bytes") ?: s.maxSourceBytes
        val (cls, err) = s.resolveClassArg(fqn)
        if (cls == null) return@addTool err!!
        val ms = s.getMethodSmali(cls, name)
        if (!ms.found) {
            return@addTool errorResult(
                "no smali for method ${cls.fullName}.$name" + (ms.note?.let { " ($it)" } ?: "") +
                    ". Check get_methods_of_class for the exact name (constructors are <init>/<clinit>)."
            )
        }
        val joined = ms.blocks.joinToString("\n\n")
        val header = "// ${cls.fullName}.$name — ${ms.blocks.size} overload(s)\n\n"
        textResult(truncate(header + joined, cap))
    }

    server.addTool(
        name = "get_inner_classes",
        description = "List the inner/nested classes of a class, each with its dotted full_name AND raw_name (the \$-joined runtime form, e.g. Outer\$Companion) so you can address them directly. Resolves \$Companion / inner / fuzzy names.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("class_name") { put("type", "string") }
            },
            required = listOf("class_name")
        )
    ) { req: CallToolRequest ->
        val s = holder.current() ?: return@addTool noApkLoaded()
        val fqn = req.arguments.strArg("class_name") ?: return@addTool errorResult("class_name is required")
        val (cls, err) = s.resolveClassArg(fqn)
        if (cls == null) return@addTool err!!
        val inners = s.innerClasses(cls)
        okJson(buildJsonObject {
            put("class_name", cls.fullName)
            put("raw_name", cls.rawName)
            put("count", inners.size)
            put("items", buildJsonArray {
                inners.forEach { m -> addJsonObject { m.forEach { (k, v) -> putAny(k, v) } } }
            })
        })
    }

    server.addTool(
        name = "resolve_class",
        description = "Resolve a possibly-inaccurate class name (inner/\$Companion/dotted/fuzzy) to its exact FQN, or list candidate FQNs when ambiguous. Use this when get_class_source says 'class not found'. Metadata-only (no decompile).",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("class_name") { put("type", "string") }
            },
            required = listOf("class_name")
        )
    ) { req: CallToolRequest ->
        val s = holder.current() ?: return@addTool noApkLoaded()
        val fqn = req.arguments.strArg("class_name") ?: return@addTool errorResult("class_name is required")
        val r = s.resolveClassDetailed(fqn)
        okJson(buildJsonObject {
            put("query", fqn)
            put("resolved", r.cls != null)
            r.cls?.let {
                put("full_name", it.fullName)
                put("raw_name", it.rawName)
            }
            put("candidates", buildJsonArray { r.candidates.forEach { add(it) } })
        })
    }

    server.addTool(
        name = "get_fields_of_class",
        description = "List fields of a class. Paginated; use filter to narrow by name (case-insensitive substring); names_only=true for a compact name list.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("class_name") { put("type", "string") }
                putJsonObject("filter") { put("type", "string"); put("description", "case-insensitive substring on field name") }
                putJsonObject("offset") { put("type", "integer"); put("default", 0) }
                putJsonObject("limit") { put("type", "integer"); put("default", 200) }
                putJsonObject("names_only") { put("type", "boolean"); put("default", false) }
            },
            required = listOf("class_name")
        )
    ) { req: CallToolRequest ->
        val s = holder.current() ?: return@addTool noApkLoaded()
        val fqn = req.arguments.strArg("class_name") ?: return@addTool errorResult("class_name is required")
        val (cls, err) = s.resolveClassArg(fqn)
        if (cls == null) return@addTool err!!
        val filter = req.arguments.strArg("filter")?.lowercase()
        val offset = req.arguments.intArg("offset") ?: 0
        val limit = req.arguments.intArg("limit") ?: 200
        val namesOnly = req.arguments.boolArg("names_only") ?: false
        val filtered = if (filter == null) cls.fields else cls.fields.filter { it.name.lowercase().contains(filter) }
        val page = filtered.asSequence().drop(offset).take(limit).toList()
        okJson(buildJsonObject {
            put("class_name", cls.fullName)
            put("total_matching", filtered.size)
            put("offset", offset)
            put("limit", limit)
            put("count", page.size)
            put("items", buildJsonArray {
                page.forEach { f ->
                    if (namesOnly) add(f.name)
                    else addJsonObject {
                        put("name", f.name)
                        put("full_name", f.fullName)
                        put("type", f.type.toString())
                    }
                }
            })
        })
    }

    server.addTool(
        name = "search_method_by_name",
        description = "Search for methods by name across all classes. Exact match preferred, falls back to substring.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("method_name") { put("type", "string") }
                putJsonObject("limit") { put("type", "integer"); put("default", 100) }
            },
            required = listOf("method_name")
        )
    ) { req: CallToolRequest ->
        val s = holder.current() ?: return@addTool noApkLoaded()
        val name = req.arguments.strArg("method_name") ?: return@addTool errorResult("method_name is required")
        val limit = req.arguments.intArg("limit") ?: 100
        val hits = s.searchMethods(name, limit)
        okJson(buildJsonObject {
            put("query", name)
            put("count", hits.size)
            put("items", buildJsonArray {
                hits.forEach { m ->
                    addJsonObject {
                        put("class_name", m.declaringClass?.fullName.orEmpty())
                        put("method_name", m.name)
                        put("full_name", m.fullName)
                    }
                }
            })
        })
    }

    server.addTool(
        name = "get_xrefs_to_class",
        description = "List code sites that reference the given class.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("class_name") { put("type", "string") }
                putJsonObject("limit") { put("type", "integer"); put("default", 200) }
            },
            required = listOf("class_name")
        )
    ) { req: CallToolRequest ->
        val s = holder.current() ?: return@addTool noApkLoaded()
        val fqn = req.arguments.strArg("class_name") ?: return@addTool errorResult("class_name is required")
        val limit = req.arguments.intArg("limit") ?: 200
        val (cls, err) = s.resolveClassArg(fqn)
        if (cls == null) return@addTool err!!
        renderUsage(cls.fullName, cls.useIn, limit, s)
    }

    server.addTool(
        name = "get_xrefs_to_method",
        description = "List code sites that call the given method.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("class_name") { put("type", "string") }
                putJsonObject("method_name") { put("type", "string") }
                putJsonObject("limit") { put("type", "integer"); put("default", 200) }
            },
            required = listOf("class_name", "method_name")
        )
    ) { req: CallToolRequest ->
        val s = holder.current() ?: return@addTool noApkLoaded()
        val fqn = req.arguments.strArg("class_name") ?: return@addTool errorResult("class_name is required")
        val name = req.arguments.strArg("method_name") ?: return@addTool errorResult("method_name is required")
        val limit = req.arguments.intArg("limit") ?: 200
        val m = s.findMethod(fqn, name) ?: return@addTool methodNotFound(s, fqn, name)
        renderUsage(m.fullName, m.useIn, limit, s)
    }

    server.addTool(
        name = "get_xrefs_to_field",
        description = "List code sites that reference the given field.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("class_name") { put("type", "string") }
                putJsonObject("field_name") { put("type", "string") }
                putJsonObject("limit") { put("type", "integer"); put("default", 200) }
            },
            required = listOf("class_name", "field_name")
        )
    ) { req: CallToolRequest ->
        val s = holder.current() ?: return@addTool noApkLoaded()
        val fqn = req.arguments.strArg("class_name") ?: return@addTool errorResult("class_name is required")
        val name = req.arguments.strArg("field_name") ?: return@addTool errorResult("field_name is required")
        val limit = req.arguments.intArg("limit") ?: 200
        val (cls, err) = s.resolveClassArg(fqn)
        if (cls == null) return@addTool err!!
        val f = cls.fields.firstOrNull { it.name == name }
            ?: return@addTool errorResult("field not found: ${cls.fullName}.$name — see get_fields_of_class")
        renderUsage(f.fullName, f.useIn, limit, s)
    }

    server.addTool(
        name = "get_strings",
        description = "Return string resources from res/values/strings*.xml. Optionally filter by substring.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("filter") { put("type", "string"); put("description", "case-insensitive substring filter on string value or name") }
                putJsonObject("limit") { put("type", "integer"); put("default", 500) }
            }
        )
    ) { req: CallToolRequest ->
        val s = holder.current() ?: return@addTool noApkLoaded()
        val filter = req.arguments.strArg("filter")
        val limit = req.arguments.intArg("limit") ?: 500
        val items = collectStringResources(s, filter, limit)
        okJson(buildJsonObject {
            put("count", items.size)
            put("filter", filter ?: "")
            put("items", buildJsonArray {
                items.forEach { (name, value) ->
                    addJsonObject {
                        put("name", name)
                        put("value", value)
                    }
                }
            })
        })
    }

    server.addTool(
        name = "list_resource_files",
        description = "List names of all resource files in the APK.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("filter") { put("type", "string"); put("description", "case-insensitive substring filter on file name") }
                putJsonObject("limit") { put("type", "integer"); put("default", 500) }
            }
        )
    ) { req: CallToolRequest ->
        val s = holder.current() ?: return@addTool noApkLoaded()
        val filter = req.arguments.strArg("filter")?.lowercase()
        val limit = req.arguments.intArg("limit") ?: 500
        val all = s.resources.map { it.deobfName }
        val filtered = if (filter == null) all else all.filter { it.lowercase().contains(filter) }
        val page = filtered.take(limit)
        okJson(buildJsonObject {
            put("total_matching", filtered.size)
            put("count", page.size)
            put("items", buildJsonArray { page.forEach { add(it) } })
        })
    }

    server.addTool(
        name = "get_resource_file",
        description = "Return the content of a resource file (text decoded if possible, otherwise base64).",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("name") { put("type", "string"); put("description", "Resource file name as reported by list_resource_files") }
            },
            required = listOf("name")
        )
    ) { req: CallToolRequest ->
        val s = holder.current() ?: return@addTool noApkLoaded()
        val name = req.arguments.strArg("name") ?: return@addTool errorResult("name is required")
        val res = s.resources.firstOrNull { it.deobfName == name || it.originalName == name }
            ?: return@addTool errorResult("resource not found: $name")
        renderResource(res, s.maxSourceBytes)
    }

    server.addTool(
        name = "export_apk_resources",
        description = "Export the APK's file-based resource tree to a directory on disk (same as jadx GUI " +
            "right-click \"Export\" on the top-level res folder under Resources). Binary AXML layouts/xml are " +
            "decoded to text; drawables/raw/fonts are copied from the zip verbatim; res/ layout is preserved. " +
            "By default exports everything under 'res/'; set 'prefix' to export another subtree (e.g. 'assets/'). " +
            "Returns the output directory and a manifest of written files. NOTE: writes files to the local disk.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("out_dir") { put("type", "string"); put("description", "Target directory. Default: <apk_dir>/<apk_name>.apk_res next to the loaded APK. Existing same-named files are overwritten.") }
                putJsonObject("prefix") { put("type", "string"); put("description", "Resource path prefix to export (case-insensitive). Default 'res/'.") }
                putJsonObject("timeout_ms") { put("type", "integer"); put("description", "Wall-clock budget for the whole export. Default 300000.") }
                putJsonObject("manifest_limit") { put("type", "integer"); put("description", "Max file entries listed in the response manifest. Default 300.") }
            }
        )
    ) { req: CallToolRequest ->
        val s = holder.current() ?: return@addTool noApkLoaded()
        val prefix = req.arguments.strArg("prefix") ?: "res/"
        val outDir = resolveExportDir(req.arguments.strArg("out_dir"), s.apkPath, "apk_res")
        val timeout = req.arguments.intArg("timeout_ms")?.toLong() ?: JadxSession.DEFAULT_EXPORT_TIMEOUT_MS
        val limit = req.arguments.intArg("manifest_limit") ?: 300
        val result = s.exportFileResources(outDir, prefix, timeout, limit)
        okJson(buildJsonObject { result.forEach { (k, v) -> putAny(k, v) } })
    }

    server.addTool(
        name = "export_arsc_resources",
        description = "Export the decoded resources.arsc value tree to a directory on disk (same as jadx GUI " +
            "right-click \"Export\" on the res node under resources.arsc). Reconstructs res/values*/*.xml " +
            "(strings/colors/dimens/styles/arrays/plurals) and res/values/public.xml from the binary resource " +
            "table — these do not exist as standalone files in the APK. Complements export_apk_resources. " +
            "Returns the output directory and a manifest of written files. NOTE: writes files to the local disk.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("out_dir") { put("type", "string"); put("description", "Target directory. Default: <apk_dir>/<apk_name>.arsc_res next to the loaded APK. Existing same-named files are overwritten.") }
                putJsonObject("timeout_ms") { put("type", "integer"); put("description", "Wall-clock budget for the whole export. Default 300000.") }
                putJsonObject("manifest_limit") { put("type", "integer"); put("description", "Max file entries listed in the response manifest. Default 300.") }
            }
        )
    ) { req: CallToolRequest ->
        val s = holder.current() ?: return@addTool noApkLoaded()
        val outDir = resolveExportDir(req.arguments.strArg("out_dir"), s.apkPath, "arsc_res")
        val timeout = req.arguments.intArg("timeout_ms")?.toLong() ?: JadxSession.DEFAULT_EXPORT_TIMEOUT_MS
        val limit = req.arguments.intArg("manifest_limit") ?: 300
        val result = s.exportArscResources(outDir, timeout, limit)
        okJson(buildJsonObject { result.forEach { (k, v) -> putAny(k, v) } })
    }
}

/**
 * Resolve the export target directory. If [outDirArg] is given it is used verbatim; otherwise a
 * default is derived next to the loaded APK: `<apk_dir>/<apk_file_name>.<suffix>`.
 */
private fun resolveExportDir(outDirArg: String?, apkPath: String, suffix: String): File {
    if (!outDirArg.isNullOrBlank()) return File(outDirArg)
    val apk = File(apkPath)
    val parent = apk.absoluteFile.parentFile ?: File(".")
    return File(parent, "${apk.name}.$suffix")
}

// ─── tool helpers ───────────────────────────────────────────────────────────

private fun renderUsage(
    target: String,
    uses: List<jadx.api.JavaNode>,
    limit: Int,
    s: JadxSession,
    resolveLine: Boolean = true,
): CallToolResult {
    val items = uses.asSequence().take(limit).map { s.describeUsage(it, resolveLine) }.toList()
    return okJson(buildJsonObject {
        put("target", target)
        put("total", uses.size)
        put("count", items.size)
        put("items", buildJsonArray {
            items.forEach { u ->
                addJsonObject { u.forEach { (k, v) -> putAny(k, v) } }
            }
        })
    })
}

private fun JsonObjectBuilder.putAny(key: String, value: Any) {
    when (value) {
        is String -> put(key, value)
        is Int -> put(key, value)
        is Long -> put(key, value)
        is Boolean -> put(key, value)
        is List<*> -> put(key, buildJsonArray {
            for (e in value) when (e) {
                is String -> add(e)
                is Map<*, *> -> addJsonObject {
                    @Suppress("UNCHECKED_CAST")
                    for ((k, v) in (e as Map<String, Any>)) putAny(k, v)
                }
                else -> add(e?.toString().orEmpty())
            }
        })
        else -> put(key, value.toString())
    }
}

private fun renderResource(res: jadx.api.ResourceFile, maxBytes: Int): CallToolResult {
    val container = runCatching { res.loadContent() }.getOrNull()
        ?: return errorResult("could not load resource content: ${res.deobfName}")
    return when (container.dataType) {
        jadx.core.xmlgen.ResContainer.DataType.TEXT -> {
            val text = container.text.codeStr ?: ""
            textResult(truncate(text, maxBytes))
        }
        jadx.core.xmlgen.ResContainer.DataType.DECODED_DATA -> {
            val bytes = container.decodedData
            okJson(buildJsonObject {
                put("name", res.deobfName)
                put("encoding", "base64")
                put("size_bytes", bytes.size)
                put("data", java.util.Base64.getEncoder().encodeToString(bytes))
            })
        }
        jadx.core.xmlgen.ResContainer.DataType.RES_TABLE -> {
            val text = container.text.codeStr ?: ""
            textResult(truncate(text, maxBytes))
        }
        jadx.core.xmlgen.ResContainer.DataType.RES_LINK -> {
            errorResult("resource is a link, not a file: ${res.deobfName}")
        }
    }
}

/**
 * Slice AndroidManifest XML by section name. Returns null if [section] is not recognized.
 * For multi-instance sections (activities/services/etc.) joins all matches with blank lines.
 */
private fun sliceManifest(xml: String, section: String): String? {
    return when (section) {
        "permissions" -> {
            val re = Regex("""<uses-permission\b[^/>]*/>|<uses-permission\b[\s\S]*?</uses-permission>""")
            re.findAll(xml).joinToString("\n") { it.value }.ifBlank { "<!-- no <uses-permission> entries -->" }
        }
        "activities" -> sliceTopLevel(xml, "activity") ?: "<!-- no <activity> entries -->"
        "services" -> sliceTopLevel(xml, "service") ?: "<!-- no <service> entries -->"
        "providers" -> sliceTopLevel(xml, "provider") ?: "<!-- no <provider> entries -->"
        "receivers" -> sliceTopLevel(xml, "receiver") ?: "<!-- no <receiver> entries -->"
        "application" -> {
            // Application open-tag with its attrs (no children — children go to other sections).
            Regex("""<application\b[^>]*>""").find(xml)?.value ?: "<!-- <application> tag not found -->"
        }
        else -> null
    }
}

private fun sliceTopLevel(xml: String, tag: String): String? {
    val open = Regex("""<$tag\b""")
    val results = mutableListOf<String>()
    for (m in open.findAll(xml)) {
        val start = m.range.first
        // Find matching end: either self-closing /> or </tag>
        val rest = xml.substring(start)
        val selfClose = Regex("""<$tag\b[^>]*/>""").find(rest, 0)
        val pairClose = Regex("""<$tag\b[\s\S]*?</$tag>""").find(rest, 0)
        val chosen = when {
            selfClose != null && pairClose != null ->
                if (selfClose.range.last <= pairClose.range.last) selfClose else pairClose
            selfClose != null -> selfClose
            pairClose != null -> pairClose
            else -> null
        } ?: continue
        if (chosen.range.first != 0) continue // must start at our anchor
        results += chosen.value
    }
    return if (results.isEmpty()) null else results.joinToString("\n\n")
}

private val MANIFEST_PKG_RE = Regex("""<manifest\b[^>]*\bpackage="([^"]+)"""")
private val VERSION_NAME_RE = Regex("""android:versionName="([^"]+)"""")
private val VERSION_CODE_RE = Regex("""android:versionCode="([^"]+)"""")
private val MIN_SDK_RE = Regex("""android:minSdkVersion="([^"]+)"""")
private val TARGET_SDK_RE = Regex("""android:targetSdkVersion="([^"]+)"""")
private val PERMISSION_RE = Regex("""<uses-permission\b[^>]*android:name="([^"]+)"""")

private fun parseManifestSummary(xml: String): JsonObject = buildJsonObject {
    put("package", MANIFEST_PKG_RE.find(xml)?.groupValues?.get(1).orEmpty())
    put("version_name", VERSION_NAME_RE.find(xml)?.groupValues?.get(1).orEmpty())
    put("version_code", VERSION_CODE_RE.find(xml)?.groupValues?.get(1).orEmpty())
    put("min_sdk", MIN_SDK_RE.find(xml)?.groupValues?.get(1).orEmpty())
    put("target_sdk", TARGET_SDK_RE.find(xml)?.groupValues?.get(1).orEmpty())
    put("permissions", buildJsonArray {
        PERMISSION_RE.findAll(xml).forEach { add(it.groupValues[1]) }
    })
}

private fun findLauncherActivity(xml: String): String? {
    val activityBlockRe = Regex(
        """<activity\b[^>]*android:name="([^"]+)"[^>]*>([\s\S]*?)</activity>""",
        RegexOption.IGNORE_CASE
    )
    val mainAction = "android.intent.action.MAIN"
    val launcherCategory = "android.intent.category.LAUNCHER"
    for (m in activityBlockRe.findAll(xml)) {
        val body = m.groupValues[2]
        if (body.contains(mainAction) && body.contains(launcherCategory)) {
            return m.groupValues[1]
        }
    }
    val activityAliasRe = Regex(
        """<activity-alias\b[^>]*android:targetActivity="([^"]+)"[^>]*>([\s\S]*?)</activity-alias>""",
        RegexOption.IGNORE_CASE
    )
    for (m in activityAliasRe.findAll(xml)) {
        val body = m.groupValues[2]
        if (body.contains(mainAction) && body.contains(launcherCategory)) {
            return m.groupValues[1]
        }
    }
    return null
}

private fun absoluteClassName(pkg: String, raw: String): String =
    when {
        raw.isEmpty() -> raw
        raw.startsWith(".") -> pkg + raw
        !raw.contains('.') -> "$pkg.$raw"
        else -> raw
    }

private val STRING_RES_RE = Regex(
    """<string\b[^>]*name="([^"]+)"[^>]*>([\s\S]*?)</string>""",
    RegexOption.IGNORE_CASE
)

private fun collectStringResources(
    s: JadxSession,
    filter: String?,
    limit: Int,
): List<Pair<String, String>> {
    val filterLc = filter?.lowercase()
    val out = mutableListOf<Pair<String, String>>()
    for (res in s.resources) {
        val deobf = res.deobfName.lowercase()
        val looksLikeStrings = "/values" in deobf && "strings" in deobf && deobf.endsWith(".xml")
        val isResTable = deobf.endsWith("resources.arsc") || deobf == "resources"
        if (!looksLikeStrings && !isResTable) continue
        val container = runCatching { res.loadContent() }.getOrNull() ?: continue
        val candidates = mutableListOf<jadx.core.xmlgen.ResContainer>()
        candidates += container
        candidates += container.subFiles
        for (c in candidates) {
            if (c.dataType != jadx.core.xmlgen.ResContainer.DataType.TEXT) continue
            val name = c.name.lowercase()
            if (!("/values" in name && "strings" in name && name.endsWith(".xml"))) continue
            val xml = c.text.codeStr ?: continue
            for (m in STRING_RES_RE.findAll(xml)) {
                val key = m.groupValues[1]
                val value = m.groupValues[2]
                if (filterLc != null &&
                    !key.lowercase().contains(filterLc) &&
                    !value.lowercase().contains(filterLc)
                ) continue
                out += key to value
                if (out.size >= limit) return out
            }
        }
    }
    return out
}

private fun truncate(text: String, max: Int): String {
    if (text.length <= max) return text
    return text.substring(0, max) +
        "\n\n... [truncated: $max bytes; total ${text.length}]"
}

// ─── result helpers ─────────────────────────────────────────────────────────

private fun textResult(text: String): CallToolResult =
    CallToolResult(content = listOf(TextContent(text)))

private fun okJson(obj: JsonObject): CallToolResult =
    textResult(json.encodeToString(JsonObject.serializer(), obj))

private fun errorResult(msg: String): CallToolResult =
    CallToolResult(content = listOf(TextContent(msg)), isError = true)

private fun noApkLoaded(): CallToolResult =
    errorResult("No APK loaded. Call 'load_apk' tool with an absolute APK path first, or check 'status'.")

/** Resolve a class arg (exact / `$Companion` / canonical / unique-fuzzy) or a rich not-found error. */
private fun JadxSession.resolveClassArg(fqn: String): Pair<jadx.api.JavaClass?, CallToolResult?> {
    val r = resolveClassDetailed(fqn)
    if (r.cls != null) return r.cls to null
    val msg = buildString {
        append("class not found: $fqn")
        if (r.candidates.isNotEmpty()) {
            append(" — ${r.candidates.size} candidate(s) matched by suffix/simple-name: ")
            append(r.candidates.take(15).joinToString(", "))
            if (r.candidates.size > 15) append(", …")
            append(". Retry with one of these exact FQNs (or use get_inner_classes / resolve_class).")
        } else {
            append(" — no exact/fuzzy match. Try list_classes, search_classes_by_keyword, or resolve_class.")
        }
    }
    return null to errorResult(msg)
}

/** Method-not-found error: distinguishes "class missing" from "class OK but no such method". */
private fun methodNotFound(s: JadxSession, fqn: String, method: String): CallToolResult {
    val r = s.resolveClassDetailed(fqn)
    val cls = r.cls
        ?: return errorResult(
            "method not found: $fqn.$method — class did not resolve." +
                if (r.candidates.isNotEmpty()) " Class candidates: ${r.candidates.take(10).joinToString(", ")}" else ""
        )
    val names = runCatching { cls.methods.map { it.name }.distinct() }.getOrDefault(emptyList())
    val near = names.filter { it.contains(method, ignoreCase = true) }.take(10)
    return errorResult(buildString {
        append("method not found: ${cls.fullName}.$method")
        if (near.isNotEmpty()) append(" — similar methods: ${near.joinToString(", ")}")
        else if (names.isNotEmpty()) append(" — class has ${names.size} method name(s); see get_methods_of_class")
        append(". (Constructors are <init>/<clinit>.)")
    })
}

// ─── argument helpers ──────────────────────────────────────────────────────

private fun JsonObject?.strArg(key: String): String? {
    val v = this?.get(key) ?: return null
    val p = v as? JsonPrimitive ?: return null
    return if (p.isString) p.content else p.contentOrNull
}

private fun JsonObject?.intArg(key: String): Int? {
    val v = this?.get(key) ?: return null
    val p = v as? JsonPrimitive ?: return null
    return p.intOrNull ?: p.contentOrNull?.toIntOrNull()
}

private fun JsonObject?.boolArg(key: String): Boolean? {
    val v = this?.get(key) ?: return null
    val p = v as? JsonPrimitive ?: return null
    return runCatching { p.boolean }.getOrNull()
        ?: p.contentOrNull?.lowercase()?.let { when (it) { "true" -> true; "false" -> false; else -> null } }
}
