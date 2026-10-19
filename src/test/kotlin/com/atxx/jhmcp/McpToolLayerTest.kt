package com.atxx.jhmcp

import io.modelcontextprotocol.kotlin.sdk.server.ClientConnection
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.lang.reflect.Proxy

/**
 * End-to-end test THROUGH the MCP tool layer: it registers the real tools on a real [Server] via the
 * same [registerTools] used by main(), then drives a loaded session by invoking the registered tool
 * handlers by name — exercising input-schema argument parsing and the CallToolResult shape, which the
 * helper-level unit tests do not cover. Tool handlers never touch their ClientConnection receiver, so
 * a throwing Proxy stands in for it.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class McpToolLayerTest {

    private lateinit var holder: SessionHolder
    private lateinit var server: Server
    private lateinit var conn: ClientConnection

    @BeforeAll
    fun setup() {
        val jar = NestedFixture.buildJar()
        holder = SessionHolder(
            SessionConfig(threads = 1, resourceMode = ResourceMode.NONE, decompileTimeoutMs = 30_000L),
        )
        runBlocking { holder.load(jar.absolutePath) }

        server = Server(
            serverInfo = Implementation(name = "jadx-headless-mcp-test", version = "0.0.0"),
            options = ServerOptions(
                capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = null)),
            ),
        )
        registerTools(server, holder)

        conn = Proxy.newProxyInstance(
            ClientConnection::class.java.classLoader,
            arrayOf(ClientConnection::class.java),
        ) { _, method, _ ->
            throw UnsupportedOperationException("ClientConnection.${method.name} is not available in this unit test")
        } as ClientConnection
    }

    @AfterAll
    fun tearDown() {
        runBlocking { holder.unload() }
    }

    private fun call(name: String, args: JsonObject = buildJsonObject {}): CallToolResult {
        val tool = server.tools[name] ?: error("tool '$name' not registered")
        return runBlocking { tool.handler.invoke(conn, CallToolRequest(CallToolRequestParams(name = name, arguments = args))) }
    }

    private fun CallToolResult.text(): String =
        content.filterIsInstance<TextContent>().joinToString("\n") { it.text.orEmpty() }

    @Test
    fun `core tools are registered`() {
        val names = server.tools.keys
        listOf("list_classes", "get_class_source", "get_class_summary", "resolve_class").forEach {
            assertTrue(it in names, "expected tool '$it' to be registered; have=$names")
        }
    }

    @Test
    fun `list_classes returns the fixture classes`() {
        val r = call("list_classes")
        assertFalse(r.isError == true, "list_classes errored: ${r.text()}")
        assertTrue(r.text().contains("demo.Outer"), "list_classes missing demo.Outer: ${r.text()}")
    }

    @Test
    fun `resolve_class maps a nested raw name to its dotted FQN`() {
        val r = call("resolve_class", buildJsonObject { put("class_name", "demo.Outer\$Nested") })
        assertFalse(r.isError == true, "resolve_class errored: ${r.text()}")
        assertTrue(r.text().contains("demo.Outer.Nested"), "resolve_class result=${r.text()}")
    }

    @Test
    fun `get_class_source decompiles the fixture class`() {
        val r = call("get_class_source", buildJsonObject { put("class_name", "demo.Outer") })
        assertFalse(r.isError == true, "get_class_source errored: ${r.text()}")
        val src = r.text()
        assertTrue(src.contains("outerMethod") || src.contains("class Outer"), "src=$src")
    }

    @Test
    fun `get_class_summary lists methods without full decompile`() {
        val r = call("get_class_summary", buildJsonObject { put("class_name", "demo.Outer") })
        assertFalse(r.isError == true, "get_class_summary errored: ${r.text()}")
        assertTrue(r.text().contains("outerMethod"), "summary=${r.text()}")
    }

    @Test
    fun `unknown class yields an error result, not an exception`() {
        val r = call("get_class_source", buildJsonObject { put("class_name", "demo.DoesNotExist") })
        assertTrue(r.isError == true, "expected isError for a missing class; got ${r.text()}")
    }
}
