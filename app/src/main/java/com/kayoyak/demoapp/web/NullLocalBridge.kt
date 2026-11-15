package com.kayoyak.demoapp.web

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.speech.tts.TextToSpeech
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
 * 把网页前端接到本机引擎上。
 *
 * 网页那一侧只认识 `MessageStreamEvent`（见 web/src/services/api/types.ts），
 * 这里负责两件事：把它的请求翻译成引擎能吃的轮次，再把引擎的增量翻译回同样的
 * 事件形状。这样前端不需要知道回复是云端还是本机生成的，历史、分支、导出都不变。
 *
 * 协议（Kotlin 实现，JS 侧在 web/src/services/api/local.ts）：
 *   available() / probe() / start(requestJson) / abort(id)
 *   回推：window.__nullLocalEmit(id, "start"|"text"|"done"|"error", payloadJson)
 */
class NullLocalBridge(
    private val context: Context,
    private val container: AppContainer,
    private val webView: WebView,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = HashMap<String, Job>()
    private val main = Handler(Looper.getMainLooper())

    /** 设备自带语音合成：朗读走它，离线可用，不经过云端 */
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var ttsFailure: String? = null


    init {
        // 构造要一点时间，先准备好；speak() 在没就绪时如实返回原因
        main.post {
            tts = TextToSpeech(context) { status ->
                ttsReady = status == TextToSpeech.SUCCESS
                if (!ttsReady) {
                    ttsFailure = "系统语音引擎不可用"
                }
            }.apply {
                language = Locale.getDefault()
            }
        }
    }

    @JavascriptInterface
    fun speak(text: String): String {
        if (text.isBlank()) {
            return "ok"
        }
        if (!ttsReady) {
            return ttsFailure ?: "语音引擎还在初始化"
        }
        val engine = tts ?: return "系统语音引擎不可用"
        main.post { engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "null-local-tts") }
        return "ok"
    }

    @JavascriptInterface
    fun stopSpeak() {
        main.post { tts?.stop() }
    }

    @JavascriptInterface
    fun available(): String = "1"

    /** 引擎状态一句话，网页用它决定能不能发消息 */
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

    /** 页面销毁时停掉还在生成的轮次，避免桥接对象被引擎回调拖着不放 */
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
                emit(id, "error", JSONObject().put("message", notReadyReason(state)))
                return
            }

            val prompt = request.optString("prompt")
            if (prompt.isBlank()) {
                emit(id, "error", JSONObject().put("message", "空提问"))
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

            // 系统提示在加载模型时就定了（LlmEngine.load 的 systemPrompt），
            // 引擎已经就绪时改不了；第一轮把它拼进问题，后续轮次靠历史携带。
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
                        emit(id, "error", JSONObject().put("message", event.error.readable()))
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            emit(id, "error", JSONObject().put("message", error.readable()))
        } finally {
            synchronized(jobs) { jobs.remove(id) }
        }
    }

    private fun notReadyReason(state: EngineState): String = when (state) {
        is EngineState.Loading -> "模型正在加载，稍等再发"
        EngineState.Idle -> "还没有加载模型：先在应用里选一个模型并启动引擎"
        is EngineState.Ready -> ""
    }

    private fun Throwable.readable(): String = message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName
}
