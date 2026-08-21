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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xiaozhi.client.tools.musicPlayer

/**
 * Embedded YouTube player for the play_music/stop_music tools. Visible while a
 * track is loaded; closes itself when the user taps ✕ or stop_music runs. If
 * the embed is refused (region/embed-disabled video), an in-place fallback
 * offers to open the track in the YouTube app/browser.
 *
 * IMPORTANT: nothing on the path to the WebView may clip (rounded-corner clip
 * or graphicsLayer transforms). WebView renders video through an out-of-band
 * SurfaceView; clipping the view hierarchy breaks that compositing and the
 * video area goes black (audio keeps playing). Rounded backgrounds only.
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
            .background(accent.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
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
    var error by remember(videoId) { mutableStateOf(false) }
    // Last videoId loaded into the WebView. A plain holder (not State) —
    // writing it must not schedule recomposition. Survives videoId changes so
    // the update block can tell "same video" from "new video".
    val loadedVideo = remember { arrayOfNulls<String>(1) }

    // Fullscreen (onShowCustomView) state. The overlay renders the custom view
    // in a dialog that fills the window.
    var customView by remember { mutableStateOf<android.view.View?>(null) }
    var customViewCallback by remember { mutableStateOf<android.webkit.WebChromeClient.CustomViewCallback?>(null) }

    if (error) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .background(Color.Black.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
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

    val hideFullscreen: () -> Unit = {
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
        customView = null
    }

    if (customView != null) {
        androidx.activity.compose.BackHandler(onBack = hideFullscreen)
        androidx.compose.ui.window.Dialog(
            onDismissRequest = hideFullscreen,
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        ) {
            AndroidView(
                factory = { ctx ->
                    android.widget.FrameLayout(ctx).apply {
                        setBackgroundColor(android.graphics.Color.BLACK)
                        customView?.let { addView(it) }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(),
            )
        }
    }

    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                WebViewEmbed.configure(
                    ctx,
                    this,
                    onMainFrameError = { error = true },
                    onCustomView = { view, callback ->
                        customView = view
                        customViewCallback = callback
                    },
                    onHideCustomView = {
                        customView = null
                        customViewCallback = null
                    },
                )
                WebViewEmbed.loadVideo(this, videoId)
                loadedVideo[0] = videoId
            }
        },
        // NO clip, NO graphicsLayer transforms: the WebView's video composites
        // through an out-of-band surface; clipping this node turns the video
        // area black while audio keeps playing.
        // Exactly 16:9 — the video surface letterboxes/centers itself within
        // the view; a fixed-height strip lets it land outside the visible
        // window (blank card, audio only). Matching the video's aspect ratio
        // removes all the geometry slack.
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f),
        update = { wv ->
            // NEVER reload the same video: `update` runs after factory and on
            // every recomposition — an unconditional load here restarts the
            // player over and over (blank card, playback never starts). Only a
            // genuinely new videoId should trigger a load.
            if (loadedVideo[0] != videoId) {
                loadedVideo[0] = videoId
                WebViewEmbed.loadVideo(wv, videoId)
            }
        },
    )
}