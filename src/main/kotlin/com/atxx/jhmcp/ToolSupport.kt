package com.atxx.jhmcp

import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.serialization.json.Json
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
import kotlinx.serialization.json.put

/**
 * Shared helpers for the MCP tool handlers: JSON result shapes, argument coercion, and the
 * cross-tool rendering/resolution helpers. Kept in one place so the per-topic tool files
 * (Session/Manifest/Class/Method/Search/Xref/Resource) stay focused on tool wiring.
 */

internal val json = Json { prettyPrint = false; encodeDefaults = true }

// ─── result helpers ─────────────────────────────────────────────────────────

internal fun textResult(text: String): CallToolResult =
    CallToolResult(content = listOf(TextContent(text)))

internal fun okJson(obj: JsonObject): CallToolResult =
    textResult(json.encodeToString(JsonObject.serializer(), obj))

internal fun errorResult(msg: String): CallToolResult =
    CallToolResult(content = listOf(TextContent(msg)), isError = true)

internal fun noApkLoaded(): CallToolResult =
    errorResult("No APK loaded. Call 'load_apk' tool with an absolute APK path first, or check 'status'.")

// ─── argument helpers ───────────────────────────────────────────────────────

internal fun JsonObject?.strArg(key: String): String? {
    val v = this?.get(key) ?: return null
    val p = v as? JsonPrimitive ?: return null
    return if (p.isString) p.content else p.contentOrNull
}

internal fun JsonObject?.intArg(key: String): Int? {
    val v = this?.get(key) ?: return null
    val p = v as? JsonPrimitive ?: return null
    return p.intOrNull ?: p.contentOrNull?.toIntOrNull()
}

internal fun JsonObject?.boolArg(key: String): Boolean? {
    val v = this?.get(key) ?: return null
    val p = v as? JsonPrimitive ?: return null
    return runCatching { p.boolean }.getOrNull()
        ?: p.contentOrNull?.lowercase()?.let { when (it) { "true" -> true; "false" -> false; else -> null } }
}

// ─── JSON building ──────────────────────────────────────────────────────────

internal fun JsonObjectBuilder.putAny(key: String, value: Any) {
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

// ─── xref rendering ─────────────────────────────────────────────────────────

internal fun renderUsage(
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
        put("resolve_line", resolveLine)
        put("items", buildJsonArray {
            items.forEach { u ->
                addJsonObject { u.forEach { (k, v) -> putAny(k, v) } }
            }
        })
    })
}

// ─── resource rendering ─────────────────────────────────────────────────────

internal fun renderResource(res: jadx.api.ResourceFile, maxBytes: Int): CallToolResult {
    val container = runCatching { res.loadContent() }.getOrNull()
        ?: return errorResult("could not load resource content: ${res.deobfName}")
    return when (container.dataType) {
        jadx.core.xmlgen.ResContainer.DataType.TEXT -> {
            val text = container.text.codeStr ?: ""
            textResult(truncateToBytes(text, maxBytes))
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
            textResult(truncateToBytes(text, maxBytes))
        }
        jadx.core.xmlgen.ResContainer.DataType.RES_LINK -> {
            errorResult("resource is a link, not a file: ${res.deobfName}")
        }
    }
}

// ─── class / method resolution error helpers ────────────────────────────────

/** Resolve a class arg (exact / `$Companion` / canonical / unique-fuzzy) or a rich not-found error. */
internal fun JadxSession.resolveClassArg(fqn: String): Pair<jadx.api.JavaClass?, CallToolResult?> {
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
internal fun methodNotFound(s: JadxSession, fqn: String, method: String): CallToolResult {
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
