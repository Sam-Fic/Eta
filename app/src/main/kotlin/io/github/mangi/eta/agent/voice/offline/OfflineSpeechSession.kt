package io.github.mangi.eta.agent.voice.offline

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import com.k2fsa.sherpa.ncnn.DecoderConfig
import com.k2fsa.sherpa.ncnn.FeatureExtractorConfig
import com.k2fsa.sherpa.ncnn.ModelConfig
import com.k2fsa.sherpa.ncnn.RecognizerConfig
import com.k2fsa.sherpa.ncnn.SherpaNcnn
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex

/**
 * 两个运行库（ncnn / ONNX Runtime）的调用形态不同，但「送样本 → 解码 → 取文本 → 端点」
 * 的循环完全一致，所以在最薄的一层做适配，避免把整个播放/录音循环写两遍。
 */
private interface StreamingEngine {
    fun accept(samples: FloatArray)
    fun ready(): Boolean
    fun decode()
    fun text(): String
    fun endpoint(): Boolean
    fun finish()
    fun close()
}

private class NcnnEngine(private val recognizer: SherpaNcnn) : StreamingEngine {
    override fun accept(samples: FloatArray) = recognizer.acceptSamples(samples)
    override fun ready(): Boolean = recognizer.isReady()
    override fun decode() = recognizer.decode()
    override fun text(): String = recognizer.text
    override fun endpoint(): Boolean = recognizer.isEndpoint()
    override fun finish() {
        // ncnn 版的 transducer 需要补一小段静音才会吐出尾包结果。
        recognizer.acceptSamples(FloatArray(TailPaddingSamples))
        recognizer.inputFinished()
    }
    override fun close() = recognizer.close()
}

private class OnnxEngine(
    private val recognizer: OnlineRecognizer,
    private val stream: OnlineStream,
) : StreamingEngine {
    override fun accept(samples: FloatArray) = stream.acceptWaveform(samples, SampleRate)
    override fun ready(): Boolean = recognizer.isReady(stream)
    override fun decode() = recognizer.decode(stream)
    override fun text(): String = recognizer.getResult(stream).text
    override fun endpoint(): Boolean = recognizer.isEndpoint(stream)
    override fun finish() = stream.inputFinished()
    override fun close() {
        stream.release()
        recognizer.release()
    }
}

private const val SampleRate = 16_000
private const val TailPaddingSamples = 4800
private const val ReadSamples = 1600

/** 按模型声明的引擎族装载对应运行库。只走文件路径，模型不打包进 APK。 */
private fun createEngine(model: SpeechModelCatalog.Model, dir: File): StreamingEngine {
    fun path(name: String) = File(dir, name).absolutePath
    return when (model.engine) {
        SpeechModelCatalog.Engine.NCNN_TRANSDUCER -> NcnnEngine(
            SherpaNcnn(
                RecognizerConfig(
                    featConfig = FeatureExtractorConfig(16_000f, 80),
                    modelConfig = ModelConfig(
                        encoderParam = path(SpeechModelCatalog.NCNN_ENCODER_PARAM),
                        encoderBin = path(SpeechModelCatalog.NCNN_ENCODER_BIN),
                        decoderParam = path(SpeechModelCatalog.NCNN_DECODER_PARAM),
                        decoderBin = path(SpeechModelCatalog.NCNN_DECODER_BIN),
                        joinerParam = path(SpeechModelCatalog.NCNN_JOINER_PARAM),
                        joinerBin = path(SpeechModelCatalog.NCNN_JOINER_BIN),
                        tokens = path(SpeechModelCatalog.TOKENS),
                        numThreads = 1, useGPU = false,
                    ),
                    decoderConfig = DecoderConfig(method = "greedy_search"),
                    enableEndpoint = true,
                    rule1MinTrailingSilence = 8f,
                    rule2MinTrailingSilence = 1.2f,
                    rule3MinUtteranceLength = 60f,
                ),
            ),
        )

        SpeechModelCatalog.Engine.ONNX_ONLINE_ZIPFORMER2 -> {
            val recognizer = OnlineRecognizer(
                config = OnlineRecognizerConfig(
                    featConfig = FeatureConfig(sampleRate = SampleRate, featureDim = 80),
                    modelConfig = OnlineModelConfig(
                        transducer = OnlineTransducerModelConfig(
                            encoder = path(SpeechModelCatalog.ONNX_ENCODER),
                            decoder = path(SpeechModelCatalog.ONNX_DECODER),
                            joiner = path(SpeechModelCatalog.ONNX_JOINER),
                        ),
                        tokens = path(SpeechModelCatalog.TOKENS),
                        // fp32 编码器比 ncnn 版大得多，单/双线程跟不上 480 ms 的分块节奏。
                        numThreads = 4,
                        // 不写 modelType：导出时已把 zipformer2、chunk 等元数据写进 ONNX，
                        // 显式覆盖反而可能选到不匹配的实现。
                    ),
                    endpointConfig = EndpointConfig(
                        rule1 = EndpointRule(mustContainNonSilence = false, minTrailingSilence = 8f, minUtteranceLength = 0f),
                        rule2 = EndpointRule(mustContainNonSilence = true, minTrailingSilence = 1.2f, minUtteranceLength = 0f),
                        rule3 = EndpointRule(mustContainNonSilence = false, minTrailingSilence = 0f, minUtteranceLength = 60f),
                    ),
                    enableEndpoint = true,
                    decodingMethod = "greedy_search",
                ),
            )
            OnnxEngine(recognizer, recognizer.createStream())
        }
    }
}

/** No network and no audio files. All native calls and microphone ownership stay on one worker. */
internal object OfflineSpeechSession {
    private val microphone = Mutex()
    // OpenMP/ncnn keep process-wide runtime state; a dedicated worker avoids
    // DefaultDispatcher thread churn around model load.
    private val native = Executors.newSingleThreadExecutor { thread ->
        Thread(thread, "eta-speech-ncnn").apply { isDaemon = true }
    }.asCoroutineDispatcher()

    @SuppressLint("MissingPermission") // Permission is requested by the user-initiated UI entry.
    suspend fun recognize(
        context: Context,
        onListening: suspend () -> Unit,
        onText: suspend (String) -> Unit,
        mode: io.github.mangi.eta.agent.voice.VoiceEntryMode = io.github.mangi.eta.agent.voice.VoiceEntryMode.DICTATION,
    ): Boolean = withContext(native) {
        check(microphone.tryLock()) { "Speech input is already active" }
        var engine: StreamingEngine? = null
        var recorder: AudioRecord? = null
        try {
            check(io.github.mangi.eta.agent.voice.VoiceEntryPolicy.enabled(
                io.github.mangi.eta.agent.voice.doubao.DoubaoVoiceConfig.state.value, mode) && OfflineSpeechPack.state.value.ready)
            val model = OfflineSpeechPack.state.value.model
            val dir = OfflineSpeechPack.directory(context, model)
            // Recheck sizes before passing paths to native code; full hashes were verified at startup/download.
            check(model.assets.all { File(dir, it.name).length() == it.bytes })
            val active = createEngine(model, dir)
            engine = active
            ensureActive() // Closing the screen during model load must never start recording later.
            val minBuffer = AudioRecord.getMinBufferSize(16_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            check(minBuffer > 0)
            val audio = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16_000,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer * 2, 64_000))
            recorder = audio
            check(audio.state == AudioRecord.STATE_INITIALIZED)
            ensureActive()
            audio.startRecording()
            check(audio.recordingState == AudioRecord.RECORDSTATE_RECORDING)
            withContext(Dispatchers.Main) { onListening() }
            val samples = ShortArray(ReadSamples)
            val start = SystemClock.elapsedRealtime()
            var lastText = ""
            while (true) {
                ensureActive()
                val elapsed = SystemClock.elapsedRealtime() - start
                if (SpeechInputPolicy.timedOut(elapsed, lastText.isNotEmpty())) break
                val count = audio.read(samples, 0, samples.size, AudioRecord.READ_NON_BLOCKING)
                check(count >= 0) { "Microphone read failed" }
                if (count == 0) { delay(10); continue }
                active.accept(FloatArray(count) { samples[it] / 32768f })
                while (active.ready()) { ensureActive(); active.decode() }
                val text = active.text().trim()
                if (text.isNotEmpty() && text != lastText) {
                    lastText = text
                    withContext(Dispatchers.Main) { onText(text) }
                }
                if (active.endpoint()) break
            }
            // Only natural completion flushes decoder tail. Explicit cancellation keeps already shown text.
            ensureActive()
            active.finish()
            while (active.ready()) { ensureActive(); active.decode() }
            val finalText = active.text().trim()
            if (finalText.isNotEmpty() && finalText != lastText) withContext(Dispatchers.Main) { onText(finalText) }
            finalText.isNotEmpty() || lastText.isNotEmpty()
        } finally {
            runCatching { recorder?.stop() }
            runCatching { recorder?.release() }
            runCatching { engine?.close() }
            microphone.unlock()
        }
    }
}
