package com.kayoyak.demoapp.web

import android.content.ClipboardManager
import android.content.Intent
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.speech.tts.TextToSpeech
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.kayoyak.demoapp.AppContainer
import com.kayoyak.demoapp.inference.EngineState
import com.kayoyak.demoapp.inference.EngineTurn
import com.kayoyak.demoapp.MainActivity
import com.kayoyak.demoapp.inference.GenerationConfig
import com.kayoyak.demoapp.inference.GenerationEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import android.util.Base64
import com.kayoyak.demoapp.data.settings.AppTheme
import java.util.Locale
import java.util.UUID

/**
 * Connects the web front end to the engine on this device.
 *
 * The page only understands `MessageStreamEvent` (see
 * web/src/services/api/types.ts). This class does two translations: a request
 * into engine turns, and the engine's deltas back into the same event shapes the
 * cloud transport produced. Nothing above it -- reply rendering, branches,
 * history, export -- can tell which side answered.
 *
 * Protocol (implemented here, consumed by web/src/services/api/local.ts):
 *   available() / probe() / start(requestJson) / abort(id) / speak / stopSpeak
 *   pushed back: window.__nullLocalEmit(id, "start"|"text"|"done"|"error", payloadJson)
 *
 * Answers are machine codes, never sentences. Words a person reads belong to a
 * catalogue, and this bridge has two audiences with two of them: strings.xml for
 * the Compose screens, web/src/i18n for the page.
 */
class NullLocalBridge(
    private val context: Context,
    private val container: AppContainer,
    private val webView: WebView,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = HashMap<String, Job>()
    private val main = Handler(Looper.getMainLooper())

    /** Settings writes are suspending; the page calls them from a click handler. */
    private val settingsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** What the last finished turn occupied, for the page's meter. */
    @Volatile
    private var lastContextTokens: Int? = null

    /** 页面按下麦克风时调用；Activity 注入，负责在缺权限时发起系统授权 */
    var requestMicrophone: (() -> Unit)? = null

    private val voice = VoiceInput(context) { kind, text ->
        emitVoice(kind, text)
    }

    /** The platform's own speech engine: read-aloud runs on the device, offline. */
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var ttsFailure: String? = null

    init {
        // Constructing takes a moment, so start early; speak() reports honestly
        // when it is asked before the engine is ready.
        main.post {
            tts = TextToSpeech(context) { status ->
                ttsReady = status == TextToSpeech.SUCCESS
                if (!ttsReady) {
                    ttsFailure = "tts_unavailable"
                }
            }.apply {
                language = Locale.getDefault()
            }
        }
    }

    @JavascriptInterface
    fun available(): String = "1"

    /** Engine state as one code; the page decides whether it may send. */
    @JavascriptInterface
    fun probe(): String = when (val state = container.llmEngine.state.value) {
        is EngineState.Ready -> "ready"
        is EngineState.Loading -> "loading"
        else -> "idle"
    }

    @JavascriptInterface
    fun start(request: String): String {
        val id = UUID.randomUUID().toString()
        synchronized(jobs) {
            jobs[id] = scope.launch { run(id, request) }
        }
        return id
    }

    @JavascriptInterface
    fun abort(id: String) {
        val job = synchronized(jobs) { jobs.remove(id) }
        job?.cancel()
    }

    /**
     * Puts an image from the system clipboard into the page.
     *
     * The web clipboard API cannot do this inside a WebView: the platform exposes no
     * clipboard-read resource for a page to request, so navigator.clipboard.read()
     * is refused no matter what the page does. The clipboard is read here instead,
     * on the app's own behalf, and the bytes are pushed to the page as an attachment.
     *
     * Returns "ok" when bytes were pushed, "empty" when the clipboard holds no image,
     * or a code the page translates.
     */
    /**
     * Opens one of NULL's own screens, which stay native because the page cannot
     * provide them: model downloads and verification, the licences, the app lock.
     */
    @JavascriptInterface
    fun openNative(screen: String): Boolean {
        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_SCREEN, screen)
        }
        return try {
            // A cross-fade, not the platform's slide: the page and the native
            // screens share a palette, and a full-page push reads as switching
            // apps rather than opening something over the conversation.
            val options = android.app.ActivityOptions.makeCustomAnimation(
                context,
                android.R.anim.fade_in,
                android.R.anim.fade_out,
            )
            context.startActivity(intent, options.toBundle())
            true
        } catch (error: Throwable) {
            Log.w("NullWeb", error.javaClass.name, error)
            false
        }
    }

    /** 桌面快捷方式要求开一段新对话。 */
    fun pushNewChat() {
        val script = "(function(){var n=0;(function go(){if(window.__nullNewChat){window.__nullNewChat();}else if(n++<40){setTimeout(go,100);}})();})()"
        main.post { webView.evaluateJavascript(script, null) }
    }

    /**
     * Hands text from another app to the page's composer.
     *
     * The hook is registered by React, which mounts after the document loads, so a
     * single evaluation can land on a page that has the DOM but not the listener.
     * Poll briefly instead of dropping the text.
     */
    fun pushSharedText(text: String) {
        val script = buildString {
            append("(function(value){var tries=0;(function deliver(){")
            append("if(window.__nullSharedText){window.__nullSharedText(value);}")
            append("else if(tries++<40){setTimeout(deliver,100);}})();})(")
            append(JSONObject.quote(text)).append(')')
        }
        main.post { webView.evaluateJavascript(script, null) }
    }

    /**
     * The page's sessions, kept in the app's encrypted store rather than IndexedDB.
     *
     * Four calls, because a session document is opaque here: the page serialises it
     * and this layer only stores bytes it cannot read without the app's key.
     */
    @JavascriptInterface
    fun historyList(): String = JSONArray(container.webHistory.ids()).toString()

    @JavascriptInterface
    fun historyRead(id: String): String? = container.webHistory.read(id)

    @JavascriptInterface
    fun historyWrite(id: String, json: String): Boolean = container.webHistory.write(id, json)

    @JavascriptInterface
    fun historyDelete(id: String): Boolean =
        runBlocking { container.webHistory.delete(id) }

    /**
     * The saved prompts, as the settings screen stores them.
     *
     * They are edited natively -- the settings screen already has the add/delete
     * UI -- and read here so the composer can offer them without a second copy of
     * that editing surface.
     */
    @JavascriptInterface
    fun prompts(): String {
        val list = JSONArray()
        container.settingsStore.settings.value.savedPrompts.forEach { prompt ->
            list.put(JSONObject().put("name", prompt.name).put("body", prompt.body))
        }
        return JSONObject().put("prompts", list).toString()
    }

    /**
     * Hands a reply to the system share sheet.
     *
     * chat-demo copies; sharing a reply was the native chat screen's job, and that
     * screen is no longer the one in front. The chooser is Android's, so the reply
     * goes wherever the user picks and through no server of ours.
     */
    /**
     * The theme the app is set to: "system", "light" or "dark".
     *
     * One preference, read by both UIs. The page used to keep its own, so a phone
     * in dark mode showed a dark model manager over a white conversation.
     */
    @JavascriptInterface
    fun theme(): String =
        container.settingsStore.settings.value.chosenTheme.name.lowercase(Locale.ROOT)

    @JavascriptInterface
    fun setTheme(theme: String): Boolean {
        val parsed = AppTheme.entries.firstOrNull { it.name.equals(theme, ignoreCase = true) }
            ?: return false
        settingsScope.launch {
            container.settingsStore.update { it.copy(theme = parsed.name) }
        }
        return true
    }

    @JavascriptInterface
    fun share(text: String): Boolean {
        if (text.isBlank()) {
            return false
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        return try {
            context.startActivity(Intent.createChooser(send, null))
            true
        } catch (error: Throwable) {
            Log.w("NullWeb", error.javaClass.name, error)
            false
        }
    }

    @JavascriptInterface
    fun pasteImage(): String {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
            ?: return "paste_unavailable"
        if (!clipboard.hasPrimaryClip()) {
            return "empty"
        }
        val item = clipboard.primaryClip?.getItemAt(0) ?: return "empty"
        val uri = item.uri ?: return "empty"
        val mime = context.contentResolver.getType(uri) ?: "image/*"
        if (!mime.startsWith("image/")) {
            return "empty"
        }
        return try {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            if (bytes == null || bytes.isEmpty()) {
                return "empty"
            }
            val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
            main.post {
                val script = buildString {
                    append("window.__nullPaste&&window.__nullPaste(")
                    append(JSONObject.quote(mime)).append(',')
                    append(JSONObject.quote(encoded)).append(')')
                }
                webView.evaluateJavascript(script, null)
            }
            "ok"
        } catch (error: Throwable) {
            Log.w("NullWeb", error.javaClass.name, error)
            "paste_failed"
        }
    }

    @JavascriptInterface
    fun startListening(): String {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            main.post { requestMicrophone?.invoke() }
            return "voice_requesting"
        }
        return voice.start()
    }

    /** 授权回来后由 Activity 调它，真正开始听 */
    fun startListeningNow(): String = voice.start()

    @JavascriptInterface
    fun stopListening() {
        voice.stop()
    }

    @JavascriptInterface
    fun speak(text: String): String {
        if (text.isBlank()) {
            return "ok"
        }
        if (!ttsReady) {
            return ttsFailure ?: "tts_initialising"
        }
        val engine = tts ?: return "tts_unavailable"
        main.post { engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "null-local-tts") }
        return "ok"
    }

    @JavascriptInterface
    fun stopSpeak() {
        main.post { tts?.stop() }
    }

    /** Stops in-flight turns when the page goes away, so the bridge does not
     * outlive its WebView through an engine callback. */
    fun dispose() {
        settingsScope.cancel()
        main.post { voice.dispose() }
        main.post { tts?.shutdown() }
        tts = null
        synchronized(jobs) {
            jobs.values.forEach { it.cancel() }
            jobs.clear()
        }
        scope.cancel()
    }

    /** 语音事件没有请求 id：麦克风一次只有一个，页面按全局通道收 */
    private fun emitVoice(kind: String, text: String) {
        val script = buildString {
            append("window.__nullVoice&&window.__nullVoice(")
            append(JSONObject.quote(kind)).append(',')
            append(JSONObject.quote(text)).append(')')
        }
        main.post { webView.evaluateJavascript(script, null) }
    }

    private fun emit(id: String, kind: String, payload: JSONObject = JSONObject()) {
        val script = buildString {
            append("window.__nullLocalEmit&&window.__nullLocalEmit(")
            append(JSONObject.quote(id)).append(',')
            append(JSONObject.quote(kind)).append(',')
            append(JSONObject.quote(payload.toString())).append(')')
        }
        main.post { webView.evaluateJavascript(script, null) }
    }

    private suspend fun run(id: String, raw: String) {
        try {
            val request = JSONObject(raw)
            val state = container.llmEngine.state.value
            if (state !is EngineState.Ready) {
                emit(id, "error", failure(notReadyReason(state)))
                return
            }

            val prompt = request.optString("prompt")
            if (prompt.isBlank()) {
                emit(id, "error", failure("empty_prompt"))
                return
            }

            emit(id, "start", JSONObject().put("model", state.modelId))

            val history = ArrayList<EngineTurn>()
            val turns = request.optJSONArray("history") ?: JSONArray()
            for (index in 0 until turns.length()) {
                val turn = turns.getJSONObject(index)
                val text = turn.optString("text")
                if (text.isNotBlank()) {
                    history += EngineTurn(fromUser = turn.optBoolean("fromUser"), text = text)
                }
            }
            container.llmEngine.resetConversation(history)

            // The system prompt is fixed when the model is loaded (LlmEngine.load's
            // systemPrompt) and cannot be changed on a ready engine, so the first
            // turn carries it in the text and later turns inherit it from history.
            val system = request.optString("system").trim()
            val text = if (history.isEmpty() && system.isNotEmpty()) "$system\n\n$prompt" else prompt

            val maxTokens = request.optInt("maxTokens", 1024).coerceIn(16, 4096)
            val config = GenerationConfig(maxTokens = maxTokens)

            // Images arrive base64-encoded, already downscaled by the page. Decoding
            // happens here so the native layer never receives a string it has to
            // parse, and a malformed frame is reported as a code the page can read.
            val images = ArrayList<ByteArray>()
            val encoded = request.optJSONArray("images") ?: JSONArray()
            for (index in 0 until encoded.length()) {
                val raw = encoded.optString(index)
                if (raw.isBlank()) {
                    continue
                }
                val bytes = runCatching { android.util.Base64.decode(raw, android.util.Base64.DEFAULT) }
                    .getOrNull()
                if (bytes == null || bytes.isEmpty()) {
                    emit(id, "error", failure("bad_image"))
                    return
                }
                images += bytes
            }

            container.llmEngine.generate(text, config, images).collect { event ->
                when (event) {
                    is GenerationEvent.Token ->
                        if (event.text.isNotEmpty()) {
                            emit(id, "text", JSONObject().put("text", event.text))
                        }

                    is GenerationEvent.Done -> {
                        val exhausted = event.tokenCount >= maxTokens
                        // The runtime's own count, not an estimate: the meter exists to
                        // show what the model actually holds, and a number computed from
                        // character counts would disagree with the engine by a third.
                        lastContextTokens = event.contextTokens
                        emit(
                            id,
                            "done",
                            JSONObject().apply {
                                put("stopReason", if (exhausted) "max_tokens" else "end_turn")
                                // 每条回复的耗时与速度：1.1.2 的聊天页有这一行，
                                // 它是判断"两个模型谁快"的唯一直接依据。
                                put("elapsedMillis", event.elapsedMillis)
                                put("tokenCount", event.tokenCount)
                                event.contextTokens?.let { put("contextUsed", it) }
                                contextWindow()?.let { put("contextWindow", it) }
                            },
                        )
                    }

                    is GenerationEvent.Failed ->
                        emit(id, "error", failure("generation_failed", event.error.readable()))
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            emit(id, "error", failure("generation_failed", error.readable()))
        } finally {
            synchronized(jobs) { jobs.remove(id) }
        }
    }

    /**
     * The window and what the last turn left in it.
     *
     * The page shows this, and uses the window to decide what history to send: the
     * engine holds a conversation per model, but what it is asked to continue is
     * whatever the page hands it on each turn.
     */
    @JavascriptInterface
    fun context(): String = JSONObject().apply {
        lastContextTokens?.let { put("used", it) }
        contextWindow()?.let { put("window", it) }
    }.toString()

    private fun contextWindow(): Int? =
        (container.llmEngine.state.value as? EngineState.Ready)?.contextWindow

    /** A code the page translates, plus the engine's own words as a detail field. */
    private fun failure(code: String, detail: String? = null): JSONObject =
        JSONObject().put("message", code).apply {
            if (!detail.isNullOrBlank()) {
                put("detail", detail)
            }
        }

    private fun notReadyReason(state: EngineState): String = when (state) {
        is EngineState.Loading -> "loading"
        EngineState.Idle -> "missing_model"
        is EngineState.Ready -> ""
    }

    private fun Throwable.readable(): String = message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName
}
