package com.xiaozhi.client.tools

import android.content.Context
import com.xiaozhi.client.mcp.Tool
import com.xiaozhi.client.mcp.ToolProvider
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.URLEncoder
import java.time.LocalDate
import java.time.format.DateTimeFormatter

// Ported from xiaozhi-web/src/tools/watchlist.ts. Persisted in app SharedPreferences
// (the web uses localStorage). Entries are Yahoo Finance symbols — Bursa/KLSE codes
// (auto-suffixed .KL) or US tickers — optionally holdings (shares + buy_price)
// whose P/L is computed against live prices. Prices come from the public Yahoo
// chart endpoint, so no cookie/crumb auth is needed here.

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
                "Add a stock to your watchlist, or update it if already present (upsert). Supports " +
                    "Bursa/KLSE stocks (bare code e.g. 1155, stored as 1155.KL) and US/S&P 500 tickers " +
                    "(e.g. AAPL). Pass only the code to track its price; pass shares (and buy_price) to " +
                    "record a holding and track profit/loss. Args: code (e.g. 1155 or AAPL), shares " +
                    "(units, omit or 0 for a plain watch), buy_price (average price paid per share, " +
                    "in the stock's own currency)."
            override fun inputSchema(): JsonObject = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("code", buildJsonObject { put("type", "string"); put("description", "Stock symbol or Bursa code, e.g. '1155' or 'AAPL'.") })
                    put("shares", buildJsonObject { put("type", "number"); put("description", "Shares held (units). Omit or 0 for a plain watch.") })
                    put("buy_price", buildJsonObject { put("type", "number"); put("description", "Average price paid per share, in the stock's currency.") })
                })
                put("required", JsonArray(listOf(JsonPrimitive("code"))))
            }
            override suspend fun run(args: JsonObject): String {
                val raw = (args["code"]?.toString() ?: "").trim('"', ' ').trim()
                if (raw.isEmpty()) return jsonPretty(buildJsonObject { put("error", "code is required") })
                val code = resolveYahooSymbol(raw)
                val shares = (args["shares"]?.toString()?.toLongOrNull() ?: 0L).coerceAtLeast(0)
                val buy = (args["buy_price"]?.toString()?.toDoubleOrNull() ?: 0.0).coerceAtLeast(0.0)

                val summary = yahooSummary(code)
                val name = summary?.name ?: raw

                val items = repo.load().map { it.copy(code = resolveYahooSymbol(it.code)) }.toMutableList()
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
                    summary?.currency?.let { put("currency", it) }
                    if (summary == null) put("warning", "Could not verify this symbol with Yahoo Finance right now — saved anyway.")
                }
                return jsonPretty(out)
            }
        },
        object : Tool {
            override val name = "get_watchlist"
            override val description =
                "List your watchlist with live prices from Yahoo Finance, plus P/L and totals for " +
                    "holdings. Each item: {code, name, price, currency?, shares?, buy_price?, value?, " +
                    "cost?, pl?, pl_pct?}. Holdings need both shares and buy_price (and a live price) to " +
                    "count toward totals; totals are grouped per currency (MYR for KLSE, USD for US stocks)."
            override fun inputSchema(): JsonObject = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject { })
                put("required", JsonArray(emptyList()))
            }
            override suspend fun run(args: JsonObject): String {
                val items = repo.load().map { it.copy(code = resolveYahooSymbol(it.code)) }
                if (items.isEmpty()) {
                    return jsonPretty(buildJsonObject {
                        put("message", "Your watchlist is empty. Add a stock with add_to_watchlist.")
                    })
                }
                val summaries = items.associate { it.code to yahooSummary(it.code) }
                val results = mutableListOf<JsonObject>()
                var holdings = 0
                var pricedHoldings = 0
                // currency -> [cost, value]
                val totalsBy = LinkedHashMap<String, DoubleArray>()
                for (it in items) {
                    val s = summaries[it.code]
                    val price = s?.price
                    var note: String? = if (price == null) {
                        "price unavailable (quote fetch failed or symbol invalid)"
                    } else null
                    val entry = buildJsonObject {
                        put("code", it.code)
                        put("name", it.name ?: s?.name ?: it.code)
                        s?.currency?.let { c -> put("currency", c) }
                        price?.let { p -> put("price", p) }
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
                                    val cur = s?.currency ?: "XXX"
                                    val tv = totalsBy.getOrPut(cur) { DoubleArray(2) }
                                    tv[0] += shares * buy
                                    tv[1] += shares * price
                                    pricedHoldings++
                                }
                            } else {
                                note = (note ?: "") + " add buy_price to see P/L"
                            }
                        }
                        note?.trim()?.takeIf { n -> n.isNotEmpty() }?.let { n -> put("note", n) }
                    }
                    results.add(entry)
                }
                val out = buildJsonObject {
                    put("count", items.size)
                    put("holdings", holdings)
                    put("items", JsonArray(results))
                    if (pricedHoldings > 0) {
                        put("totals", buildJsonObject {
                            totalsBy.forEach { (cur, tv) ->
                                put(cur, buildJsonObject {
                                    put("cost", round2(tv[0]))
                                    put("value", round2(tv[1]))
                                    put("pl", round2(tv[1] - tv[0]))
                                    put("pl_pct", if (tv[0] != 0.0) round2((tv[1] - tv[0]) / tv[0] * 100) else null)
                                })
                            }
                        })
                    }
                }
                return jsonPretty(out)
            }
        },
        object : Tool {
            override val name = "remove_from_watchlist"
            override val description = "Remove a stock from your watchlist by its symbol. Args: code — e.g. '1155', '1155.KL' or 'AAPL'."
            override fun inputSchema(): JsonObject = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("code", buildJsonObject { put("type", "string"); put("description", "Stock symbol or Bursa code to remove.") })
                })
                put("required", JsonArray(listOf(JsonPrimitive("code"))))
            }
            override suspend fun run(args: JsonObject): String {
                val raw = (args["code"]?.toString() ?: "").trim('"', ' ').trim()
                val code = resolveYahooSymbol(raw)
                val before = repo.load().map { it.copy(code = resolveYahooSymbol(it.code)) }
                val remaining = before.filter { it.code != code }
                if (remaining.size == before.size) {
                    return jsonPretty(buildJsonObject { put("message", "$raw is not in your watchlist.") })
                }
                repo.save(remaining)
                return jsonPretty(buildJsonObject { put("removed", code); put("remaining", remaining.size) })
            }
        },
    )

    private fun today(): String = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)

    private fun round2(v: Double): Double = kotlin.math.round(v * 100.0) / 100.0

    /** Live name/price/currency from Yahoo's public chart endpoint (no crumb auth needed). */
    private fun yahooSummary(symbol: String): Summary? {
        return try {
            val enc = URLEncoder.encode(symbol, "UTF-8")
            val body = HttpTools.fetch("https://query1.finance.yahoo.com/v8/finance/chart/$enc?range=1d&interval=1d")
            val quote = parseChartQuote(body, symbol)
            val name = (quote["longName"] ?: quote["shortName"])?.jsonPrimitive?.contentOrNull
            val price = quote["regularMarketPrice"]?.jsonPrimitive?.doubleOrNull
            val currency = quote["currency"]?.jsonPrimitive?.contentOrNull
            Summary(name = name, price = price, currency = currency)
        } catch (t: Throwable) {
            null
        }
    }

    private data class Summary(val name: String?, val price: Double?, val currency: String?)
}
