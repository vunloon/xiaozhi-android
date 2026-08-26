package com.xiaozhi.client.tools

import com.xiaozhi.client.mcp.Tool
import com.xiaozhi.client.mcp.ToolProvider
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.Request
import java.net.URLEncoder
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

// Stock tools backed by the unofficial Yahoo Finance API. Replaces the old
// klsescreener.com scraper (KlseTools) and covers both Bursa Malaysia/KLSE
// (e.g. 1155.KL) and US/S&P 500 (e.g. AAPL, ^GSPC) tickers. The quoteSummary
// endpoints require the cookie+crumb pair Yahoo issues to browsers; the
// v1/finance/search endpoint (symbol lookup + news) is public.

internal class YahooApiException(message: String) : Exception(message)

/**
 * Normalizes user/AI input into a Yahoo symbol: bare Bursa board codes
 * (digits, optionally with warrant letters) get the .KL suffix; ^indexes and
 * already-suffixed symbols pass through; anything else is a US ticker.
 */
internal fun resolveYahooSymbol(raw: String): String {
    val s = raw.trim().uppercase()
    return when {
        s.isEmpty() || s.startsWith("^") || s.contains('.') -> s
        s.any { it.isDigit() } -> "$s.KL"
        else -> s
    }
}

/**
 * Recursively compacts Yahoo's value cells ({raw, fmt, longFmt} -> fmt, or raw
 * when there is no formatted form) and drops nulls, empty objects and maxAge
 * fields, so statement payloads stay readable for the voice LLM.
 */
internal fun compactYahooElement(el: JsonElement): JsonElement = when (el) {
    is JsonObject -> {
        val raw = el["raw"]
        val fmt = el["fmt"]
        if (el.isNotEmpty() && el.keys.all { it == "raw" || it == "fmt" || it == "longFmt" }) {
            when {
                fmt is JsonPrimitive && fmt != JsonNull -> fmt
                raw is JsonPrimitive && raw != JsonNull -> raw
                else -> JsonNull
            }
        } else {
            buildJsonObject {
                for ((k, v) in el) {
                    if (k == "maxAge") continue
                    val c = compactYahooElement(v)
                    if (c is JsonNull || (c is JsonObject && c.isEmpty())) continue
                    put(k, c)
                }
            }
        }
    }
    is JsonArray -> JsonArray(el.map(::compactYahooElement).filter { it !is JsonNull })
    else -> el
}

internal object YahooFinanceApi {
    private const val BASE = "https://query2.finance.yahoo.com"

    @Volatile private var cookie: String? = null
    @Volatile private var crumb: String? = null

    /** GET an endpoint that needs the cookie+crumb pair; refreshes credentials once on auth failure. */
    fun fetchApi(pathQuery: String): String {
        var attempts = 0
        while (true) {
            if (crumb == null || cookie == null) refreshCredentials()
            val sep = if ('?' in pathQuery) "&" else "?"
            val url = "$BASE$pathQuery${sep}crumb=${URLEncoder.encode(crumb!!, "UTF-8")}"
            val request = Request.Builder().url(url)
                .header("User-Agent", HttpTools.BROWSER_UA)
                .header("Cookie", cookie!!)
                .build()
            HttpTools.client.newCall(request).execute().use { resp ->
                val body = resp.body?.string() ?: ""
                val authFailure = resp.code == 401 || resp.code == 403 || body.contains("Invalid Crumb")
                if (!authFailure) {
                    if (resp.isSuccessful) return body
                    throw YahooApiException("Yahoo API HTTP ${resp.code}")
                }
                if (++attempts > 1) throw YahooApiException("Yahoo API auth failed (HTTP ${resp.code})")
                crumb = null
            }
        }
    }

    private fun refreshCredentials() {
        val cookiePairs = HttpTools.client.newCall(
            Request.Builder().url("https://fc.yahoo.com")
                .header("User-Agent", HttpTools.BROWSER_UA)
                .build()
        ).execute().use { resp ->
            resp.headers("set-cookie").mapNotNull { it.substringBefore(';').trim().takeIf { it.isNotEmpty() } }
        }
        cookie = cookiePairs.joinToString("; ").ifEmpty { null }
            ?: throw YahooApiException("Yahoo returned no session cookie")
        val crumbBody = HttpTools.client.newCall(
            Request.Builder().url("$BASE/v1/test/getcrumb")
                .header("User-Agent", HttpTools.BROWSER_UA)
                .header("Cookie", cookie!!)
                .build()
        ).execute().use { it.body?.string() ?: "" }
        crumb = crumbBody.trim().takeUnless { it.isEmpty() }
            ?: throw YahooApiException("Yahoo returned no crumb")
    }
}

internal fun parseQuoteSummaryModules(body: String): JsonObject {
    val summary = runCatching { HttpTools.json.parseToJsonElement(body).jsonObject["quoteSummary"]!!.jsonObject }
        .getOrNull() ?: throw YahooApiException("Unexpected quoteSummary response")
    summary["error"]?.let { err ->
        if (err is JsonObject) {
            val desc = err["description"]?.jsonPrimitive?.contentOrNull ?: err.toString()
            throw YahooApiException(desc)
        }
    }
    val result = summary["result"]?.jsonArray ?: throw YahooApiException("Missing result in quoteSummary")
    if (result.isEmpty()) throw YahooApiException("No fundamentals data found for symbol")
    val compacted = compactYahooElement(result[0])
    return compacted as JsonObject
}

/** statement/period -> (output key, quoteSummary module). statement: income|balance|cashflow|all. */
internal fun financialStatementModules(statement: String, period: String): List<Pair<String, String>> {
    val suffix = if (period.equals("quarterly", ignoreCase = true)) "Quarterly" else ""
    val all = listOf(
        "income_statement" to "incomeStatementHistory$suffix",
        "balance_sheet" to "balanceSheetHistory$suffix",
        "cashflow_statement" to "cashflowStatementHistory$suffix",
    )
    return when (statement.lowercase().trim()) {
        "income" -> all.take(1)
        "balance" -> all.drop(1).take(1)
        "cashflow" -> all.drop(2).take(1)
        else -> all
    }
}

private val newsTimeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC)

internal fun formatEpochUtc(seconds: Long): String = newsTimeFormat.format(Instant.ofEpochSecond(seconds))

internal fun parseYahooNews(body: String): JsonArray {
    val root = runCatching { HttpTools.json.parseToJsonElement(body).jsonObject }.getOrNull()
        ?: return JsonArray(emptyList())
    val news = root["news"]?.jsonArray ?: return JsonArray(emptyList())
    val out = mutableListOf<JsonObject>()
    for (item in news.take(12)) {
        val o = item.jsonObject
        out.add(buildJsonObject {
            o["title"]?.jsonPrimitive?.contentOrNull?.let { put("title", it) }
            o["publisher"]?.jsonPrimitive?.contentOrNull?.let { put("publisher", it) }
            o["link"]?.jsonPrimitive?.contentOrNull?.let { put("url", it) }
            o["providerPublishTime"]?.jsonPrimitive?.longOrNull?.let { put("published", formatEpochUtc(it)) }
            (o["relatedTickers"] as? JsonArray)?.takeIf { it.isNotEmpty() }?.let { put("related_tickers", it) }
        })
    }
    return JsonArray(out)
}

internal fun parseYahooSearchQuotes(body: String): JsonArray {
    val root = runCatching { HttpTools.json.parseToJsonElement(body).jsonObject }.getOrNull()
        ?: return JsonArray(emptyList())
    val quotes = root["quotes"]?.jsonArray ?: return JsonArray(emptyList())
    val out = quotes.take(10).mapNotNull { q ->
        val o = q.jsonObject
        val symbol = o["symbol"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
        buildJsonObject {
            put("symbol", symbol)
            (o["longname"] ?: o["shortname"])?.jsonPrimitive?.contentOrNull?.let { put("name", it) }
            o["exchDisp"]?.jsonPrimitive?.contentOrNull?.let { put("exchange", it) }
            o["quoteType"]?.jsonPrimitive?.contentOrNull?.let { put("type", it) }
            o["sector"]?.jsonPrimitive?.contentOrNull?.let { put("sector", it) }
            o["industry"]?.jsonPrimitive?.contentOrNull?.let { put("industry", it) }
        }
    }
    return JsonArray(out)
}

/** Basic quote from the public chart endpoint — fallback when quoteSummary auth fails. */
internal fun parseChartQuote(body: String, symbol: String): JsonObject {
    val chart = runCatching { HttpTools.json.parseToJsonElement(body).jsonObject["chart"]!!.jsonObject }
        .getOrNull() ?: throw YahooApiException("Unexpected chart response")
    chart["error"]?.let { err ->
        if (err is JsonObject) {
            val desc = err["description"]?.jsonPrimitive?.contentOrNull ?: err.toString()
            throw YahooApiException(desc)
        }
    }
    val meta = (chart["result"] as? JsonArray)?.firstOrNull()?.jsonObject?.get("meta")?.jsonObject
        ?: throw YahooApiException("No chart data for $symbol")
    val keys = listOf(
        "currency", "instrumentType", "exchangeName", "fullExchangeName", "shortName", "longName",
        "regularMarketPrice", "regularMarketDayHigh", "regularMarketDayLow", "regularMarketVolume",
        "fiftyTwoWeekHigh", "fiftyTwoWeekLow", "regularMarketTime", "previousClose", "chartPreviousClose",
    )
    return buildJsonObject {
        put("symbol", symbol)
        for (k in keys) {
            val v = meta[k]
            if (v != null && v !is JsonNull) put(k, v)
        }
    }
}

private fun quoteUrl(symbol: String) = "https://finance.yahoo.com/quote/${URLEncoder.encode(symbol, "UTF-8")}"

private fun argString(args: JsonObject, key: String): String =
    args[key]?.jsonPrimitive?.contentOrNull?.trim('"', ' ')?.trim() ?: ""

private fun errorMessage(msg: String): String = jsonPretty(buildJsonObject { put("error", msg) })

class YahooFinanceTools : ToolProvider {
    override fun tools(): List<Tool> = listOf(
        object : Tool {
            override val name = "get_stock_quote"
            override val description =
                "Retrieves a detailed stock quote with key fundamentals from Yahoo Finance: price, " +
                    "day change, 52-week range, volume, valuation (PE, forward PE, P/B, EPS, market cap), " +
                    "dividend yield and payout, profitability ratios, analyst recommendation and price " +
                    "targets, earnings history and the company profile. Covers Bursa Malaysia/KLSE stocks " +
                    "(e.g. '1155' or '1155.KL' for Maybank, '1023' for CIMB), US stocks including the " +
                    "S&P 500 (e.g. 'AAPL', 'MSFT', 'NVDA') and indexes like '^GSPC' (S&P 500). " +
                    "Args: symbol — ticker or Bursa stock code."
            override fun inputSchema(): JsonObject = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("symbol", buildJsonObject {
                        put("type", "string")
                        put("description", "Ticker symbol or Bursa stock code, e.g. '1155', 'AAPL' or '^GSPC'.")
                    })
                })
                put("required", JsonArray(listOf(JsonPrimitive("symbol"))))
            }
            override suspend fun run(args: JsonObject): String {
                val symbol = resolveYahooSymbol(argString(args, "symbol"))
                if (symbol.isEmpty()) return errorMessage("symbol is required")
                return try {
                    val modules = listOf(
                        "price", "summaryDetail", "defaultKeyStatistics", "financialData", "summaryProfile", "earnings",
                    )
                    val body = YahooFinanceApi.fetchApi(
                        "/v10/finance/quoteSummary/${URLEncoder.encode(symbol, "UTF-8")}" +
                            "?modules=${modules.joinToString(",")}"
                    )
                    buildJsonObject {
                        put("symbol", symbol)
                        put("url", quoteUrl(symbol))
                        parseQuoteSummaryModules(body).forEach { (k, v) -> put(k, v) }
                    }.let(::jsonPretty)
                } catch (t: Throwable) {
                    try {
                        val enc = URLEncoder.encode(symbol, "UTF-8")
                        val chartBody = HttpTools.fetch(
                            "https://query1.finance.yahoo.com/v8/finance/chart/$enc?range=1mo&interval=1d"
                        )
                        buildJsonObject {
                            put("note", "Full fundamentals unavailable, basic quote only")
                            parseChartQuote(chartBody, symbol).forEach { (k, v) -> put(k, v) }
                        }.let(::jsonPretty)
                    } catch (t2: Throwable) {
                        errorMessage(
                            "Failed to get quote for '$symbol': ${t.message ?: t::class.java.simpleName}. " +
                                "Use search_stock_symbol to find the correct symbol."
                        )
                    }
                }
            }
        },
        object : Tool {
            override val name = "get_stock_financials"
            override val description =
                "Retrieves financial statements from Yahoo Finance for analysis: income statement " +
                    "(revenue, cost, operating income, net income, margins), balance sheet (total assets, " +
                    "liabilities, debt, cash, equity) and cash flow (operating/investing/financing flows, " +
                    "free cash flow, dividends paid). Covers KLSE/Bursa Malaysia and US/S&P 500 stocks. " +
                    "Args: symbol — ticker or Bursa code; statement — 'income', 'balance', 'cashflow' or " +
                    "'all' (default 'all'); period — 'annual' (default) or 'quarterly'."
            override fun inputSchema(): JsonObject = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("symbol", buildJsonObject {
                        put("type", "string")
                        put("description", "Ticker symbol or Bursa stock code, e.g. '1155' or 'AAPL'.")
                    })
                    put("statement", buildJsonObject {
                        put("type", "string")
                        put("description", "Which statement: 'income', 'balance', 'cashflow', or 'all'.")
                    })
                    put("period", buildJsonObject {
                        put("type", "string")
                        put("description", "'annual' (default) or 'quarterly'.")
                    })
                })
                put("required", JsonArray(listOf(JsonPrimitive("symbol"))))
            }
            override suspend fun run(args: JsonObject): String {
                val symbol = resolveYahooSymbol(argString(args, "symbol"))
                if (symbol.isEmpty()) return errorMessage("symbol is required")
                val statement = argString(args, "statement").ifEmpty { "all" }
                val period = argString(args, "period").ifEmpty { "annual" }
                val modules = financialStatementModules(statement, period)
                return try {
                    val body = YahooFinanceApi.fetchApi(
                        "/v10/finance/quoteSummary/${URLEncoder.encode(symbol, "UTF-8")}" +
                            "?modules=${modules.joinToString(",") { it.second }}"
                    )
                    val parsed = parseQuoteSummaryModules(body)
                    buildJsonObject {
                        put("symbol", symbol)
                        put("period", period.lowercase())
                        put("url", quoteUrl(symbol) + "/financials")
                        for ((key, module) in modules) {
                            parsed[module]?.let { put(key, it) }
                        }
                    }.let(::jsonPretty)
                } catch (t: Throwable) {
                    errorMessage(
                        "Failed to get financials for '$symbol': ${t.message ?: t::class.java.simpleName}. " +
                            "Use search_stock_symbol to find the correct symbol."
                    )
                }
            }
        },
        object : Tool {
            override val name = "get_stock_news"
            override val description =
                "Retrieves the latest news headlines about a stock from Yahoo Finance, with title, " +
                    "publisher, publish time and link. Covers KLSE/Bursa Malaysia and US/S&P 500 stocks. " +
                    "Args: symbol — ticker, Bursa code or company name, e.g. '1155', 'AAPL' or 'Maybank'."
            override fun inputSchema(): JsonObject = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("symbol", buildJsonObject {
                        put("type", "string")
                        put("description", "Ticker symbol, Bursa stock code or company name.")
                    })
                })
                put("required", JsonArray(listOf(JsonPrimitive("symbol"))))
            }
            override suspend fun run(args: JsonObject): String {
                val query = argString(args, "symbol")
                if (query.isEmpty()) return errorMessage("symbol is required")
                val symbol = resolveYahooSymbol(query)
                return try {
                    val body = HttpTools.fetch(
                        "https://query2.finance.yahoo.com/v1/finance/search?q=" +
                            "${URLEncoder.encode(symbol, "UTF-8")}&quotesCount=4&newsCount=12"
                    )
                    buildJsonObject {
                        put("symbol", symbol)
                        put("news", parseYahooNews(body))
                    }.let(::jsonPretty)
                } catch (t: Throwable) {
                    errorMessage("Failed to get news for '$query': ${t.message ?: t::class.java.simpleName}")
                }
            }
        },
        object : Tool {
            override val name = "search_stock_symbol"
            override val description =
                "Translates a company name or partial ticker into its Yahoo Finance symbol, covering " +
                    "KLSE/Bursa Malaysia and US markets including the S&P 500 (e.g. 'Maybank' -> '1155.KL', " +
                    "'Apple' -> 'AAPL'). Args: query — the company name or partial name."
            override fun inputSchema(): JsonObject = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("query", buildJsonObject {
                        put("type", "string")
                        put("description", "Company name to resolve to a Yahoo Finance symbol.")
                    })
                })
                put("required", JsonArray(listOf(JsonPrimitive("query"))))
            }
            override suspend fun run(args: JsonObject): String {
                val query = argString(args, "query")
                if (query.isEmpty()) return errorMessage("query is required")
                return try {
                    val body = HttpTools.fetch(
                        "https://query2.finance.yahoo.com/v1/finance/search?q=" +
                            "${URLEncoder.encode(query, "UTF-8")}&quotesCount=10&newsCount=0"
                    )
                    val quotes = parseYahooSearchQuotes(body)
                    if (quotes.isEmpty()) {
                        jsonPretty(buildJsonObject { put("message", "No stocks found matching '$query'") })
                    } else {
                        jsonPretty(buildJsonObject { put("matches", quotes) })
                    }
                } catch (t: Throwable) {
                    errorMessage("Failed to search stock: ${t.message ?: t::class.java.simpleName}")
                }
            }
        },
    )
}
