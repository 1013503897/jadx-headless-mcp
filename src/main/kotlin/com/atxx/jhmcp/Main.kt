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

private val log = LoggerFactory.getLogger("jhmcp.Main")
private val json = Json { prettyPrint = false; encodeDefaults = true }

private data class Config(val apkPath: String?, val maxSourceBytes: Int)

private fun parseArgs(args: Array<String>): Config {
    var apkPath: String? = null
    var maxSourceBytes = 200_000
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
    return Config(apkPath, maxSourceBytes)
}

private const val USAGE = """
Usage: jadx-headless-mcp [--apk <path>] [--max-source-bytes N]

Headless JADX-based MCP server for Android APK static analysis.
Communicates via MCP over stdio.

Options:
  --apk <path>              optional: APK / DEX / JAR to load eagerly at startup.
                            If omitted, use the 'load_apk' tool to load on demand.
  --max-source-bytes <n>    max bytes per source response (default 200000)
  -h, --help                show this help
"""

fun main(args: Array<String>) {
    // kotlin-logging 8.x writes its "initializing..." banner to System.out,
    // which would corrupt MCP's JSON-RPC stdout stream. Capture the real
    // stdout for the transport and route everything else to stderr.
    val realOut = System.out
    System.setOut(System.err)

    val cfg = parseArgs(args)
    val holder = SessionHolder(cfg.maxSourceBytes)
    Runtime.getRuntime().addShutdownHook(Thread {
        runCatching { runBlocking { holder.unload() } }
    })

    val server = Server(
        serverInfo = Implementation(name = "jadx-headless-mcp", version = "0.2.0"),
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
        )
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
        val xml = findManifestText(s)
        if (xml == null) errorResult("AndroidManifest.xml not found")
        else okJson(parseManifestSummary(xml))
    }

    server.addTool(
        name = "get_android_manifest",
        description = "Return the full decoded AndroidManifest.xml as text.",
        inputSchema = ToolSchema(properties = buildJsonObject {})
    ) { _: CallToolRequest ->
        val s = holder.current() ?: return@addTool noApkLoaded()
        val xml = findManifestText(s)
        if (xml == null) errorResult("AndroidManifest.xml not found") else textResult(xml)
    }

    server.addTool(
        name = "get_main_activity_class",
        description = "Return the FQN of the LAUNCHER activity (action MAIN + category LAUNCHER).",
        inputSchema = ToolSchema(properties = buildJsonObject {})
    ) { _: CallToolRequest ->
        val s = holder.current() ?: return@addTool noApkLoaded()
        val xml = findManifestText(s) ?: return@addTool errorResult("AndroidManifest.xml not found")
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
        description = "Paginated list of all class FQNs in the APK.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("offset") { put("type", "integer"); put("default", 0) }
                putJsonObject("limit") { put("type", "integer"); put("default", 200) }
            }
        )
    ) { req: CallToolRequest ->
        val s = holder.current() ?: return@addTool noApkLoaded()
        val offset = req.arguments.intArg("offset") ?: 0
        val limit = req.arguments.intArg("limit") ?: 200
        val total = s.classes.size
        val page = s.classes.asSequence().drop(offset).take(limit).map { it.fullName }.toList()
        okJson(buildJsonObject {
            put("total", total)
            put("offset", offset)
            put("limit", limit)
            put("items", buildJsonArray { page.forEach { add(it) } })
        })
    }

    server.addTool(
        name = "search_classes_by_keyword",
        description = "Substring search over class FQNs (case-insensitive).",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("keyword") { put("type", "string") }
                putJsonObject("limit") { put("type", "integer"); put("default", 100) }
            },
            required = listOf("keyword")
        )
    ) { req: CallToolRequest ->
        val s = holder.current() ?: return@addTool noApkLoaded()
        val kw = req.arguments.strArg("keyword") ?: return@addTool errorResult("keyword is required")
        val limit = req.arguments.intArg("limit") ?: 100
        val hits = s.searchClasses(kw, limit).map { it.fullName }
        okJson(buildJsonObject {
            put("keyword", kw)
            put("count", hits.size)
            put("items", buildJsonArray { hits.forEach { add(it) } })
        })
    }

    server.addTool(
        name = "get_class_source",
        description = "Return the decompiled Java source of a class. Truncated at max-source-bytes.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("class_name") { put("type", "string"); put("description", "Fully-qualified class name, e.g. com.example.Foo") }
            },
            required = listOf("class_name")
        )
    ) { req: CallToolRequest ->
        val s = holder.current() ?: return@addTool noApkLoaded()
        val fqn = req.arguments.strArg("class_name") ?: return@addTool errorResult("class_name is required")
        val cls = s.findClass(fqn) ?: return@addTool errorResult("class not found: $fqn")
        textResult(s.getClassSource(cls))
    }

    server.addTool(
        name = "get_smali_of_class",
        description = "Return the smali (DEX disassembly) of a class. Truncated at max-source-bytes.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("class_name") { put("type", "string") }
            },
            required = listOf("class_name")
        )
    ) { req: CallToolRequest ->
        val s = holder.current() ?: return@addTool noApkLoaded()
        val fqn = req.arguments.strArg("class_name") ?: return@addTool errorResult("class_name is required")
        val cls = s.findClass(fqn) ?: return@addTool errorResult("class not found: $fqn")
        textResult(s.getClassSmali(cls))
    }

    server.addTool(
        name = "get_methods_of_class",
        description = "List all methods of a class with their signatures.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("class_name") { put("type", "string") }
            },
            required = listOf("class_name")
        )
    ) { req: CallToolRequest ->
        val s = holder.current() ?: return@addTool noApkLoaded()
        val fqn = req.arguments.strArg("class_name") ?: return@addTool errorResult("class_name is required")
        val cls = s.findClass(fqn) ?: return@addTool errorResult("class not found: $fqn")
        val items = cls.methods.map { m ->
            buildJsonObject {
                put("name", m.name)
                put("full_name", m.fullName)
                put("return_type", m.returnType.toString())
                put(
                    "arg_types",
                    buildJsonArray { m.arguments.forEach { add(it.toString()) } }
                )
                put("is_constructor", m.isConstructor)
                put("is_class_init", m.isClassInit)
            }
        }
        okJson(buildJsonObject {
            put("class_name", fqn)
            put("count", items.size)
            put("items", buildJsonArray { items.forEach { add(it) } })
        })
    }

    server.addTool(
        name = "get_method_by_name",
        description = "Return the decompiled source of a single method. If overloaded, returns the first match.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("class_name") { put("type", "string") }
                putJsonObject("method_name") { put("type", "string") }
            },
            required = listOf("class_name", "method_name")
        )
    ) { req: CallToolRequest ->
        val s = holder.current() ?: return@addTool noApkLoaded()
        val fqn = req.arguments.strArg("class_name") ?: return@addTool errorResult("class_name is required")
        val name = req.arguments.strArg("method_name") ?: return@addTool errorResult("method_name is required")
        val m = s.findMethod(fqn, name) ?: return@addTool errorResult("method not found: $fqn.$name")
        textResult(m.codeStr.orEmpty().ifEmpty { "// method exists but has no decompiled body (native/abstract)" })
    }

    server.addTool(
        name = "get_fields_of_class",
        description = "List all fields of a class.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("class_name") { put("type", "string") }
            },
            required = listOf("class_name")
        )
    ) { req: CallToolRequest ->
        val s = holder.current() ?: return@addTool noApkLoaded()
        val fqn = req.arguments.strArg("class_name") ?: return@addTool errorResult("class_name is required")
        val cls = s.findClass(fqn) ?: return@addTool errorResult("class not found: $fqn")
        val items = cls.fields.map { f ->
            buildJsonObject {
                put("name", f.name)
                put("full_name", f.fullName)
                put("type", f.type.toString())
            }
        }
        okJson(buildJsonObject {
            put("class_name", fqn)
            put("count", items.size)
            put("items", buildJsonArray { items.forEach { add(it) } })
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
        val cls = s.findClass(fqn) ?: return@addTool errorResult("class not found: $fqn")
        renderUsage(fqn, cls.useIn, limit, s)
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
        val m = s.findMethod(fqn, name) ?: return@addTool errorResult("method not found: $fqn.$name")
        renderUsage("$fqn.$name", m.useIn, limit, s)
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
        val f = s.findField(fqn, name) ?: return@addTool errorResult("field not found: $fqn.$name")
        renderUsage("$fqn.$name", f.useIn, limit, s)
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
}

// ─── tool helpers ───────────────────────────────────────────────────────────

private fun renderUsage(
    target: String,
    uses: List<jadx.api.JavaNode>,
    limit: Int,
    s: JadxSession,
): CallToolResult {
    val items = uses.asSequence().take(limit).map { s.describeUsage(it) }.toList()
    return okJson(buildJsonObject {
        put("target", target)
        put("total", uses.size)
        put("count", items.size)
        put("items", buildJsonArray {
            items.forEach { u ->
                addJsonObject { u.forEach { (k, v) -> put(k, v) } }
            }
        })
    })
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

private fun findManifestText(s: JadxSession): String? {
    val res = s.resources.firstOrNull {
        val n = it.deobfName.lowercase()
        n.endsWith("androidmanifest.xml") || n == "androidmanifest.xml"
    } ?: return null
    val container = runCatching { res.loadContent() }.getOrNull() ?: return null
    return when (container.dataType) {
        jadx.core.xmlgen.ResContainer.DataType.TEXT -> container.text.codeStr
        else -> null
    }
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
