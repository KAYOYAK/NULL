package com.kayoyak.demoapp

import kotlinx.coroutines.flow.MutableStateFlow

import android.app.Application
import androidx.core.net.toUri
import com.kayoyak.demoapp.diagnostics.CrashLog
import com.kayoyak.demoapp.diagnostics.Speech
import android.content.Context
import com.kayoyak.demoapp.data.webhistory.EncryptedHistory
import com.kayoyak.demoapp.data.settings.FileSettingsStore
import com.kayoyak.demoapp.data.settings.SettingsStore
import com.kayoyak.demoapp.inference.LiteRtLlmEngine
import com.kayoyak.demoapp.inference.LlmEngine
import com.kayoyak.demoapp.model.ModelDownloadCoordinator
import com.kayoyak.demoapp.model.ModelDownloader
import com.kayoyak.demoapp.model.ModelStore
import com.kayoyak.demoapp.model.FileModelRegistry
import com.kayoyak.demoapp.model.ModelImporter
import com.kayoyak.demoapp.model.DeviceMemory
import com.kayoyak.demoapp.model.ModelRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.kayoyak.demoapp.inference.EngineState
import com.kayoyak.demoapp.inference.LocalModel
import com.kayoyak.demoapp.inference.inferenceBackendNamed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Holds the objects that should exist once per process.
 *
 * Built by hand rather than with a dependency-injection framework: the graph is
 * three objects, and a framework would be a larger thing to maintain than the
 * thing it manages.
 */
class AppContainer(context: Context) {

    /**
     * Shared HTTP client.
     *
     * One client, not one per download: a client owns a connection pool and
     * dispatch threads, and building a second for every attempt wastes both.
     */
    val httpClient: OkHttpClient = OkHttpClient.Builder()
        // Time to establish a connection. A slow mobile network can take a
        // while, but not this long.
        .connectTimeout(30, TimeUnit.SECONDS)
        // Time to wait for the *next* byte, not for the whole transfer. A
        // multi-gigabyte download legitimately takes far longer than any total
        // timeout worth setting; what must not happen is hanging forever on a
        // stalled connection.
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        // Deliberately no callTimeout: it would cap the total duration and
        // abort a large but perfectly healthy download.
        .retryOnConnectionFailure(true)
        .build()

    /**
     * Models live in internal storage.
     *
     * Not external storage: these files are the user's business, no other app
     * needs to read them, and internal storage is the one location that is
     * private without asking for a permission.
     */
    val modelStore: ModelStore =
        ModelStore(
            root = File(context.filesDir, "models"),
            // What the platform could actually give this app, which is more than
            // what is free right now: Android evicts other applications' cached
            // files when a large allocation needs the room, and
            // `File.usableSpace` does not count them. Asking here rather than in
            // the store because `StorageManager` needs a Context, and the store is
            // a directory -- which is also why every test builds one without this
            // and keeps the plain figure.
            allocatableBytes = {
                val manager =
                    context.getSystemService(android.os.storage.StorageManager::class.java)
                val target = File(context.filesDir, "models")
                // The filesystem this app is actually on. A device can have more
                // than one, and asking about the wrong one answers a question
                // nobody asked.
                val uuid = manager.getUuidForPath(target)
                manager.getAllocatableBytes(uuid)
            },
        )

    /**
     * How much memory this device has, for judging a model before loading it.
     *
     * Application-scoped because the total does not change, and because the check
     * has to give the same answer from the model list and from the load path.
     */
    val deviceMemory: DeviceMemory = DeviceMemory.from(context)

    /**
     * Reading replies out loud.
     *
     * One instance for the app rather than one per reply: the platform binds a
     * service to speak, and a bind per press is visible as a delay. It is
     * released in DemoApplication, which the platform requires.
     */
    val speech: Speech = Speech(context)

    val modelDownloader: ModelDownloader = ModelDownloader(modelStore, httpClient)

    /**
     * Scope for work that must outlive any screen.
     *
     * SupervisorJob so one failed download cannot take down anything else
     * running in the same scope.
     *
     * IO rather than Default. The work this scope runs is a blocking HTTP read
     * plus file writes: on Default -- a CPU pool sized to the core count -- a
     * single multi-gigabyte download occupies one of those threads for minutes,
     * which on a two-core device is half the pool.
     */
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val engineLoadLock = Mutex()
    private var engineStartRequested = false

    /**
     * Loads the installed model once per process, whoever opens the app first.
     *
     * The model used to be loaded by the chat screen, which was the only way in.
     * The front end is the bundled page now, and a page cannot load a model before
     * it renders: the page would report "no model" on every cold start. The
     * Application owns the engine, so the Application starts it.
     */
    fun startEngineOnce() {
        if (engineStartRequested) {
            return
        }
        engineStartRequested = true
        applicationScope.launch {
            engineLoadLock.withLock {
                val state = llmEngine.state.value
                if (state is EngineState.Ready || state is EngineState.Loading) {
                    return@withLock
                }
                val installed = modelStore.installedModels(modelRegistry.models.value)
                val descriptor = installed.firstOrNull() ?: return@withLock
                val settings = settingsStore.settings.value
                llmEngine.load(
                    model = LocalModel(
                        id = descriptor.id,
                        displayName = descriptor.displayName,
                        file = modelStore.modelFile(descriptor),
                    ),
                    requested = inferenceBackendNamed(settings.backend),
                    systemPrompt = settings.effectiveSystemPrompt,
                    sampling = settings.samplingFor(descriptor.id),
                )
            }
        }
    }


    val downloadCoordinator: ModelDownloadCoordinator =
        ModelDownloadCoordinator(applicationScope, modelStore, modelDownloader::download)

    /**
     * The conversation the history screen asked the chat screen to open.
     *
     * A one-shot hand-off between two destinations that share no lifetime: the
     * history screen sets it and leaves, the chat screen reads it and clears it.
     * Holding the ChatViewModel itself here would tie a screen's state to the
     * application object and keep a conversation in memory for the whole process.
     */
    val chosenConversation = MutableStateFlow<String?>(null)

    /**
     * Text another app shared into this one, waiting to be asked about.
     *
     * A command, like [chosenConversation], and consumed and cleared for the same
     * reason: it describes something that was asked for once, not a state the app
     * is in.
     *
     * It is *put in the composer*, not sent. Sharing a paragraph to this app is a
     * request to ask about it, and the asking is still the user's -- sending on
     * their behalf would spend their battery and their model's attention on a
     * question they had not finished writing.
     */
    val sharedText = MutableStateFlow<String?>(null)

    /**
     * Set when the app was opened from the "new chat" shortcut.
     *
     * A command, consumed and cleared, like [sharedText] and [chosenConversation].
     * It arrives before the chat screen exists and has to survive the trip through
     * the navigation graph, which is why it lives here rather than in the activity.
     */
    val newChatRequested = MutableStateFlow(false)

    /**
     * Which conversation the chat screen has open, for the history list to mark.
     *
     * Separate from [chosenConversation], which is a command -- "open this one" --
     * and is consumed and cleared. This one is state: it says what is on screen
     * now, and the history screen reads it to show which row the user is in.
     */
    val openConversationId = MutableStateFlow<String?>(null)

    /**
     * The choices that outlive a screen.
     *
     * In the container, not in a ViewModel: the inference engine reads the chosen
     * backend when it loads a model, and that happens from whichever screen asked
     * for the load.
     */
    val settingsStore: SettingsStore = FileSettingsStore(File(context.filesDir, "settings.json"))

    /**
     * Which models exist: the catalogue, plus whatever the user imported.
     *
     * Everything that lists models asks this. The catalogue alone would show an
     * imported model on the screen that added it and nowhere else.
     */
    val modelRegistry: ModelRegistry =
        FileModelRegistry(File(context.filesDir, "imported-models.json"))

    val modelImporter: ModelImporter = ModelImporter(modelStore)

    /**
     * Opens the file the user picked.
     *
     * Held here rather than in a ViewModel so the ViewModels stay free of
     * Android's content resolver, which is what makes the import logic testable
     * without a device.
     */
    val openPickedFile: (String) -> java.io.InputStream = { uri ->
        context.contentResolver.openInputStream(uri.toUri())
            ?: throw java.io.IOException("That file could not be opened")
    }

    /** 网页前端的会话历史，落在同一个加密工具上 */
    val webHistory: EncryptedHistory =
        EncryptedHistory(File(context.filesDir, "web-history"))

    /**
     * The inference engine.
     *
     * One instance for the whole application, not one per screen. It holds an
     * open model occupying hundreds of megabytes to several gigabytes, and two
     * screens each building their own would load that model twice.
     *
     * Created here rather than in a ViewModel so a model stays loaded while the
     * user visits the model list and comes back.
     */
    val llmEngine: LlmEngine by lazy {
        LiteRtLlmEngine(cacheDir = File(context.filesDir, "inference-cache").apply { mkdirs() })
    }
}

class DemoApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        installCrashHandler()
        // The front end is the page, and a page cannot load a model before it
        // renders: the engine starts here so every entry finds it loading or ready.
        container.startEngineOnce()
    }

    /**
     * Releases the speech engine.
     *
     * The platform requires this rather than leaving it to the process ending:
     * `TextToSpeech` holds a service connection and native resources, and an app that
     * never releases them is one the system reports as leaking.
     *
     * `onTerminate` is not called on a real device -- Android kills the process
     * rather than winding it down -- so this covers the emulator and the case where
     * the system does call it, and the process ending covers everything else.
     */
    override fun onTerminate() {
        container.speech.shutdown()
        super.onTerminate()
    }

    /**
     * Writes a crash to a file so the user can send it.
     *
     * Installed before anything else and delegating afterwards. Not delegating
     * would swallow the crash: the system would see a clean exit, the app would
     * look like it vanished with no dialog and no entry in the system log, and the
     * report this exists to enable would describe a failure that never happened.
     *
     * This is the whole reason the app has no crash-reporting SDK. What it has
     * instead is a file the user reads before sending and decides about.
     */
    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        val directory = File(filesDir, "diagnostics")
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            CrashLog.record(directory, CrashLog.describe(error))
            previous?.uncaughtException(thread, error)
        }
    }
}
