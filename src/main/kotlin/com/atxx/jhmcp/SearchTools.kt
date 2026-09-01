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

/** Multi-scope class search: search_classes_by_keyword. */
internal fun Server.registerSearchTools(holder: SessionHolder) {
    addTool(
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
}
