package com.xiaozhi.client.tools

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-app music player state, driven by the play_music/stop_music tools and
 * rendered by the UI (MusicPlayerCard). Playback is a YouTube embed WebView
 * (audio plays on this device), mirroring the web client's YouTube iframe.
 */
data class MusicPlayerState(
    val videoId: String? = null,
    val title: String = "",
    val playing: Boolean = false,
)

class MusicPlayer {
    private val _state = MutableStateFlow(MusicPlayerState())
    val state: StateFlow<MusicPlayerState> = _state.asStateFlow()

    fun play(videoId: String, title: String = "") {
        _state.value = MusicPlayerState(videoId = videoId, title = title.ifBlank { videoId }, playing = true)
    }

    fun stop(): Boolean {
        if (_state.value.videoId == null) return false
        _state.value = MusicPlayerState()
        return true
    }
}

// youtube.com (not youtube-nocookie.com): the privacy host is consent-walled
// and often renders blank inside an Android WebView. Same params as the web
// client's iframe so behaviour matches.
fun embedUrl(videoId: String): String =
    "https://www.youtube.com/embed/${java.net.URLEncoder.encode(videoId, "UTF-8")}?autoplay=1&playsinline=1&rel=0"

val musicPlayer = MusicPlayer()