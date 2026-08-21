package com.xiaozhi.client.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Shared HTTP plumbing for the MCP tools. Unlike the browser client there is
 * no CORS here, so the tools talk to the upstreams directly with browser-ish
 * headers (the same ones the web proxy injects).
 */
object HttpTools {

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    val json: Json = Json { ignoreUnknownKeys = true }

    const val BROWSER_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private val TEXT = "application/json; charset=utf-8".toMediaType()

    /** GET a page with a desktop-Chrome UA. Returns the raw body text. */
    fun fetch(url: String, lang: String = "en-US,en;q=0.9"): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", BROWSER_UA)
            .header("Accept-Language", lang)
            .build()
        client.newCall(request).execute().use { resp ->
            check(resp.isSuccessful) { "HTTP ${resp.code}: ${resp.message}" }
            return resp.body?.string() ?: ""
        }
    }

    /** POST a YouTube Music innertube payload and return the parsed JSON. */
    fun ytmSearch(payload: JsonObject): JsonObject {
        val request = Request.Builder()
            .url(YTM_SEARCH_BASE)
            .post(payload.toString().toRequestBody(TEXT))
            .header("User-Agent", BROWSER_UA)
            .header("X-Goog-Visitor-Id", YTM_VISITOR_ID)
            .build()
        client.newCall(request).execute().use { resp ->
            check(resp.isSuccessful) { "ytm HTTP ${resp.code}: ${resp.message}" }
            val raw = resp.body?.string() ?: throw IllegalStateException("empty ytm response")
            return json.parseToJsonElement(raw).jsonObject
        }
    }

    private const val YTM_SEARCH_BASE =
        "https://music.youtube.com/youtubei/v1/search?key=AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX30"
    private const val YTM_VISITOR_ID = "CgtkME5Edm1Sb0ZodyjSiKmrBjIKCgJVUxIEGgAgVw=="
}