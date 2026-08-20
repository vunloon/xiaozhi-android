package com.xiaozhi.client.mcp

import kotlinx.serialization.json.JsonObject

/**
 * An MCP tool, mirroring `xiaozhi-web/src/tools/types.ts` (`ToolDef`). The
 * `run` implementation returns the text payload that the broker speaks to the
 * user — always a JSON string (or a short human string), matching FastMCP with
 * `structured_output=False`.
 */
interface Tool {
    val name: String
    val description: String
    fun inputSchema(): JsonObject
    suspend fun run(args: JsonObject): String
}