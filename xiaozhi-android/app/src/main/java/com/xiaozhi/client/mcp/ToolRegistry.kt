package com.xiaozhi.client.mcp

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Registry of in-app MCP tools shared by the voice channel and the reverse-WSS channel. */
class ToolRegistry {

    private val tools = LinkedHashMap<String, Tool>()

    fun register(tool: Tool) {
        tools[tool.name] = tool
    }

    fun registerAll(vararg ts: Tool) {
        ts.forEach { register(it) }
    }

    fun find(name: String): Tool? = tools[name]

    /** Catalog entries for `tools/list`. */
    fun catalog(): List<JsonObject> = tools.values.map { t ->
        buildJsonObject {
            put("name", t.name)
            put("description", t.description)
            put("inputSchema", t.inputSchema())
        }
    }
}