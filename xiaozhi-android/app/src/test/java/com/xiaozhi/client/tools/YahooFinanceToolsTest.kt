package com.xiaozhi.client.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Offline tests for the Yahoo Finance tooling: symbol normalization (KLSE vs
 * US), value-cell compaction and the response parsers. Fixtures are trimmed
 * captures of the real quoteSummary / v1/finance/search shapes.
 */
class YahooFinanceToolsTest {

    // --- resolveYahooSymbol ---

    @Test
    fun `bare bursa codes get the KL suffix`() {
        assertEquals("1155.KL", resolveYahooSymbol("1155"))
        assertEquals("1155.KL", resolveYahooSymbol(" 1155.KL "))
        assertEquals("5347.KL", resolveYahooSymbol("5347"))
        assertEquals("1155WA.KL", resolveYahooSymbol("1155WA"))
    }

    @Test
    fun `us tickers and indexes pass through uppercased`() {
        assertEquals("AAPL", resolveYahooSymbol("aapl"))
        assertEquals("MSFT", resolveYahooSymbol(" MSFT "))
        assertEquals("^GSPC", resolveYahooSymbol("^gspc"))
        assertEquals("", resolveYahooSymbol(""))
    }

    // --- compactYahooElement ---

    @Test
    fun `value cells collapse to fmt with raw fallback`() {
        val el = HttpTools.json.parseToJsonElement(
            """{"a":{"raw":1.5,"fmt":"1.50","longFmt":"1.50"},"b":{"raw":0,"fmt":null},"c":null,"maxAge":1,"d":{}}"""
        )
        val out = compactYahooElement(el).jsonObject
        assertEquals("1.50", out["a"]!!.jsonPrimitive.content)
        assertEquals("0", out["b"]!!.jsonPrimitive.content)
        assertTrue(!out.containsKey("c"))
        assertTrue(!out.containsKey("maxAge"))
        assertTrue(!out.containsKey("d"))
    }

    @Test
    fun `compaction recurses through arrays and drops empty statements`() {
        val el = HttpTools.json.parseToJsonElement(
            """{"maxAge":1,"items":[{"endDate":{"fmt":"2025-09-30"},"netIncome":{"raw":5,"fmt":"5"},
               "emptyField":{},"nullCell":{"raw":null,"fmt":null}}]}"""
        )
        val out = compactYahooElement(el).jsonObject
        val items = out["items"]!!.jsonArray
        val row = items[0].jsonObject
        assertEquals("2025-09-30", row["endDate"]!!.jsonPrimitive.content)
        assertEquals("5", row["netIncome"]!!.jsonPrimitive.content)
        assertTrue(!row.containsKey("emptyField"))
        assertTrue(!row.containsKey("nullCell"))
    }

    // --- parseQuoteSummaryModules ---

    private val quoteFixture = """
        {"quoteSummary":{"result":[{"price":{"maxAge":1,"symbol":"1155.KL","longName":"Malayan Banking Berhad",
        "regularMarketPrice":{"raw":10.7,"fmt":"10.70"},"currency":"MYR","regularMarketTime":{"raw":1774915200,"fmt":"2026-03-31"}},
        "summaryDetail":{"previousClose":{"raw":10.56,"fmt":"10.56"},"dividendYield":{"raw":0.0625,"fmt":"6.25%"}},
        "financialData":{"targetMeanPrice":{"raw":11.5,"fmt":"11.50"},"recommendationKey":"buy"}}],"error":null}}
    """.trimIndent()

    @Test
    fun `quote summary parses modules and compacts cells`() {
        val modules = parseQuoteSummaryModules(quoteFixture)
        assertEquals("Malayan Banking Berhad", modules["price"]!!.jsonObject["longName"]!!.jsonPrimitive.content)
        assertEquals("10.70", modules["price"]!!.jsonObject["regularMarketPrice"]!!.jsonPrimitive.content)
        assertEquals("6.25%", modules["summaryDetail"]!!.jsonObject["dividendYield"]!!.jsonPrimitive.content)
        assertEquals("buy", modules["financialData"]!!.jsonObject["recommendationKey"]!!.jsonPrimitive.content)
    }

    @Test
    fun `quote summary error is surfaced as exception`() {
        val body = """{"quoteSummary":{"result":null,"error":{"code":"Not Found",
            "description":"No fundamentals data found for any of the summaryTypes=price for XYZ.KL"}}}"""
        try {
            parseQuoteSummaryModules(body)
            fail("expected YahooApiException")
        } catch (e: YahooApiException) {
            assertTrue(e.message!!.contains("No fundamentals data"))
        }
    }

    // --- financialStatementModules ---

    @Test
    fun `financial statement module selection`() {
        assertEquals(
            listOf("income_statement" to "incomeStatementHistory"),
            financialStatementModules("income", "annual"),
        )
        assertEquals(
            listOf("balance_sheet" to "balanceSheetHistoryQuarterly"),
            financialStatementModules("balance", "quarterly"),
        )
        assertEquals(3, financialStatementModules("all", "annual").size)
        assertEquals(3, financialStatementModules("bogus", "annual").size)
        assertTrue(financialStatementModules("cashflow", "annual").all { !it.second.contains("Quarterly") })
    }

    // --- parseYahooNews / parseYahooSearchQuotes ---

    @Test
    fun `news parser maps fields and formats publish time`() {
        val body = """
            {"count":2,"quotes":[],"news":[
             {"uuid":"a1","title":"Maybank Q2 profit rises","publisher":"Reuters",
              "link":"https://finance.yahoo.com/news/maybank-q2-123.html","providerPublishTime":1774915200,
              "relatedTickers":["1155.KL"]},
             {"uuid":"b2","title":"Bursa closes higher","publisher":"Bernama",
              "link":"https://finance.yahoo.com/news/bursa-456.html"}]}
        """.trimIndent()
        val news = parseYahooNews(body)
        assertEquals(2, news.size)
        val first = news[0].jsonObject
        assertEquals("Maybank Q2 profit rises", first["title"]!!.jsonPrimitive.content)
        assertEquals("Reuters", first["publisher"]!!.jsonPrimitive.content)
        assertEquals("2026-03-31 00:00 UTC", first["published"]!!.jsonPrimitive.content)
        assertEquals("1155.KL", (first["related_tickers"] as JsonArray)[0].jsonPrimitive.content)
        assertTrue(!news[1].jsonObject.containsKey("published"))
    }

    @Test
    fun `search parser prefers longname and maps exchange metadata`() {
        val body = """
            {"count":2,"quotes":[
             {"exchange":"KLS","shortname":"MAYBANK","quoteType":"EQUITY","symbol":"1155.KL",
              "exchDisp":"Kuala Lumpur Stock Exchange","sector":"Financial Services","industry":"Banks—Regional",
              "longname":"Malayan Banking Berhad","isYahooFinance":true},
             {"exchange":"NMS","shortname":"Apple Inc.","quoteType":"EQUITY","symbol":"AAPL",
              "exchDisp":"NASDAQ","isYahooFinance":true}]}
        """.trimIndent()
        val quotes = parseYahooSearchQuotes(body)
        assertEquals(2, quotes.size)
        val maybank = quotes[0].jsonObject
        assertEquals("1155.KL", maybank["symbol"]!!.jsonPrimitive.content)
        assertEquals("Malayan Banking Berhad", maybank["name"]!!.jsonPrimitive.content)
        assertEquals("Kuala Lumpur Stock Exchange", maybank["exchange"]!!.jsonPrimitive.content)
        assertEquals("Banks—Regional", maybank["industry"]!!.jsonPrimitive.content)
        val apple = quotes[1].jsonObject
        assertEquals("Apple Inc.", apple["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `search parser tolerates malformed payloads`() {
        assertTrue(parseYahooSearchQuotes("not json").isEmpty())
        assertTrue(parseYahooNews("""{"quotes":[]}""").isEmpty())
    }

    // --- parseChartQuote ---

    @Test
    fun `chart fallback extracts meta fields`() {
        val body = """
            {"chart":{"result":[{"meta":{"currency":"MYR","symbol":"1155.KL","exchangeName":"KLS",
             "instrumentType":"EQUITY","regularMarketPrice":10.7,"fiftyTwoWeekHigh":11.2,
             "fiftyTwoWeekLow":8.9,"regularMarketVolume":22580500,"shortName":"MAYBANK",
             "longName":"Malayan Banking Berhad"}}],"error":null}}
        """.trimIndent()
        val quote = parseChartQuote(body, "1155.KL")
        assertEquals("1155.KL", quote["symbol"]!!.jsonPrimitive.content)
        assertEquals("Malayan Banking Berhad", quote["longName"]!!.jsonPrimitive.content)
        assertEquals(10.7, quote["regularMarketPrice"]!!.jsonPrimitive.content.toDouble(), 1e-9)
        assertTrue(!quote.containsKey("previousClose"))
    }
}
