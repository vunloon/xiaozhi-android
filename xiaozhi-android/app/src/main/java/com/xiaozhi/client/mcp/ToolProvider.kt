package com.xiaozhi.client.mcp

/** A group of related tools (e.g. the news family). */
interface ToolProvider {
    fun tools(): List<Tool>
}