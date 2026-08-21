package com.xiaozhi.client.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * In-process MCP JSON-RPC endpoint, mirroring `xiaozhi-web/src/core/mcp.ts`.
 * Handles `initialize` / `ping` / `tools/list` / `tools/call` and returns a
 * reply payload (or null for notifications).
 */
class McpDispatcher(private val registry: ToolRegistry) {

    private val json = Json { ignoreUnknownKeys = true }

    /** Handles one inbound JSON-RPC payload; returns a reply payload or null. */
    suspend fun handle(req: JsonObject): JsonObject? {
        val method = req["method"]?.jsonPrimitive?.contentOrNull ?: return null
        if (method.startsWith("notifications")) return null
        val id = req["id"] ?: return null
        val params = req["params"]?.jsonObject ?: buildJsonObject { }
        return when (method) {
            "initialize" -> reply(id, handleInitialize())
            "ping" -> reply(id, buildJsonObject { })
            "tools/list" -> reply(id, buildJsonObject { put("tools", JsonArray(registry.catalog())) })
            "tools/call" -> {
                try {
                    reply(id, handleToolCall(params))
                } catch (t: Throwable) {
                    replyError(id, t.message ?: t::class.java.simpleName)
                }
            }
            else -> replyError(id, "Method not implemented: $method")
        }
    }

    private fun handleInitialize(): JsonObject = buildJsonObject {
        put("protocolVersion", PROTOCOL_VERSION)
        put("capabilities", buildJsonObject { put("tools", buildJsonObject { put("listChanged", JsonPrimitive(false)) }) })
        put("serverInfo", buildJsonObject {
            put("name", "xiaozhi-android")
            put("version", APP_VERSION)
        })
    }

    private suspend fun handleToolCall(params: JsonObject): JsonObject {
        val name = params["name"]?.jsonPrimitive?.contentOrNull ?: throw IllegalStateException("Missing tool name")
        val tool = registry.find(name) ?: throw IllegalStateException("Unknown tool: $name")
        val args = params["arguments"]?.jsonObject ?: buildJsonObject { }
        val text = tool.run(args)
        // Always the standard CallToolResult shape ({content:[{type:"text",text}]}),
        // exactly like FastMCP with structured_output=False — the broker reads
        // result.content to speak the answer.
        return buildJsonObject {
            put("content", JsonArray(
                listOf(buildJsonObject {
                    put("type", "text")
                    put("text", text)
                })
            ))
            put("isError", JsonPrimitive(false))
        }
    }

    private fun reply(id: JsonElement, result: JsonObject): JsonObject = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("result", result)
    }

    private fun replyError(id: JsonElement, message: String): JsonObject = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("error", buildJsonObject { put("message", message) })
    }

    companion object {
        private const val PROTOCOL_VERSION = "2024-11-05"
        private const val APP_VERSION = "1.0.0"
    }
}