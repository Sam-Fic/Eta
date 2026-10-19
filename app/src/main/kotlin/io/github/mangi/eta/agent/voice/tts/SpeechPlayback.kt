package io.github.mangi.eta.agent.voice.tts

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import androidx.core.content.ContextCompat
import io.github.mangi.eta.R
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.data.model.SpeechSynthesisModels
import java.io.File
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class SpeechPlaybackState(
    val owner: String? = null,
    val preparing: Boolean = false,
    val source: String = "",
    val error: String? = null,
    val recording: Boolean = false,
)

/** Latest-request-wins identity; only used on the main thread, including stop and microphone ownership. */
internal class SpeechPlaybackEpoch {
    private var value = 0L
    fun next(): Long = ++value
    fun isCurrent(token: Long): Boolean = token == value
}

/** Owner used by the Agent `text_to_speech` tool. */
internal const val AGENT_SPEECH_OWNER = "agent-tts"

/**
 * Speech the Agent was asked to produce is not bound to a screen: pausing the activity, switching
 * routes or conversations must not silence it. Only explicit stops (run stop, recording, settings) do.
 */
internal fun survivesUiTeardown(owner: String?): Boolean = owner == AGENT_SPEECH_OWNER

internal sealed interface SpeechOutcome {
    data object Completed : SpeechOutcome
    data class Failed(val message: String) : SpeechOutcome
    /** [reason] is the stop call site, e.g. `pause`, `superseded`, `focus_loss`. */
    data class Cancelled(val reason: String) : SpeechOutcome
}

/** Callbacks run on the main thread; each fires at most once per playback. */
internal interface SpeechPlaybackListener {
    fun onAudible() {}
    fun onFinished(outcome: SpeechOutcome) {}
}

/** Single UI-process output controller. No Agent messages, turns or tool calls are created here. */
internal object SpeechPlayback {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutex = Mutex()
    private val epoch = SpeechPlaybackEpoch()
    private var job: Job? = null
    private var recordingToken: Long? = null
    private var session: Session? = null

    /** Main-thread only. Records why a playback ended so a silent failure can be traced to its caller. */
    private class Session(val diagnostic: io.github.mangi.eta.agent.voice.VoiceDiagnostics, private val listener: SpeechPlaybackListener?) {
        var stopReason: String? = null
        private var audible = false
        private var finished = false
        fun audible() {
            if (audible || finished) return
            audible = true
            runCatching { listener?.onAudible() }
        }
        fun finish(outcome: SpeechOutcome) {
            if (finished) return
            finished = true
            runCatching { listener?.onFinished(outcome) }
        }
    }
    private val mutableState = MutableStateFlow(SpeechPlaybackState())
    val state = mutableState.asStateFlow()
    val audioAttributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()

    // Call from main; cancellation never affects the Agent run.
    fun stop(reason: String = "unspecified") {
        session?.let { current ->
            current.stopReason = reason
            current.diagnostic.mark("stop.$reason")
            current.finish(SpeechOutcome.Cancelled(reason))
        }
        session = null
        epoch.next()
        job?.cancel()
        job = null
        mutableState.value = SpeechPlaybackState(recording = recordingToken != null)
    }

    /** Pause, route change and conversation switch: keeps Agent speech playing. */
    fun stopUiBound(reason: String) {
        if (survivesUiTeardown(state.value.owner)) {
            session?.diagnostic?.mark("stop.skipped.$reason")
            return
        }
        stop(reason)
    }

    /** Stops only playback whose owner matches, e.g. voice mode must not end a reply read-aloud. */
    fun stopOwned(reason: String, owns: (String) -> Boolean) {
        val owner = state.value.owner ?: return
        if (owns(owner)) stop(reason)
    }

    suspend fun beginInput(): Long {
        stop("recording")
        val token = epoch.next()
        recordingToken = token
        mutableState.value = SpeechPlaybackState(recording = true)
        try {
            // Do not open the microphone until a cancelled player has released its audio resources.
            mutex.withLock { }
            return token
        } catch (cancelled: CancellationException) {
            endInput(token)
            throw cancelled
        }
    }

    fun endInput(token: Long) {
        if (recordingToken == token) {
            recordingToken = null
            mutableState.value = SpeechPlaybackState()
        }
    }

    fun speak(
        context: Context,
        owner: String,
        markdown: String,
        diagnostic: io.github.mangi.eta.agent.voice.VoiceDiagnostics = io.github.mangi.eta.agent.voice.VoiceDiagnostics("tts"),
        listener: SpeechPlaybackListener? = null,
        settings: SpeechPlaybackSettings = SpeechPlaybackProfile.READ_ALOUD.snapshot(),
    ) {
        if (recordingToken != null) {
            diagnostic.mark("tts.blocked_recording")
            runCatching { listener?.onFinished(SpeechOutcome.Failed("正在录音，无法朗读")) }
            return
        }
        stop("superseded")
        start(context, owner, markdown, diagnostic, listener = listener, settings = settings)
    }

    fun toggle(context: Context, owner: String, markdown: String,
        settings: SpeechPlaybackSettings = SpeechPlaybackProfile.READ_ALOUD.snapshot()) {
        if (recordingToken != null) return
        if (state.value.owner == owner) { stop("toggle"); return }
        start(context, owner, markdown, settings = settings)
    }

    fun previewMimo(context: Context, voice: io.github.mangi.eta.agent.voice.mimo.MimoPersonalVoices.Voice, text: String) {
        val owner = "mimo-preview:${voice.id}"
        if (recordingToken != null) return
        if (state.value.owner == owner) { stop("toggle"); return }
        start(context, owner, text, overrideVoice = voice)
    }

    private fun start(
        context: Context,
        owner: String,
        markdown: String,
        diagnostic: io.github.mangi.eta.agent.voice.VoiceDiagnostics = io.github.mangi.eta.agent.voice.VoiceDiagnostics("tts"),
        overrideVoice: io.github.mangi.eta.agent.voice.mimo.MimoPersonalVoices.Voice? = null,
        listener: SpeechPlaybackListener? = null,
        settings: SpeechPlaybackSettings = SpeechPlaybackProfile.READ_ALOUD.snapshot(),
    ) {
        stop("superseded")
        val token = epoch.next()
        val playback = Session(diagnostic, listener)
        session = playback
        val app = context.applicationContext
        // Capture preferences once: settings changed while loading must not mix provider/model/voice.
        val cloud = overrideVoice != null || settings.cloud
        diagnostic.mark("tts.begin", "chars" to markdown.length, "cloud" to if (cloud) 1 else 0)
        val providerId = overrideVoice?.providerId ?: settings.providerId
        val modelId = settings.modelId
        val voiceId = overrideVoice?.id ?: settings.voiceId
        mutableState.value = SpeechPlaybackState(owner, preparing = true)
        job = scope.launch {
            // A new player cannot overlap the previous player's finally/shutdown.
            mutex.withLock {
                if (!epoch.isCurrent(token)) {
                    playback.finish(SpeechOutcome.Cancelled(playback.stopReason ?: "superseded"))
                    return@withLock
                }
                try {
                    withContext(Dispatchers.IO) {
                        File(app.cacheDir, "speech-playback").listFiles()?.filter { it.extension in setOf("mp3", "wav", "ogg") }?.forEach { it.delete() }
                    }
                    speechCheck(markdown.length <= SpeechSpeakableText.MAX_SOURCE_CHARS) { "回复过长，请分段朗读" }
                    val sentences = withContext(Dispatchers.Default) { SpeechSpeakableText.sentences(markdown) }
                    speechCheck(sentences.isNotEmpty()) { "这条回复没有可朗读的正文" }
                    diagnostic.mark("tts.sentences", "count" to sentences.size)
                    withAudioFocus(app, token, diagnostic) {
                        if (!cloud) {
                            SystemSpeechSynthesizer().speak(
                                app,
                                sentences,
                                // Engine callback thread; hop to main where the session lives.
                                onFirstSentence = { scope.launch { playback.audible() } },
                            ) { voice ->
                                if (epoch.isCurrent(token)) mutableState.value = SpeechPlaybackState(owner, source = "系统本地音色 · $voice")
                            }
                        } else {
                            val config = withContext(Dispatchers.IO) {
                                val provider = ProviderRepository.providerById(providerId)
                                    ?.takeIf(SpeechSynthesisModels::isReadAloudProvider)
                                    ?: throw SpeechPlaybackFailure("该提供商不支持此朗读接入方式")
                                if (voiceId.startsWith("mimo-local-")) io.github.mangi.eta.agent.voice.mimo.MimoPersonalVoices.load(app)
                                val model = SpeechSynthesisModels.mergeCatalog(provider).firstOrNull {
                                    (if (overrideVoice != null) it.modelId == "mimo-v2.5-tts" else it.id == modelId) && it.isEnabled && SpeechSynthesisModels.isReadAloudModel(it, provider)
                                } ?: throw SpeechPlaybackFailure("朗读模型已不可用，请重新配置或选择系统朗读")
                                RuntimeConfigRepository.buildRuntimeConfig(provider, model)
                            }
                            io.github.mangi.eta.agent.voice.doubao.PersonalVoices.load(app)
                            val voice = voiceId
                            speechCheck(voice.isNotEmpty()) { app.getString(R.string.tts_pick_voice) }
                            val synth = CloudSpeechSynthesizer(diagnostic = diagnostic)
                            val label = when (SpeechEngineResolver.resolve(config.providerSourceType, config.baseUrl, config.model)) {
                                SpeechEngine.DOUBAO -> app.getString(R.string.tts_source_doubao)
                                SpeechEngine.MIMO -> app.getString(R.string.tts_source_xiaomi)
                                SpeechEngine.MINIMAX -> "MiniMax"
                                SpeechEngine.STEP -> app.getString(R.string.tts_source_step)
                                SpeechEngine.QWEN -> app.getString(R.string.tts_source_qwen)
                                SpeechEngine.GROQ -> "Groq"
                                SpeechEngine.XAI -> "xAI"
                                SpeechEngine.GEMINI -> "Gemini"
                                SpeechEngine.ELEVENLABS -> "ElevenLabs"
                                SpeechEngine.FISH -> "Fish Audio"
                                SpeechEngine.COSYVOICE -> "CosyVoice"
                                SpeechEngine.MOSS -> "MOSS-TTSD"
                                SpeechEngine.OPENAI -> app.getString(R.string.tts_source_openai)
                            }
                            supervisorScope {
                                // At most current + next sentence buffered. Never restart from the beginning on failure.
                                var next = async { synth.synthesize(config, sentences.first(), voice) }
                                for (index in sentences.indices) {
                                    val bytes = next.await()
                                    if (index < sentences.lastIndex) next = async { synth.synthesize(config, sentences[index + 1], voice) }
                                    currentCoroutineContext().ensureActive()
                                    if (epoch.isCurrent(token)) mutableState.value = SpeechPlaybackState(owner, source = "$label · ${index + 1}/${sentences.size}")
                                    playMp3(app, bytes, diagnostic) { playback.audible() }
                                }
                            }
                        }
                    }
                    diagnostic.mark("tts.completed")
                    playback.finish(SpeechOutcome.Completed)
                    if (epoch.isCurrent(token)) mutableState.value = SpeechPlaybackState()
                } catch (e: TimeoutCancellationException) {
                    diagnostic.mark("tts.timeout")
                    val message = app.getString(R.string.tts_timeout_retry)
                    playback.finish(SpeechOutcome.Failed(message))
                    if (epoch.isCurrent(token)) mutableState.value = SpeechPlaybackState(error = message)
                } catch (e: CancellationException) {
                    diagnostic.mark("tts.cancelled")
                    playback.finish(SpeechOutcome.Cancelled(playback.stopReason ?: "cancelled"))
                    throw e
                } catch (e: Exception) {
                    diagnostic.mark("tts.failed")
                    // Config/decoder exceptions may include URLs, never surface them verbatim.
                    val message = when (e) {
                        is SpeechPlaybackFailure -> e.message
                        else -> null
                    } ?: app.getString(R.string.tts_failed_check_setup)
                    playback.finish(SpeechOutcome.Failed(message))
                    if (epoch.isCurrent(token)) mutableState.value = SpeechPlaybackState(error = message)
                } finally {
                    if (session === playback) session = null
                    if (epoch.isCurrent(token)) {
                        job = null
                        if (mutableState.value.owner != null) mutableState.value = SpeechPlaybackState()
                    }
                }
            }
        }
    }

    private suspend fun withAudioFocus(context: Context, token: Long, diagnostic: io.github.mangi.eta.agent.voice.VoiceDiagnostics, block: suspend () -> Unit) {
        val audio = context.getSystemService(AudioManager::class.java)
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(audioAttributes).setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener { change ->
                diagnostic.mark("focus.changed", "change" to change)
                if (change < 0 && epoch.isCurrent(token)) stop("focus_loss")
            }.build()
        val focusResult = audio.requestAudioFocus(request)
        diagnostic.mark("focus.request", "result" to focusResult, "volume" to audio.getStreamVolume(AudioManager.STREAM_MUSIC))
        speechCheck(focusResult == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { "暂时无法取得音频焦点，请稍后朗读" }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (epoch.isCurrent(token)) stop("noisy")
            }
        }
        var registered = false
        try {
            ContextCompat.registerReceiver(context, receiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED)
            registered = true
            block()
        } finally {
            if (registered) context.unregisterReceiver(receiver)
            audio.abandonAudioFocusRequest(request)
        }
    }

    private suspend fun playMp3(context: Context, bytes: ByteArray, diagnostic: io.github.mangi.eta.agent.voice.VoiceDiagnostics, onStarted: () -> Unit) {
        diagnostic.mark("player.prepare", "bytes" to bytes.size)
        val directory = File(context.cacheDir, "speech-playback")
        val payload = DoubaoSpeech.decodeAudio(bytes)
        val file = File(directory, "${UUID.randomUUID()}.${payload.extension}")
        val player = MediaPlayer()
        try {
            withContext(Dispatchers.IO) {
                speechCheck(directory.isDirectory || directory.mkdirs()) { "无法创建临时音频目录" }
                file.writeBytes(payload.bytes)
            }
            player.setAudioAttributes(audioAttributes)
            player.setVolume(1f, 1f)
            player.setDataSource(file.absolutePath)
            withTimeout(120_000) {
                suspendCancellableCoroutine<Unit> { continuation ->
                    player.setOnPreparedListener {
                        if (continuation.isActive) {
                            diagnostic.mark("player.prepared", "durationMs" to it.duration)
                            try { it.start(); diagnostic.mark("player.started"); onStarted() } catch (_: Exception) {
                                if (continuation.isActive) continuation.resumeWithException(SpeechPlaybackFailure("音频播放器启动失败"))
                            }
                        }
                    }
                    player.setOnCompletionListener { diagnostic.mark("player.completed"); if (continuation.isActive) continuation.resume(Unit) }
                    player.setOnErrorListener { _, what, extra ->
                        diagnostic.mark("player.error", "what" to what, "extra" to extra)
                        if (continuation.isActive) continuation.resumeWithException(SpeechPlaybackFailure("无法播放接口返回的音频"))
                        true
                    }
                    player.prepareAsync()
                }
            }
        } finally {
            runCatching { player.setOnPreparedListener(null) }
            runCatching { player.setOnCompletionListener(null) }
            runCatching { player.setOnErrorListener(null) }
            runCatching { player.release() }
            withContext(NonCancellable + Dispatchers.IO) { file.delete() }
        }
    }
}
