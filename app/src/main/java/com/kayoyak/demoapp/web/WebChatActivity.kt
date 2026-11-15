package com.kayoyak.demoapp.web

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
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
    /** 页面正在等一次文件选择；WebView 只给一次回调，用过就清 */
    private var pendingFileCallback: ValueCallback<Array<Uri>>? = null

    private val pickFile =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            val callback = pendingFileCallback
            pendingFileCallback = null
            callback?.onReceiveValue(if (uri != null) arrayOf(uri) else null)
        }

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

            // 附件按钮在页面里就是一个 <input type=file>；没有这个回调，点它什么也不会发生。
            // 选完把 URI 交回页面，它自己读字节（本机模式下不进上传队列）。
            override fun onShowFileChooser(
                view: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams,
            ): Boolean {
                pendingFileCallback?.onReceiveValue(null)
                pendingFileCallback = callback
                return try {
                    pickFile.launch("*/*")
                    true
                } catch (t: Throwable) {
                    pendingFileCallback = null
                    Log.w("NullWeb", t.javaClass.name, t)
                    false
                }
            }
        }

        val localBridge = NullLocalBridge(this, container, webView)
        bridge = localBridge
        webView.addJavascriptInterface(localBridge, "NullLocal")

        // targetSdk 37 enforces edge-to-edge. Padding alone is not enough: the page
        // sizes itself from its own viewport (--app-viewport-height, falling back to
        // 100dvh), so the WebView itself has to get shorter or the composer stays
        // behind the keyboard. A bottom margin shrinks the view, which shrinks the
        // viewport, which is what moves the composer up; the top inset stays padding
        // because the content should start below the status bar.
        ViewCompat.setOnApplyWindowInsetsListener(webView) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val params = view.layoutParams as? ViewGroup.MarginLayoutParams
            val bottom = maxOf(bars.bottom, ime.bottom)
            if (params != null && params.bottomMargin != bottom) {
                params.bottomMargin = bottom
                view.layoutParams = params
            }
            view.setPadding(0, bars.top, 0, 0)
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
