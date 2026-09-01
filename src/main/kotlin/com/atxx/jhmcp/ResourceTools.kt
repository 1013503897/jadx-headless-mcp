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

/** Resource tools: get_strings, list_resource_files, get_resource_file. */
internal fun Server.registerResourceTools(holder: SessionHolder) {
    addTool(
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

    addTool(
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

    addTool(
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
