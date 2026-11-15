package com.kayoyak.demoapp.web

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebViewAssetLoader
import com.kayoyak.demoapp.DemoApplication

/**
 * Hosts the web front end (DeepSeek's chat-demo, made local).
 *
 * The page is loaded from `https://appassets.androidplatform.net/assets/web/index.html`
 * rather than file://: an https origin is what makes IndexedDB, the clipboard and
 * web fonts behave the way they do in a browser. That host is not real -- every
 * request is answered by [WebViewAssetLoader] from the APK -- so the page issues
 * no network request at all. Replies come from the engine through [NullLocalBridge].
 */
class WebChatActivity : ComponentActivity() {

    private var bridge: NullLocalBridge? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as DemoApplication).container

        val webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mediaPlaybackRequiresUserGesture = false
            settings.textZoom = 100
        }

        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest,
            ): WebResourceResponse? = assetLoader.shouldInterceptRequest(request.url)
        }

        // Page errors must be visible in logcat; a blank screen with no console is unauditable
        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                Log.w("NullWeb", "${message.messageLevel()} ${message.message()} @${message.lineNumber()}")
                return true
            }
        }

        val localBridge = NullLocalBridge(this, container, webView)
        bridge = localBridge
        webView.addJavascriptInterface(localBridge, "NullLocal")

        // targetSdk 37 enforces edge-to-edge, so without this the keyboard covers the
        // composer at the bottom of the page. Reserving the bars and the IME shrinks the
        // view, which shrinks the page's own 100vh, which moves the composer up.
        ViewCompat.setOnApplyWindowInsetsListener(webView) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(0, bars.top, 0, maxOf(bars.bottom, ime.bottom))
            insets
        }

        setContentView(webView)
        webView.loadUrl("https://appassets.androidplatform.net/assets/web/index.html")
    }

    override fun onDestroy() {
        bridge?.dispose()
        bridge = null
        super.onDestroy()
    }
}
