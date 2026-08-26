package com.xiaozhi.client.tools

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression test for the Oriental Daily list scraper. The site's article
 * anchors carry empty or generic ("阅读全文") visible text with the real
 * headline in the title attribute, and nav/footer links (e.g. 产业大奖,
 * Xiaohongshu) used to crowd out real articles. See NewsTools.kt.
 */
class NewsToolsParseListTest {

    private val orientalPattern =
        Regex("https?://[^/]+/news/[a-z-]+/\\d{4}/\\d{2}/\\d{2}/\\d+.*")

    // Trimmed structure of the real orientaldaily.com.my homepage.
    private val fixture = """
        <html><body>
        <nav>
          <a class="nav-link" href="https://www.orientaldaily.com.my/news/nation">国内</a>
          <a class="nav-link" href="https://www.orientaldaily.com.my/news/2024">2024年马来西亚产业大奖</a>
        </nav>
        <div class="carousel">
          <div class="box">
            <h3>无津贴RON95油价皆涨5仙</h3>
            <a href="https://www.orientaldaily.com.my/news/nation/2026/08/26/843390?source=lead"
               title="无津贴RON95油价皆涨5仙"></a>
          </div>
        </div>
        <div class="news-item">
          <a class="link" href="https://www.orientaldaily.com.my/news/nation/2026/08/26/843390"
             title="无津贴RON95油价皆涨5仙"><span>阅读全文</span></a>
          <a class="link" href="https://www.orientaldaily.com.my/news/central/2026/08/26/843362"
             title="依斯迈拉欣称有人挑拨离间"><span>阅读全文</span></a>
        </div>
        <footer>
          <a href="https://www.xiaohongshu.com/user/profile/61f568ee" rel="nofollow" target="_blank">Xiaohongshu</a>
        </footer>
        </body></html>
    """.trimIndent()

    @Test
    fun `oriental daily extracts articles from title attribute and applies article pattern`() {
        val items = parseList(fixture, "https://www.orientaldaily.com.my", orientalPattern)
        val entries = items.map { it.jsonObject }
        assertEquals(2, entries.size)
        entries.forEach { e ->
            val url = e["url"]!!.jsonPrimitive.content
            assertTrue("not an article url: $url", orientalPattern.matches(url))
            assertTrue(e["title"]!!.jsonPrimitive.content.isNotBlank())
        }
        assertEquals("无津贴RON95油价皆涨5仙", entries[0]["title"]!!.jsonPrimitive.content)
    }

    @Test
    fun `oriental daily dedupes carousel and overlay links for the same article`() {
        val items = parseList(fixture, "https://www.orientaldaily.com.my", orientalPattern)
        val urls = items.map { it.jsonObject["url"]!!.jsonPrimitive.content }
        assertEquals(urls.size, urls.toSet().size)
    }

    @Test
    fun `legacy length heuristic still applies when no pattern is given`() {
        val items = parseList(fixture, "https://www.orientaldaily.com.my", null)
        val titles = items.map { it.jsonObject["title"]!!.jsonPrimitive.content }
        // Only the nav link has >10 visible text chars; article titles come from
        // the title attribute and pass too.
        assertTrue(titles.contains("2024年马来西亚产业大奖"))
        assertTrue(titles.contains("无津贴RON95油价皆涨5仙"))
    }
}
