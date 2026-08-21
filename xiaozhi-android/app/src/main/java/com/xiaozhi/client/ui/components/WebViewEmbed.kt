package com.xiaozhi.client.ui.components

import android.content.Context
import android.util.Log
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * Shared WebView configuration for the embedded YouTube player. Kept in one
 * place so the debug test activity (app/src/debug) exercises the exact
 * production path.
 *
 * Requirements discovered the hard way (each failure mode was live on device):
 *  - Strip the `X-Requested-With` header (app package) → else YouTube shows a
 *    "configuration error" card. Needs androidx.webkit + WebView 108+.
 *  - ALSO strip the `; <package>` token WebView appends to the UA → else the
 *    player loads but refuses to start (blank/black).
 *  - Grant the PROTECTED_MEDIA_ID permission request → music videos are
 *    Widevine-DRM'd; without the grant the player loads and stays black with
 *    no audio and no error surfaced.
 *  - WebChromeClient present, mediaPlaybackRequiresUserGesture=false.
 */
object WebViewEmbed {
    private const val TAG = "MusicCard"

    fun configure(
        context: Context,
        wv: WebView,
        onMainFrameError: (String) -> Unit,
        onCustomView: (View, WebChromeClient.CustomViewCallback) -> Unit = { _, _ -> },
        onHideCustomView: () -> Unit = {},
    ) {
        wv.setBackgroundColor(android.graphics.Color.BLACK)
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.settings.mediaPlaybackRequiresUserGesture = false
        wv.settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        wv.settings.cacheMode = WebSettings.LOAD_DEFAULT
        wv.settings.loadWithOverviewMode = true
        wv.settings.useWideViewPort = true

        // Remove the app identity from the UA — both the `; com.package` token
        // and the `wv` WebView marker inside the platform parenthetical.
        // YouTube's embed player treats any of these (plus X-Requested-With)
        // as an app-embedding context and refuses to play, redirecting the
        // whole WebView to m.youtube.com.
        wv.settings.userAgentString = wv.settings.userAgentString
            .replace(Regex(";?\\s*${Regex.escape(context.packageName)}"), "")
            .replace(Regex(";?\\s*wv(?=[;)\\s]|$)"), "")

        if (androidx.webkit.WebViewFeature.isFeatureSupported(
                androidx.webkit.WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST,
            )
        ) {
            androidx.webkit.WebSettingsCompat.setRequestedWithHeaderOriginAllowList(
                wv.settings,
                emptySet(),
            )
        }

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)

        wv.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                Log.d(TAG, "console[${msg.messageLevel()}] ${msg.sourceId()}:${msg.lineNumber()}: ${msg.message()}")
                return true
            }

            override fun onPermissionRequest(request: PermissionRequest) {
                // DRM (EME): YouTube music videos will not decrypt without this
                // grant — the player silently stays black.
                if (request.resources.contains(PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID)) {
                    Log.d(TAG, "granting protected-media (DRM) permission")
                    request.grant(request.resources)
                } else {
                    super.onPermissionRequest(request)
                }
            }

            // Fullscreen video: the host must swap in the custom view itself.
            // Without these overrides the player's fullscreen button does
            // nothing useful (or CSS-stretches inside the tiny card).
            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                Log.d(TAG, "custom view (fullscreen) shown")
                onCustomView(view, callback)
            }

            override fun onHideCustomView() {
                Log.d(TAG, "custom view (fullscreen) hidden")
                onHideCustomView()
            }
        }

        wv.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                Log.d(TAG, "page start: $url")
                // If the player breaks out of the shell (it navigates the main
                // frame to m.youtube.com when it refuses the context), surface
                // the error fallback instead of letting YouTube take the card.
                if (!url.startsWith(SHELL_ORIGIN)) {
                    Log.w(TAG, "main frame left the shell: $url")
                    onMainFrameError("left shell: $url")
                }
            }

            override fun onPageFinished(view: WebView, url: String) {
                Log.d(TAG, "page done: $url")
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError,
            ) {
                if (request.isForMainFrame) {
                    Log.w(TAG, "embed error ${error.errorCode}: ${error.description}")
                    onMainFrameError("${error.errorCode}: ${error.description}")
                }
            }

            override fun onReceivedHttpError(
                view: WebView,
                request: WebResourceRequest,
                errorResponse: WebResourceResponse,
            ) {
                Log.w(TAG, "http ${errorResponse.statusCode} for ${request.url}")
            }
        }
    }

    /**
     * Loads the video into [wv] as a cross-origin iframe inside a local HTML
     * shell. This is the only embedding YouTube's player accepts here: loaded
     * directly (top-level /embed), the player detects an app WebView and
     * refuses to play (idle player + redirect to the m.youtube.com watch
     * page). Framed from a real (non-YouTube) origin it behaves exactly like
     * a legitimate website embed and plays — verified on WebView 113 with DRM
     * music content.
     */
    fun loadVideo(wv: WebView, videoId: String) {
        val id = java.net.URLEncoder.encode(videoId, "UTF-8")
        val html = """
            <!DOCTYPE html><html><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <style>
              html,body{margin:0;padding:0;background:#000;overflow:hidden}
              body{display:flex;align-items:center;justify-content:center;min-height:100vh}
              iframe{width:100%;aspect-ratio:16/9;border:0;display:block}
            </style></head>
            <body>
            <iframe
              src="https://www.youtube.com/embed/$id?autoplay=1&playsinline=1&rel=0"
              allow="autoplay; encrypted-media; fullscreen"
              allowfullscreen></iframe>
            </body></html>
        """.trimIndent()
        wv.loadDataWithBaseURL(SHELL_ORIGIN, html, "text/html", "utf-8", null)
    }

    private const val SHELL_ORIGIN = "https://xiaozhi.local/"
}
