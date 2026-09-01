package com.atxx.jhmcp

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Class-level tools: listing, source, summary, smali, fields, inner classes, name resolution.
 */
internal fun Server.registerClassTools(holder: SessionHolder) {
    addTool(
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
        val allNames = s.classFqns
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

    addTool(
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
        val matched = s.classFqns.asSequence()
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

    addTool(
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

    addTool(
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

    addTool(
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

    addTool(
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

    addTool(
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

    addTool(
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
}
