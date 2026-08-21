package com.xiaozhi.client.debug

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.xiaozhi.client.tools.embedUrl
import com.xiaozhi.client.tools.musicPlayer
import com.xiaozhi.client.ui.components.MusicPlayerCard
import com.xiaozhi.client.ui.components.WebViewEmbed
import com.xiaozhi.client.ui.theme.ThemeId
import com.xiaozhi.client.ui.theme.XiaozhiPalettes
import com.xiaozhi.client.ui.theme.XiaozhiTheme

/**
 * Debug-only harness for the YouTube embed recipe. Lets us A/B the embedding
 * strategies on a device/emulator without activation/chat/MCP:
 *
 *   adb shell am start -n com.xiaozhi.client.debug/com.xiaozhi.client.debug.WebViewTestActivity \
 *     --es recipe card --es vid jNQXAC9IVRw
 *
 * recipes: direct | shell-cross | shell-same | card
 *  - card: composes the REAL MusicPlayerCard (exact production layout) at the
 *    same position it sits in the chat screen.
 */
class WebViewTestActivity : ComponentActivity() {

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val vid = intent.getStringExtra("vid") ?: "jNQXAC9IVRw"
        val recipe = intent.getStringExtra("recipe") ?: "direct"

        when (recipe) {
            "card" -> {
                musicPlayer.play(vid, "Test track")
                setContent {
                    val palette = XiaozhiPalettes.getValue(ThemeId.DARK)
                    XiaozhiTheme(palette = palette) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            Column(modifier = Modifier.align(Alignment.BottomCenter)) {
                                MusicPlayerCard(
                                    modifier = Modifier.fillMaxWidth(),
                                    accent = palette.accent,
                                    text = palette.text,
                                    dim = palette.dim,
                                )
                            }
                        }
                    }
                }
            }
            else -> {
                val wv = WebView(this)
                WebViewEmbed.configure(this, wv, onMainFrameError = {})
                when (recipe) {
                    "direct" -> wv.loadUrl(embedUrl(vid))
                    "shell-cross" -> wv.loadDataWithBaseURL(
                        "https://xiaozhi.local/", shell(vid), "text/html", "utf-8", null,
                    )
                    "shell-same" -> wv.loadDataWithBaseURL(
                        "https://www.youtube.com", shell(vid), "text/html", "utf-8", null,
                    )
                }
                setContentView(wv)
            }
        }
    }

    private fun shell(vid: String): String = """
        <!DOCTYPE html><html><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1">
        <style>html,body{margin:0;height:100%;background:#000;overflow:hidden}
        iframe{width:100%;height:100%;border:0}</style></head>
        <body><iframe
          src="https://www.youtube.com/embed/$vid?autoplay=1&playsinline=1&rel=0"
          allow="autoplay; encrypted-media; fullscreen" allowfullscreen></iframe>
        </body></html>
    """.trimIndent()
}
