package com.atxx.jhmcp

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** Lifecycle & introspection tools: status, load_apk, unload_apk. */
internal fun Server.registerSessionTools(holder: SessionHolder) {
    addTool(
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

    addTool(
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

    addTool(
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
}
