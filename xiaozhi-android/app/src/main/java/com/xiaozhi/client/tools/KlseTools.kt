package com.xiaozhi.client.tools

import com.xiaozhi.client.mcp.Tool
import com.xiaozhi.client.mcp.ToolProvider
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonObject
import org.jsoup.Jsoup

// Ported from xiaozhi-web/src/tools/klse.ts (which is ported from
// xiaozhi-wss/server.py). Parsing with Jsoup instead of DOMParser.

private fun parseMetrics(doc: org.jsoup.nodes.Document): JsonObject {
    val metrics = buildJsonObject {
        for (tr in doc.select("tr")) {
            val tds = tr.select("td")
            if (tds.size == 2) {
                val key = tds[0].text().trim()
                val value = tds[1].text().trim()
                if (key.isNotEmpty() && value.isNotEmpty() && key.length < 30 && value.length < 50) {
                    put(key, value)
                }
            }
        }
    }
    return metrics
}

private fun parseDividends(doc: org.jsoup.nodes.Document): JsonArray {
    val dividends = mutableListOf<JsonObject>()
    val table = doc.getElementById("dividends")?.selectFirst("table") ?: return JsonArray(dividends)
    val rows = table.select("tr")
    if (rows.isEmpty()) return JsonArray(dividends)
    val headers = rows[0].select("th").map { it.text().trim() }
    for (tr in rows.drop(1).take(10)) {
        val cells = tr.select("td").map { it.text().trim() }
        if (cells.isNotEmpty()) {
            val row = buildJsonObject {
                cells.forEachIndexed { i, cell -> if (i < headers.size) put(headers[i], cell) }
            }
            dividends.add(row)
        }
    }
    return JsonArray(dividends)
}

private fun isCloudflareBlocked(text: String): Boolean =
    text.contains("Just a moment...") || text.length < 1000

class KlseTools : ToolProvider {
    override fun tools(): List<Tool> = listOf(
        object : Tool {
            override val name = "get_klse_stock_data"
            override val description =
                "Retrieves stock quote, fundamentals and dividend reports from KLSE Screener. " +
                    "Args: code — the stock code (e.g. '1155' for Maybank, '1023' for CIMB, etc.)."
            override fun inputSchema(): JsonObject = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("code", buildJsonObject {
                        put("type", "string")
                        put("description", "Bursa stock code, e.g. '1155'.")
                    })
                })
                put("required", JsonArray(listOf(JsonPrimitive("code"))))
            }
            override suspend fun run(args: JsonObject): String {
                val code = (args["code"]?.toString() ?: "").trim('"', ' ')
                val url = "https://www.klsescreener.com/v2/stocks/view/$code"
                return try {
                    val text = HttpTools.fetch(url)
                    if (isCloudflareBlocked(text)) {
                        return jsonPretty(buildJsonObject {
                            put("error", "Failed to bypass KLSE anti-bot protection mechanism.")
                        })
                    }
                    val doc = Jsoup.parse(text, url)
                    buildJsonObject {
                        put("code", code)
                        put("url", url)
                        doc.title().takeIf { it.isNotBlank() }?.let { put("name", it.trim()) }
                        doc.getElementById("price")?.let { put("price", it.text().trim()) }
                        put("metrics", parseMetrics(doc))
                        put("dividends", parseDividends(doc))
                    }.let(::jsonPretty)
                } catch (t: Throwable) {
                    jsonPretty(buildJsonObject {
                        put("error", "Failed to retrieve KLSE data: ${t.message ?: t::class.java.simpleName}")
                    })
                }
            }
        },
        object : Tool {
            override val name = "search_klse_stock_code"
            override val description =
                "Translates a natural language company name into its KLSE stock code. " +
                    "Args: query — the company name or partial name (e.g. 'Maybank')."
            override fun inputSchema(): JsonObject = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("query", buildJsonObject {
                        put("type", "string")
                        put("description", "Company name to resolve to a stock code.")
                    })
                })
                put("required", JsonArray(listOf(JsonPrimitive("query"))))
            }
            override suspend fun run(args: JsonObject): String {
                val query = (args["query"]?.toString() ?: "").trim('"', ' ').trim()
                val url = "https://www.klsescreener.com/v2/screener/search/${java.net.URLEncoder.encode(query, "UTF-8")}"
                return try {
                    val text = HttpTools.fetch(url)
                    val parsed = HttpTools.json.parseToJsonElement(text)
                    val arr = if (parsed is JsonArray) parsed else buildJsonArray { }
                    if (arr.isEmpty()) {
                        jsonPretty(buildJsonObject { put("message", "No stocks found matching '$query'") })
                    } else {
                        jsonPretty(arr.take(10).let { JsonArray(it) })
                    }
                } catch (t: Throwable) {
                    jsonPretty(buildJsonObject {
                        put("error", "Failed to search stock: ${t.message ?: t::class.java.simpleName}")
                    })
                }
            }
        },
    )
}