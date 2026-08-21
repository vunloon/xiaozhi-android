package com.xiaozhi.client.tools

import android.content.Context
import com.xiaozhi.client.mcp.Tool
import com.xiaozhi.client.mcp.ToolProvider
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jsoup.Jsoup
import java.time.LocalDate
import java.time.format.DateTimeFormatter

// Ported from xiaozhi-web/src/tools/watchlist.ts. Persisted in app SharedPreferences
// (the web uses localStorage). Entries are Bursa stock codes, optionally holdings
// (shares + buy_price) whose P/L is computed against live KLSE prices.

@Serializable
data class WatchEntry(
    val code: String,
    val name: String? = null,
    val shares: Long? = null,
    val buy_price: Double? = null,
    val updated_at: String? = null,
)

class WatchlistRepo(context: Context) {
    private val prefs = context.getSharedPreferences("xiaozhi_watchlist", Context.MODE_PRIVATE)

    @Synchronized
    fun load(): List<WatchEntry> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return runCatching {
            HttpTools.json.decodeFromString(
                ListSerializer(WatchEntry.serializer()),
                raw,
            )
        }.getOrElse { emptyList() }
    }

    @Synchronized
    fun save(items: List<WatchEntry>) {
        prefs.edit()
            .putString(KEY, HttpTools.json.encodeToString(
                ListSerializer(WatchEntry.serializer()),
                items,
            ))
            .apply()
    }

    private companion object {
        const val KEY = "watchlist.v1"
    }
}

class WatchlistTools(context: Context) : ToolProvider {

    private val repo = WatchlistRepo(context)

    override fun tools(): List<Tool> = listOf(
        object : Tool {
            override val name = "add_to_watchlist"
            override val description =
                "Add a Bursa stock to your watchlist, or update it if already present (upsert). " +
                    "Pass only the code to track its price; pass shares (and buy_price) to record a holding and " +
                    "track profit/loss. Args: code (e.g. 1155), shares (units, omit or 0 for a plain watch), " +
                    "buy_price (average price paid per share, RM)."
            override fun inputSchema(): JsonObject = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("code", buildJsonObject { put("type", "string"); put("description", "KLSE stock code, e.g. '1155'.") })
                    put("shares", buildJsonObject { put("type", "number"); put("description", "Shares held (units). Omit or 0 for a plain watch.") })
                    put("buy_price", buildJsonObject { put("type", "number"); put("description", "Average price paid per share (RM).") })
                })
                put("required", JsonArray(listOf(JsonPrimitive("code"))))
            }
            override suspend fun run(args: JsonObject): String {
                val code = (args["code"]?.toString() ?: "").trim('"', ' ').trim()
                if (code.isEmpty()) return jsonPretty(buildJsonObject { put("error", "code is required") })
                val shares = (args["shares"]?.toString()?.toLongOrNull() ?: 0L).coerceAtLeast(0)
                val buy = (args["buy_price"]?.toString()?.toDoubleOrNull() ?: 0.0).coerceAtLeast(0.0)

                val summary = klseSummary(code)
                val name = summary?.name ?: code

                val items = repo.load().toMutableList()
                val entry = items.firstOrNull { it.code == code } ?: WatchEntry(code = code)
                if (items.none { it.code == code }) items.add(entry)
                val idx = items.indexOfFirst { it.code == code }
                items[idx] = entry.copy(
                    name = name,
                    shares = if (shares > 0) shares else null,
                    buy_price = if (shares > 0 && buy > 0) buy else null,
                    updated_at = today(),
                )
                repo.save(items)

                val out = buildJsonObject {
                    put("code", code)
                    put("name", name)
                    put("type", if (shares > 0) "holding" else "watch")
                    if (shares > 0) put("shares", shares)
                    if (shares > 0 && buy > 0) put("buy_price", buy)
                    if (summary == null) put("warning", "Could not verify this code with KLSE right now — saved anyway.")
                }
                return jsonPretty(out)
            }
        },
        object : Tool {
            override val name = "get_watchlist"
            override val description =
                "List your watchlist with live KLSE prices, plus P/L and totals for holdings. " +
                    "Each item: {code, name, price, shares?, buy_price?, value?, cost?, pl?, pl_pct?}. " +
                    "Holdings need both shares and buy_price (and a live price) to count toward totals."
            override fun inputSchema(): JsonObject = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject { })
                put("required", JsonArray(emptyList()))
            }
            override suspend fun run(args: JsonObject): String {
                val items = repo.load()
                if (items.isEmpty()) {
                    return jsonPretty(buildJsonObject {
                        put("message", "Your watchlist is empty. Add a stock with add_to_watchlist.")
                    })
                }
                val summaries = items.map { it.code to klseSummary(it.code) }.toMap()
                val results = mutableListOf<JsonObject>()
                var totCost = 0.0
                var totValue = 0.0
                var holdings = 0
                var pricedHoldings = 0
                for (it in items) {
                    val s = summaries[it.code]
                    val price = s?.price
                    var note: String? = if (price == null) {
                        "price unavailable (KLSE blocked or code invalid)"
                    } else null
                    val entry = buildJsonObject {
                        put("code", it.code)
                        put("name", it.name ?: s?.name ?: it.code)
                        s?.priceStr?.let { p -> put("price", p) }
                        val shares = it.shares
                        if (shares != null && shares > 0) {
                            holdings++
                            put("shares", shares)
                            val buy = it.buy_price
                            val value = price?.let { p -> shares * p }
                            val cost = buy?.let { p -> shares * p }
                            if (value != null) put("value", round2(value))
                            if (buy != null) {
                                put("buy_price", buy)
                                put("cost", round2(shares * buy))
                                if (price != null) {
                                    val pl = shares * price - shares * buy
                                    put("pl", round2(pl))
                                    put("pl_pct", if (buy != 0.0) round2(pl / (shares * buy) * 100) else null)
                                    totCost += shares * buy
                                    totValue += shares * price
                                    pricedHoldings++
                                }
                            } else {
                                note = (note ?: "") + " add buy_price to see P/L"
                            }
                        }
                        note?.trim()?.takeIf { it.isNotEmpty() }?.let { put("note", it) }
                    }
                    results.add(entry)
                }
                val out = buildJsonObject {
                    put("count", items.size)
                    put("holdings", holdings)
                    put("currency", "MYR")
                    put("items", JsonArray(results))
                    if (pricedHoldings > 0) {
                        put("totals", buildJsonObject {
                            put("cost", round2(totCost))
                            put("value", round2(totValue))
                            put("pl", round2(totValue - totCost))
                            put("pl_pct", if (totCost != 0.0) round2((totValue - totCost) / totCost * 100) else null)
                        })
                    }
                }
                return jsonPretty(out)
            }
        },
        object : Tool {
            override val name = "remove_from_watchlist"
            override val description = "Remove a stock from your watchlist by its code. Args: code — e.g. '1155'."
            override fun inputSchema(): JsonObject = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("code", buildJsonObject { put("type", "string"); put("description", "KLSE stock code to remove.") })
                })
                put("required", JsonArray(listOf(JsonPrimitive("code"))))
            }
            override suspend fun run(args: JsonObject): String {
                val code = (args["code"]?.toString() ?: "").trim('"', ' ').trim()
                val before = repo.load()
                val remaining = before.filter { it.code != code }
                if (remaining.size == before.size) {
                    return jsonPretty(buildJsonObject { put("message", "$code is not in your watchlist.") })
                }
                repo.save(remaining)
                return jsonPretty(buildJsonObject { put("removed", code); put("remaining", remaining.size) })
            }
        },
    )

    private fun today(): String = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)

    private fun round2(v: Double): Double = kotlin.math.round(v * 100.0) / 100.0

    private fun parsePrice(text: String?): Double? {
        if (text.isNullOrBlank()) return null
        val m = Regex("\\d+(?:\\.\\d+)?").find(text.replace(",", ""))
        return m?.value?.toDoubleOrNull()
    }

    private fun klseSummary(code: String): Summary? {
        val url = "https://www.klsescreener.com/v2/stocks/view/$code"
        return try {
            val text = HttpTools.fetch(url)
            if (text.contains("Just a moment...") || text.length < 1000) return null
            val doc = Jsoup.parse(text, url)
            val priceStr = doc.getElementById("price")?.text()?.trim()
            Summary(
                name = doc.title().takeIf { it.isNotBlank() }?.trim(),
                priceStr = priceStr,
                price = parsePrice(priceStr),
            )
        } catch (t: Throwable) {
            null
        }
    }

    private data class Summary(val name: String?, val priceStr: String?, val price: Double?)
}