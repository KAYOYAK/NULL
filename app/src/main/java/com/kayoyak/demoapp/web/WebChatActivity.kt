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
 * 网页前端（chat-demo 改造成全本地）的壳。
 *
 * 页面从 `https://appassets.androidplatform.net/assets/web/index.html` 加载：
 * 用 https 源而不是 file://，IndexedDB、Clipboard、字体这类按安全上下文判断的能力
 * 才和浏览器里一致；它不指向任何真实主机，请求全在本进程内被 [WebViewAssetLoader]
 * 拦下。整个前端不发一个网络请求——回复来自 [NullLocalBridge] 接的本机引擎。
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

        // 页面报错要能在 logcat 里看见，否则白屏时无从下手
        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                Log.w("NullWeb", "${message.messageLevel()} ${message.message()} @${message.lineNumber()}")
                return true
            }
        }

        val localBridge = NullLocalBridge(this, container, webView)
        bridge = localBridge
        webView.addJavascriptInterface(localBridge, "NullLocal")

        // targetSdk 37 起系统强制 edge-to-edge：不主动让位，键盘会盖住页面底部的输入框。
        // 这里把状态栏与输入法的高度让出来，页面自己的 100vh 随之缩小，输入框就跟着移动。
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
