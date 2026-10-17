package io.github.mangi.eta.agent.voice

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import io.github.mangi.eta.agent.voice.tts.SpeechOutcome
import io.github.mangi.eta.agent.voice.tts.SpeechPlayback
import io.github.mangi.eta.agent.voice.tts.SpeechPlaybackListener
import io.github.mangi.eta.agent.voice.tts.SpeechPlaybackProfile
import io.github.mangi.eta.agent.voice.tts.SpeechSpeakableText
import io.github.mangi.eta.config.ReplyReadAloudPreference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 回复自动朗读的纯决策逻辑：只依赖 [VoiceChatSnapshot] 与开关，不接触 Android 组件，
 * 单测可直接驱动。调用方每收到一次聊天状态快照就调一次 [onSnapshot]，返回本次需要
 * 朗读的句子（可能为空）。
 *
 * 两条规则与「语音对话」保持一致：
 * - 只有本次会话里现场生成的回复才自动朗读：打开历史会话、切换会话都不重读旧回复；
 * - 同一条回复只读一次，朗读进度按「已播句子数」推进，流式中丢帧的快照不会重复朗读。
 */
internal class ReplyReadAloudTracker {

    /** 出现过进行中的回复（`isStreaming = true`）：区分「刚生成的回复」与「历史消息」。 */
    var armed: Boolean = false
        private set

    /**
     * 运行开始前就存在的最后一条回复。新运行的头几个快照还在盯着它（思考阶段尚无新回复），
     * 不能因为它出现在流式快照里就把旧回复读一遍。
     */
    var baselineId: String? = null
        private set

    /** 当前正在处理的回复 id。 */
    var currentId: String? = null
        private set

    /** 已朗读的句子数。 */
    var spokenCount: Int = 0
        private set

    /** 已读完（或确认无可朗读内容）的回复 id。 */
    var finalizedId: String? = null
        private set

    fun onSnapshot(snapshot: VoiceChatSnapshot, streamSynthesis: Boolean): List<String> {
        val finalized = !snapshot.isStreaming
        val id = snapshot.lastAgentId
        if (!finalized) {
            if (!armed) {
                // 运行刚开始：此刻的最后一条回复是运行前就存在的，记为基线不读。
                armed = true
                baselineId = id
            }
        } else if (!armed) {
            // 没有进行中的运行：这是打开历史会话 / 切换会话带来的旧回复，不重读。
            return emptyList()
        }
        // 运行结束的这一刻解除武装，避免空回复把武装状态泄漏到下一次会话切换。
        if (finalized) armed = false
        if (id.isNullOrBlank() || id == finalizedId || id == baselineId) return emptyList()
        val text = snapshot.lastAgentText
        if (id != currentId) {
            currentId = id
            spokenCount = 0
        }
        if (text.isBlank()) {
            if (finalized) finalizedId = id
            return emptyList()
        }
        // 关闭流式合成时，只有回复全部结束才开口；开启时按已提交的整句朗读。
        if (!finalized && !streamSynthesis) return emptyList()
        if (text.length > SpeechSpeakableText.MAX_SOURCE_CHARS) {
            // 与手动朗读一致：超长回复不自动朗读，交给用户手动分段。
            if (finalized) finalizedId = id
            return emptyList()
        }
        val ready = runCatching {
            SpeechSpeakableText.committedSentences(text, finalized = finalized)
        }.getOrDefault(emptyList())
        val utterances = ready.drop(spokenCount)
        spokenCount = ready.size
        if (finalized) finalizedId = id
        return utterances
    }

    fun reset() {
        armed = false
        baselineId = null
        currentId = null
        spokenCount = 0
        finalizedId = null
    }
}

/**
 * 回复自动朗读的进程级控制器：应用内聊天与助手浮窗都把 [VoiceChatSnapshot] 喂进来，
 * 由这里决定「是否读、读哪几句」。朗读复用 [SpeechPlayback]，音色/模型走朗读分区
 * （[SpeechPlaybackProfile.READ_ALOUD]）配置，与手动朗读、试听完全同一条播放通路。
 *
 * 播放所有权前缀见 [OWNER_PREFIX]：自动朗读不绑定某条可见消息（朗读的是整条回复，
 * 且可能在流式中就开始），因此不能被「回复不在可见列表里」的孤儿回收收掉。
 */
internal object ReplyReadAloudController {

    /** Owner 前缀；一条回复一个 owner，[SpeechPlayback.stopOwned] 只收自己的播放。 */
    const val OWNER_PREFIX = "auto-read-"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val main = Handler(Looper.getMainLooper())
    private val snapshots = Channel<VoiceChatSnapshot>(Channel.CONFLATED)
    private val tracker = ReplyReadAloudTracker()
    private var app: Context? = null
    private var loop: Job? = null
    private var preferenceListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    fun updateChat(context: Context, snapshot: VoiceChatSnapshot) {
        if (app == null) app = context.applicationContext
        if (!ReplyReadAloudPreference.autoRead) {
            // 关闭自动朗读：立刻停掉正在进行的自动朗读并解除武装。
            disable()
            return
        }
        ensureObserving()
        snapshots.trySend(snapshot)
        if (loop?.isActive != true) loop = scope.launch { runLoop() }
    }

    /** 关闭总开关 / 结束 Service 时调用：停掉自己的播放并清空进度。 */
    fun stop() {
        disable()
    }

    private suspend fun runLoop() {
        while (currentCoroutineContext().isActive) {
            val snapshot = snapshots.receive()
            if (!ReplyReadAloudPreference.autoRead) {
                disable()
                continue
            }
            val utterances = tracker.onSnapshot(snapshot, ReplyReadAloudPreference.streamSynthesis)
            for (utterance in utterances) {
                val app = app ?: return
                val owner = OWNER_PREFIX + (tracker.currentId ?: return)
                // 用户手动朗读 / 语音对话 / Agent 工具接管了扬声器时不抢回播放。
                val activeOwner = SpeechPlayback.state.value.owner
                if (activeOwner != null && !activeOwner.startsWith(OWNER_PREFIX)) return
                val completion = CompletableDeferred<SpeechOutcome>()
                val listener = object : SpeechPlaybackListener {
                    override fun onFinished(outcome: SpeechOutcome) {
                        completion.complete(outcome)
                    }
                }
                SpeechPlayback.speak(
                    app,
                    owner,
                    utterance,
                    listener = listener,
                    settings = SpeechPlaybackProfile.READ_ALOUD.snapshot(),
                )
                if (completion.await() != SpeechOutcome.Completed) {
                    // 被打断（手动朗读、录音、失败）：放弃这条回复的剩余句子。
                    return
                }
            }
        }
    }

    private fun disable() {
        tracker.reset()
        SpeechPlayback.stopOwned("auto_read_off") { it.startsWith(OWNER_PREFIX) }
    }

    /** 开关可能在设置页被关闭，而此时并没有新的快照进来：需要监听并及时收声。 */
    private fun ensureObserving() {
        if (preferenceListener != null) return
        val listener = ReplyReadAloudPreference.observeAutoRead { enabled ->
            // 写入方通常是主线程，但读取/停止播放只允许在主线程，保守地做一次线程切换。
            if (Looper.myLooper() == main.looper) {
                if (!enabled) disable()
            } else {
                main.post { if (!enabled) disable() }
            }
        } ?: return
        preferenceListener = listener
    }
}
