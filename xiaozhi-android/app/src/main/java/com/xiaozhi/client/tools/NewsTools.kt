package com.xiaozhi.client.tools

import com.xiaozhi.client.mcp.Tool
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jsoup.Jsoup
import java.net.URI

// Ported from xiaozhi-web/src/tools/news.ts. Two-step retrieval is intentional:
// the list tools return ≤15 {title,url}; the AI then calls get_news_article(url).

private data class NewsSite(
    val base: String,
    val paths: Map<String, String>,
    // Article URL pattern (optional). When set, only matching links are kept and the
    // title-length heuristic is skipped; sites without one keep the legacy heuristic.
    val articlePattern: Regex? = null,
)

// Link labels that never carry a headline ("read more"-style); when the anchor's
// visible text is one of these or empty, the title attribute is the real headline.
private val GENERIC_LINK_TEXTS = setOf(
    "阅读全文", "更多", "更多新闻", "更多視频", "更多文章", "read more", "more", "详情", "全文",
)

private fun anchorTitle(a: org.jsoup.nodes.Element): String {
    val text = a.text().trim()
    if (text.isNotEmpty() && text.lowercase() !in GENERIC_LINK_TEXTS) return text
    return a.attr("title").trim().ifEmpty { text }
}

private val SITES: Map<String, NewsSite> = mapOf(
    "oriental" to NewsSite(
        "https://www.orientaldaily.com.my",
        mapOf(
            "local" to "news/nation",
            "international" to "news/international",
            "finance" to "news/business",
            "entertainment" to "news/entertainment",
        ),
        // e.g. /news/nation/2026/08/26/843390 — excludes nav/section/footer links.
        articlePattern = Regex("https?://[^/]+/news/[a-z-]+/\\d{4}/\\d{2}/\\d{2}/\\d+.*"),
    ),
    "sinchew" to NewsSite(
        "https://www.sinchew.com.my",
        mapOf(
            "local" to "category/%E5%85%A8%E5%9B%BD",
            "international" to "category/%E5%9B%BD%E9%99%85",
            "finance" to "category/%E8%B4%A2%E7%BB%8F",
            "entertainment" to "category/%E5%A8%B1%E4%B9%90",
        ),
    ),
    "enanyang" to NewsSite(
        "https://www.enanyang.my",
        mapOf(
            "local" to "category/%E5%9B%BD%E5%86%85",
            "international" to "category/%E5%9B%BD%E9%99%85",
            "finance" to "category/%E8%B4%A2%E7%BB%8F",
            "entertainment" to "category/%E5%A8%B1%E4%B9%90",
        ),
    ),
)

internal fun parseList(html: String, baseUrl: String, articlePattern: Regex? = null): JsonArray {
    val doc = Jsoup.parse(html, baseUrl)
    val results = mutableListOf<JsonObject>()
    val seenUrls = mutableSetOf<String>()
    val seenTitles = mutableSetOf<String>()
    for (a in doc.select("a[href]")) {
        val title = anchorTitle(a)
        val href = a.attr("href")
        if (href.isEmpty() || title.isEmpty() || title.length >= 150) continue
        val url = runCatching { URI(baseUrl).resolve(href).toString() }.getOrNull() ?: continue
        val isArticle = articlePattern?.matches(url) ?: (title.length > 10)
        if (!isArticle) continue
        if (url in seenUrls || title in seenTitles) continue
        seenUrls.add(url)
        seenTitles.add(title)
        results.add(buildJsonObject {
            put("title", title)
            put("url", url)
        })
        if (results.size >= 15) break
    }
    return JsonArray(results)
}

private fun makeListTool(name: String, siteKey: String, description: String): Tool {
    val site = SITES.getValue(siteKey)
    return object : Tool {
        override val name = name
        override val description = description
        override fun inputSchema(): JsonObject = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("category", buildJsonObject {
                    put("type", "string")
                    put("description", "One of 'local', 'international', 'finance', 'entertainment', or 'other'.")
                })
            })
            put("required", JsonArray(emptyList()))
        }
        override suspend fun run(args: JsonObject): String {
            val category = (args["category"]?.toString() ?: "other").trim('"', ' ')
                .lowercase()
            val path = site.paths[category] ?: ""
            val url = if (path.isNotEmpty()) "${site.base}/$path" else site.base
            return try {
                val text = HttpTools.fetch(url)
                jsonPretty(buildJsonObject { put("items", parseList(text, url, site.articlePattern)) })
            } catch (t: Throwable) {
                jsonPretty(buildJsonObject { put("error", t.message ?: t::class.java.simpleName) })
            }
        }
    }
}

private val ArticleTool = object : Tool {
    override val name = "get_news_article"
    override val description =
        "Retrieves the full article contents of a specific news URL. Args: url — the URL of the " +
            "specific news article, usually obtained after calling the category list tools."
    override fun inputSchema(): JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("url", buildJsonObject {
                put("type", "string")
                put("description", "The URL of the news article to fetch.")
            })
        })
        put("required", JsonArray(listOf(JsonPrimitive("url"))))
    }
    override suspend fun run(args: JsonObject): String {
        val url = args["url"]?.toString()?.trim('"', ' ') ?: ""
        if (url.isEmpty()) return jsonPretty(buildJsonObject { put("error", "url is required") })
        return try {
            val text = HttpTools.fetch(url)
            val doc = Jsoup.parse(text, url)
            val content = doc.select("p")
                .map { it.text().trim() }
                .filter { it.length > 20 }
                .joinToString(" ")
            if (content.length > 3000) {
                "Article URL: $url\n\nContents:\n${content.take(3000)}...\n(Article truncated for length)"
            } else if (content.isNotEmpty()) {
                "Article URL: $url\n\nContents:\n$content"
            } else {
                "Could not parse meaningful text from: $url"
            }
        } catch (t: Throwable) {
            "Error fetching article: ${t.message ?: t::class.java.simpleName}"
        }
    }
}

class NewsTools : com.xiaozhi.client.mcp.ToolProvider {
    override fun tools(): List<Tool> = listOf(
        makeListTool(
            "get_oriental_daily_news",
            "oriental",
            "Gets the latest news from Oriental Daily (www.orientaldaily.com.my). Args: category — one of " +
                "'local', 'international', 'finance', 'entertainment', or 'other'.",
        ),
        makeListTool(
            "get_sinchew_news",
            "sinchew",
            "Gets the latest news from Sinchew Daily (www.sinchew.com.my). Args: category — one of " +
                "'local', 'international', 'finance', 'entertainment', or 'other'.",
        ),
        makeListTool(
            "get_enanyang_news",
            "enanyang",
            "Gets the latest news from eNanyang (www.enanyang.my). Args: category — one of " +
                "'local', 'international', 'finance', 'entertainment', or 'other'.",
        ),
        ArticleTool,
    )
}

fun jsonPretty(el: JsonElement): String = prettyJsonBuilder.encodeToString(JsonElement.serializer(), el)

private val prettyJsonBuilder = kotlinx.serialization.json.Json {
    prettyPrint = true
    ignoreUnknownKeys = true
}