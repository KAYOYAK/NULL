package top.nullcmd.app.web

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.SystemClock
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
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.nullcmd.app.data.settings.AppTheme
import top.nullcmd.app.diagnostics.AppLock
import top.nullcmd.app.theme.DemoAppTheme
import top.nullcmd.app.ui.lock.DeviceAuth
import top.nullcmd.app.ui.lock.LockScreen
import android.Manifest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebViewAssetLoader
import android.widget.FrameLayout
import top.nullcmd.app.DemoApplication

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

    private companion object {
        /** 本应用私有：只有自己的快捷方式会发它 */
        const val ACTION_NEW_CHAT = "top.nullcmd.app.web.NEW_CHAT"
    }

    private var bridge: NullLocalBridge? = null

    /**
     * The same lock the native screens use, on the page.
     *
     * The page is the entry now, so a lock that only covered the Compose screens
     * would be a lock the launcher walks around: tapping the icon would land in an
     * unlocked conversation.
     */
    private var leftAt by mutableStateOf<Long?>(null)
    private var unlockedAt by mutableStateOf<Long?>(null)
    private lateinit var lockOverlay: ComposeView
    private lateinit var rootView: FrameLayout

    /** 桌面快捷方式送来的「新对话」请求 */
    private var pendingNewChat = false

    /** Text shared in from another app, held until the page can take it. */
    private var pendingShare: String? = null
    private var pageLoaded = false
    /** 页面正在等一次文件选择；WebView 只给一次回调，用过就清 */
    private var pendingFileCallback: ValueCallback<Array<Uri>>? = null

    /** 页面按下麦克风时才申请：一进入口就弹权限是最快让人卸载应用的方式 */
    private val micPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                bridge?.startListeningNow()
            }
        }

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

            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                pageLoaded = true
                shellProbeScript?.let { runCatching { webView.evaluateJavascript(it, null) } }
                if (pendingNewChat) {
                    pendingNewChat = false
                    bridge?.pushNewChat()
                }
                pendingShare?.let { text ->
                    pendingShare = null
                    bridge?.pushSharedText(text)
                }
            }
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
        localBridge.requestMicrophone = {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
        bridge = localBridge
        // 页面自己有主题设置；外壳颜色跟它，而不是跟系统。
        localBridge.onShellColor = { colour ->
            shellColour = colour
            applyWindowBackground()
        }
        localBridge.onShellTheme = { dark ->
            shellDark = dark
            applyWindowBackground()
        }
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
            // 刘海/挖孔：systemBars 不含它，单独取出来和状态栏高度取大者。
            val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            val top = maxOf(bars.top, cutout.top)
            val bottomInset = maxOf(bars.bottom, cutout.bottom)
            val params = view.layoutParams as? ViewGroup.MarginLayoutParams
            val bottom = maxOf(bottomInset, ime.bottom)
            if (params != null && params.bottomMargin != bottom) {
                params.bottomMargin = bottom
                view.layoutParams = params
            }
            // WebView 不把 padding 用在内容区上（它按自己的视口画），所以
            // 顶部让出状态栏这件事必须由外层容器做；底部仍用 margin 缩短视口。
            rootView?.setPadding(0, top, 0, 0)
            insets
        }

        val root = FrameLayout(this)
        rootView = root
        root.addView(
            webView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )

        lockOverlay = ComposeView(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            setContent {
                val settings = container.settingsStore.settings.collectAsStateWithLifecycle().value
                DemoAppTheme(theme = settings.chosenTheme, fontScale = settings.fontScale) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        LockScreen(
                            onUnlocked = {
                                unlockedAt = SystemClock.elapsedRealtime()
                                leftAt = null
                                syncLock()
                            },
                        )
                    }
                }
            }
        }
        root.addView(lockOverlay)

        setContentView(root)
        applyWindowBackground()
        syncLock()
        takeShare(intent)
        takeNewChat(intent)
        webView.loadUrl("https://appassets.androidplatform.net/assets/web/index.html")

        // 观察页面的深浅色 class，一次注入，之后每次切换都会回报。
        val shellProbe = "(function(){function r(){try{var d=document.body&&document.body.classList.contains('dark');" +
            "if(window.NullLocal&&window.NullLocal.setShellColor){window.NullLocal.setShellColor(getComputedStyle(document.body).backgroundColor)}" +
            "if(window.NullLocal&&window.NullLocal.setShellTheme){window.NullLocal.setShellTheme(!!d)}}catch(e){}}" +
            "r();new MutationObserver(r).observe(document.body,{attributes:true,attributeFilter:['class']});})()"
        shellProbeScript = shellProbe

    }

    /**
     * Shows or hides the lock over the page.
     *
     * Called on create, on resume and after an unlock, because the answer depends
     * on how long the app was away -- which only `onStart` knows.
     */
    private fun syncLock() {
        val settings = (application as DemoApplication).container.settingsStore.settings.value
        val locked = AppLock.isLocked(
            enabled = settings.lockEnabled,
            leftAt = leftAt,
            authenticatedAt = unlockedAt,
            now = SystemClock.elapsedRealtime(),
            canAuthenticate = DeviceAuth.canAuthenticate(this),
        )
        lockOverlay.visibility = if (locked) android.view.View.VISIBLE else android.view.View.GONE
    }

    /** Text shared in from another app, delivered once the page can accept it. */
    private fun takeNewChat(intent: Intent?) {
        if (intent?.action == ACTION_NEW_CHAT) {
            if (pageLoaded) {
                bridge?.pushNewChat()
            } else {
                pendingNewChat = true
            }
        }
    }

    private fun takeShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) {
            return
        }
        if (intent.type?.startsWith("text/") != true) {
            return
        }
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.trim()
        if (text.isNullOrEmpty()) {
            return
        }
        if (pageLoaded) {
            bridge?.pushSharedText(text)
        } else {
            pendingShare = text
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        takeShare(intent)
        takeNewChat(intent)
    }

    override fun onStart() {
        super.onStart()
        applyWindowBackground()
        syncLock()
    }

    /**
     * Paints the window to match the page.
     *
     * Edge-to-edge leaves the space behind the system bars to the window, which
     * kept its default while the page turned dark: a white band under a black
     * conversation. Both are read from the same setting the page uses.
     */
    /** 页面加载完成后注入的深浅色观察脚本。 */
    private var shellProbeScript: String? = null

    /** 页面回报的背景色；null 表示页面还没说话。 */
    private var shellColour: Int? = null

    /** 页面回报的深浅色；null 表示页面还没说话，暂时按设置推断。 */
    private var shellDark: Boolean? = null

    private fun applyWindowBackground() {
        val settings = (application as DemoApplication).container.settingsStore.settings.value
        val dark = when (settings.chosenTheme) {
            AppTheme.LIGHT -> false
            AppTheme.DARK -> true
            AppTheme.SYSTEM ->
                (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                    Configuration.UI_MODE_NIGHT_YES
        }
        val effective = shellDark ?: dark
        val colour = shellColour ?: if (effective) 0xFF121212.toInt() else 0xFFFFFFFF.toInt()
        // 图标明暗按实际底色算亮度，而不是靠 dark 标志。
        val luminance = ((colour shr 16 and 0xFF) * 299 + (colour shr 8 and 0xFF) * 587 + (colour and 0xFF) * 114) / 1000
        val lightIcons = luminance < 128
        rootView.setBackgroundColor(colour)
        window.decorView.setBackgroundColor(colour)
        // 旧式主题把导航栏画成黑色；显式上色，并关掉对比度遮罩。
        // 两个栏的颜色必须和页面底色一致，否则浅色页面上会出现黑带。
        @Suppress("DEPRECATION")
        run {
            window.statusBarColor = colour
            window.navigationBarColor = colour
            window.isNavigationBarContrastEnforced = false
        }
        // 图标颜色也得跟着换：浅色主题 + 系统深色时，白色图标会压在白色页面上。
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !lightIcons
            isAppearanceLightNavigationBars = !lightIcons
        }
    }

    override fun onStop() {
        // Recorded here rather than on pause: the lock timeout is about being away,
        // and a permission dialog should not count as leaving.
        leftAt = SystemClock.elapsedRealtime()
        super.onStop()
    }

    override fun onDestroy() {
        bridge?.dispose()
        bridge = null
        super.onDestroy()
    }
}
