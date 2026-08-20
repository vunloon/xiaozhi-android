package com.xiaozhi.client.ui.components

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xiaozhi.client.tools.HttpTools
import com.xiaozhi.client.tools.embedUrl
import com.xiaozhi.client.tools.musicPlayer

/**
 * Embedded YouTube (nocookie) player for the play_music/stop_music tools.
 * Visible while a track is loaded; closes itself when the user taps ✕ or
 * stop_music runs. If the embed is refused (region/embed-disabled video), an
 * in-place fallback offers to open the track in the YouTube app/browser.
 */
@Composable
fun MusicPlayerCard(
    modifier: Modifier = Modifier,
    accent: Color,
    text: Color,
    dim: Color,
) {
    val state by musicPlayer.state.collectAsStateWithLifecycle()
    val videoId = state.videoId ?: return

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(accent.copy(alpha = 0.12f))
            .padding(8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = state.title.ifBlank { "Music" },
                color = text,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = { musicPlayer.stop() },
                modifier = Modifier.size(24.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Close",
                    tint = dim,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        EmbeddedVideo(videoId = videoId, accent = accent, text = text, dim = dim)
        Text(
            text = "Stop with ✕ above or “stop music”",
            color = dim,
            fontSize = 11.sp,
        )
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun EmbeddedVideo(
    videoId: String,
    accent: Color,
    text: Color,
    dim: Color,
) {
    val context = LocalContext.current
    val url = embedUrl(videoId)
    var error by remember(videoId) { mutableStateOf(false) }

    if (error) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black.copy(alpha = 0.15f))
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        ) {
            Text(
                text = "YouTube playback is unavailable in the embedded player.",
                color = text,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
            )
            TextButton(onClick = {
                val intent = android.content.Intent(
                    android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse("https://www.youtube.com/watch?v=$videoId"),
                )
                runCatching { context.startActivity(intent) }
            }) {
                Text("Open in YouTube", color = accent, fontSize = 12.sp)
            }
        }
        return
    }

    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                settings.cacheMode = WebSettings.LOAD_DEFAULT
                settings.userAgentString = HttpTools.BROWSER_UA
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                webViewClient = object : WebViewClient() {
                    override fun onReceivedError(
                        view: WebView,
                        request: WebResourceRequest,
                        webError: WebResourceError,
                    ) {
                        if (request.isForMainFrame) {
                            android.util.Log.w("MusicCard", "embed error ${webError.errorCode}: ${webError.description}")
                            error = true
                        }
                    }
                }
                loadUrl(url)
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp)
            .clip(RoundedCornerShape(8.dp)),
        update = { it.loadUrl(url) },
    )
}