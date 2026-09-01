package com.atxx.jhmcp

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Cross-reference tools: get_xrefs_to_class / _method / _field.
 *
 * Each accepts `resolve_line` (default true). Resolving the source line of every usage
 * force-decompiles that usage's top-level class; on a large fan-out (hundreds of xrefs across
 * hundreds of classes) that is the dominant cost. Pass resolve_line=false to get the reference
 * list cheaply (line reported as 0).
 */
internal fun Server.registerXrefTools(holder: SessionHolder) {
    val resolveLineProp: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {
        putJsonObject("resolve_line") {
            put("type", "boolean")
            put("description", "Resolve each usage's source line (force-decompiles its class). Default true; set false for a cheap reference list.")
            put("default", true)
        }
    }

    addTool(
        name = "get_xrefs_to_class",
        description = "List code sites that reference the given class.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("class_name") { put("type", "string") }
                putJsonObject("limit") { put("type", "integer"); put("default", 200) }
                resolveLineProp()
            },
            required = listOf("class_name")
        )
    ) { req: CallToolRequest ->
        val s = holder.current() ?: return@addTool noApkLoaded()
        val fqn = req.arguments.strArg("class_name") ?: return@addTool errorResult("class_name is required")
        val limit = req.arguments.intArg("limit") ?: 200
        val resolveLine = req.arguments.boolArg("resolve_line") ?: true
        val (cls, err) = s.resolveClassArg(fqn)
        if (cls == null) return@addTool err!!
        renderUsage(cls.fullName, cls.useIn, limit, s, resolveLine)
    }

    addTool(
        name = "get_xrefs_to_method",
        description = "List code sites that call the given method.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("class_name") { put("type", "string") }
                putJsonObject("method_name") { put("type", "string") }
                putJsonObject("limit") { put("type", "integer"); put("default", 200) }
                resolveLineProp()
            },
            required = listOf("class_name", "method_name")
        )
    ) { req: CallToolRequest ->
        val s = holder.current() ?: return@addTool noApkLoaded()
        val fqn = req.arguments.strArg("class_name") ?: return@addTool errorResult("class_name is required")
        val name = req.arguments.strArg("method_name") ?: return@addTool errorResult("method_name is required")
        val limit = req.arguments.intArg("limit") ?: 200
        val resolveLine = req.arguments.boolArg("resolve_line") ?: true
        val m = s.findMethod(fqn, name) ?: return@addTool methodNotFound(s, fqn, name)
        renderUsage(m.fullName, m.useIn, limit, s, resolveLine)
    }

    addTool(
        name = "get_xrefs_to_field",
        description = "List code sites that reference the given field.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("class_name") { put("type", "string") }
                putJsonObject("field_name") { put("type", "string") }
                putJsonObject("limit") { put("type", "integer"); put("default", 200) }
                resolveLineProp()
            },
            required = listOf("class_name", "field_name")
        )
    ) { req: CallToolRequest ->
        val s = holder.current() ?: return@addTool noApkLoaded()
        val fqn = req.arguments.strArg("class_name") ?: return@addTool errorResult("class_name is required")
        val name = req.arguments.strArg("field_name") ?: return@addTool errorResult("field_name is required")
        val limit = req.arguments.intArg("limit") ?: 200
        val resolveLine = req.arguments.boolArg("resolve_line") ?: true
        val (cls, err) = s.resolveClassArg(fqn)
        if (cls == null) return@addTool err!!
        val f = cls.fields.firstOrNull { it.name == name }
            ?: return@addTool errorResult("field not found: ${cls.fullName}.$name — see get_fields_of_class")
        renderUsage(f.fullName, f.useIn, limit, s, resolveLine)
    }
}
