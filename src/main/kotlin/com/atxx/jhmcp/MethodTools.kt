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
 * Method-level tools: listing, single-method source/smali, and cross-class method name search.
 */
internal fun Server.registerMethodTools(holder: SessionHolder) {
    addTool(
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

    addTool(
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

    addTool(
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

    addTool(
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
        textResult(truncateToBytes(header + joined, cap))
    }

    addTool(
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
}
