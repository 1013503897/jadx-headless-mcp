package com.atxx.jhmcp

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** AndroidManifest-derived tools: get_app_info, get_android_manifest, get_main_activity_class. */
internal fun Server.registerManifestTools(holder: SessionHolder) {
    addTool(
        name = "get_app_info",
        description = "Return package name, version, minSdk/targetSdk, and permissions parsed from AndroidManifest.xml.",
        inputSchema = ToolSchema(properties = buildJsonObject {})
    ) { _: CallToolRequest ->
        val s = holder.current() ?: return@addTool noApkLoaded()
        val xml = s.manifestText
        if (xml == null) errorResult("AndroidManifest.xml not found")
        else okJson(parseManifestSummary(xml))
    }

    addTool(
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
        textResult(truncateToBytes(sliced, cap))
    }

    addTool(
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
}
