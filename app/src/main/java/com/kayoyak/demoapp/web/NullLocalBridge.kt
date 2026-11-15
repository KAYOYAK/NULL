package com.kayoyak.demoapp.web

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.kayoyak.demoapp.AppContainer
import com.kayoyak.demoapp.inference.EngineState
import com.kayoyak.demoapp.inference.EngineTurn
import com.kayoyak.demoapp.inference.GenerationConfig
import com.kayoyak.demoapp.inference.GenerationEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
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
        main.post { tts?.shutdown() }
        tts = null
        synchronized(jobs) {
            jobs.values.forEach { it.cancel() }
            jobs.clear()
        }
        scope.cancel()
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

            container.llmEngine.generate(text, config).collect { event ->
                when (event) {
                    is GenerationEvent.Token ->
                        if (event.text.isNotEmpty()) {
                            emit(id, "text", JSONObject().put("text", event.text))
                        }

                    is GenerationEvent.Done -> {
                        val exhausted = event.tokenCount >= maxTokens
                        emit(
                            id,
                            "done",
                            JSONObject().put("stopReason", if (exhausted) "max_tokens" else "end_turn"),
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
