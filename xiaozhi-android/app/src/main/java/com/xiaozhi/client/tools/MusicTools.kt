package com.xiaozhi.client.tools

import com.xiaozhi.client.mcp.Tool
import com.xiaozhi.client.mcp.ToolProvider
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

// Ported from xiaozhi-web/src/tools/ytmusic.ts. Search hits YouTube Music's
// innertube API directly (no proxy needed on Android — no CORS); the response
// navigation mirrors ytmusicapi's parse_song_runs.

private val YTM_CONTEXT = buildJsonObject {
    put("context", buildJsonObject {
        put("client", buildJsonObject {
            put("clientName", "WEB_REMIX")
            put("clientVersion", "1.20240805.01.00")
            put("hl", "en")
            put("gl", "US")
            put("visitorData", "CgtkME5Edm1Sb0ZodyjSiKmrBjIKCgJVUxIEGgAgVw%3D%3D")
        })
    })
}

// songs filter (from ytmusicapi get_search_params)
private const val SONGS_PARAMS = "EgWKAQIIAWoMEA4QChADEAQQCRAF"

private fun navPath(node: JsonElement?, path: List<Any>): JsonElement? {
    var cur = node
    for (key in path) {
        cur = when (key) {
            is String -> (cur as? JsonObject)?.get(key)
            is Int -> (cur as? JsonArray)?.getOrNull(key)
            else -> return null
        } ?: return null
    }
    return cur
}

private fun navStr(node: JsonElement?, path: List<Any>): String? =
    navPath(node, path)?.jsonPrimitive?.contentOrNull

private fun parseSongRuns(runs: JsonArray): Pair<List<String>, String> {
    val artists = mutableListOf<String>()
    var duration = ""
    for (i in runs.indices step 2) {
        val run = runs.getOrNull(i)?.jsonObject ?: continue
        val text = run["text"]?.jsonPrimitive?.contentOrNull ?: ""
        val browseId = navStr(run, listOf("navigationEndpoint", "browseEndpoint", "browseId"))
        when {
            browseId != null -> {
                if (!browseId.startsWith("MPRE") && !browseId.contains("release_detail")) artists.add(text)
            }
            Regex("^\\d+(:\\d+)+$").matches(text) -> duration = text
            !Regex("^\\d+\\s*[^ ]*$").matches(text) || text.length > 2 -> {
                if (text.isNotEmpty() && text != "•" && duration.isEmpty()) artists.add(text)
            }
        }
    }
    return artists to duration
}

private fun parseSearchResponse(resp: JsonObject): List<JsonObject> {
    val sl = navPath(resp, listOf(
        "contents", "tabbedSearchResultsRenderer", "tabs", 0, "tabRenderer",
        "content", "sectionListRenderer",
    )) as? JsonObject ?: return emptyList()
    val sections = sl["contents"] as? JsonArray ?: JsonArray(emptyList())
    val shelf = sections.firstOrNull {
        (it as? JsonObject)?.get("musicShelfRenderer") != null
    } as? JsonObject ?: return emptyList()
    val contents = shelf["musicShelfRenderer"]?.jsonObject?.get("contents") as? JsonArray ?: return emptyList()

    val items = mutableListOf<JsonObject>()
    for (raw in contents) {
        val it = (raw as? JsonObject)?.get("musicResponsiveListItemRenderer") as? JsonObject ?: continue
        val title = navStr(it, listOf("flexColumns", 0, "musicResponsiveListItemFlexColumnRenderer", "text", "runs", 0, "text")) ?: ""
        val runs = navPath(it, listOf("flexColumns", 1, "musicResponsiveListItemFlexColumnRenderer", "text", "runs")) as? JsonArray ?: continue
        val (artists, duration) = parseSongRuns(runs)
        val videoId = (it["playlistItemData"] as? JsonObject)?.get("videoId")?.jsonPrimitive?.contentOrNull ?: continue
        if (title.isEmpty()) continue
        items.add(buildJsonObject {
            put("title", title)
            put("artist", artists.joinToString(", "))
            put("videoId", videoId)
            put("duration", duration)
        })
    }
    return items
}

class MusicTools : ToolProvider {
    override fun tools(): List<Tool> = listOf(
        object : Tool {
            override val name = "search_music"
            override val description =
                "Search YouTube Music for a SPECIFIC song or artist by name. Use this whenever the user names " +
                    "a song or an artist, in any language (e.g. \"play Jay Chou\", \"周杰伦 晴天\", \"Bohemian Rhapsody\", " +
                    "\"我要听周杰伦的歌\"). This is the only music search tool — use it for all song and artist requests. " +
                    "Args: query (song/artist name), artist (optional, folded into the query), gender (optional, " +
                    "best-effort only), limit (max results, default 8). Returns a JSON list of {title, artist, " +
                    "videoId, duration}. Then call play_music(videoId)."
            override fun inputSchema(): JsonObject = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("query", buildJsonObject { put("type", "string"); put("description", "Song name, artist name, or keywords.") })
                    put("artist", buildJsonObject { put("type", "string"); put("description", "Optional artist name to narrow the search.") })
                    put("gender", buildJsonObject { put("type", "string"); put("description", "Optional keyword hint; rarely useful.") })
                    put("limit", buildJsonObject { put("type", "number"); put("description", "Max number of results (default 8).") })
                })
                put("required", JsonArray(listOf(JsonPrimitive("query"))))
            }
            override suspend fun run(args: JsonObject): String {
                val query = (args["query"]?.jsonPrimitive?.contentOrNull ?: "").trim()
                val artist = (args["artist"]?.jsonPrimitive?.contentOrNull ?: "").trim()
                val gender = (args["gender"]?.jsonPrimitive?.contentOrNull ?: "").trim()
                val limit = (args["limit"]?.toString()?.toIntOrNull() ?: 8).coerceAtLeast(1)
                val fullQuery = listOf(query, artist, gender).filter { it.isNotEmpty() }.joinToString(" ")
                return try {
                    val payload = buildJsonObject {
                        YTM_CONTEXT.forEach { (k, v) -> put(k, v) }
                        put("params", SONGS_PARAMS)
                        put("query", fullQuery)
                    }
                    val resp = HttpTools.ytmSearch(payload)
                    val items = parseSearchResponse(resp).take(limit)
                    if (items.isEmpty()) {
                        jsonPretty(buildJsonObject { put("message", "No songs found for '$fullQuery'") })
                    } else {
                        jsonPretty(buildJsonObject { put("results", JsonArray(items)) })
                    }
                } catch (t: Throwable) {
                    jsonPretty(buildJsonObject {
                        put("error", "Music search failed: ${t.message ?: t::class.java.simpleName}")
                    })
                }
            }
        },
        object : Tool {
            override val name = "play_music"
            override val description =
                "Play a YouTube Music song in the embedded player (audio plays on this device, optionally with " +
                    "video). Pass the videoId returned by search_music. Replaces any track currently playing. " +
                    "Call stop_music to stop it."
            override fun inputSchema(): JsonObject = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("video_id", buildJsonObject { put("type", "string"); put("description", "The YouTube Music videoId from a search result.") })
                })
                put("required", JsonArray(listOf(JsonPrimitive("video_id"))))
            }
            override suspend fun run(args: JsonObject): String {
                val videoId = (args["video_id"]?.jsonPrimitive?.contentOrNull ?: "").trim()
                if (videoId.isEmpty()) return jsonPretty(buildJsonObject { put("error", "video_id is required") })
                musicPlayer.play(videoId)
                return "正在播放：$videoId"
            }
        },
        object : Tool {
            override val name = "stop_music"
            override val description = "Stop any music currently playing in the embedded player. Use when the user says stop the music, pause, or no longer wants to listen."
            override fun inputSchema(): JsonObject = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject { })
                put("required", JsonArray(emptyList()))
            }
            override suspend fun run(args: JsonObject): String {
                return if (musicPlayer.stop()) "已停止播放。" else "当前没有正在播放的音乐。"
            }
        },
    )
}