package io.github.mangi.eta.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import io.github.mangi.eta.agent.device.AgentTaskPrompt
import io.github.mangi.eta.agent.device.AgentTaskSurfaceMode
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.data.model.AppearanceSettings
import io.github.mangi.eta.data.repository.AppearanceSettingsRepository
import io.github.mangi.eta.ui.app.AgentAppTheme

/**
 * 透明窗口里的“这次在哪里执行”弹窗，盖在当前任何应用之上。
 *
 * 始终显示 [AgentTaskPrompt.pending] 的队首请求：回答后若还有下一个请求就接着显示，队列空了才结束自己。
 * 工具线程在 [AgentTaskPrompt.awaitHostHidden] 等它退场再继续。
 */
class AgentTaskSurfaceDialogActivity : ComponentActivity() {
    /** 本实例当前是否已计入 [AgentTaskPrompt.hostVisible]，避免 onStart/onResume 重复计数。 */
    private var countedVisible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 立即渲染：不能先挂一层空白透明窗口挡住点击。外观未读到前用默认值。
        setContent {
            val appearance by AppearanceSettingsRepository.settingsFlow()
                .collectAsState(initial = null)
            val pending by AgentTaskPrompt.pending.collectAsState()
            val request = pending
            LaunchedEffect(request == null) {
                // 队列已空（都已回答、run 已停止或超时）：不再挂着一个过期弹窗。
                if (request == null) finish()
            }
            if (request != null) {
                AgentAppTheme(appearance = appearance ?: DEFAULT_APPEARANCE, applyInterfaceScale = true) {
                    // 换成下一个请求时重置单选状态。
                    key(request.id) {
                        AgentTaskSurfaceChoiceDialog(
                            onChoose = { mode -> answer(request.id, mode) },
                            onCancel = { answer(request.id, null) },
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // 显示内容由 pending 驱动，这里只更新 intent。
        setIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        markVisible()
    }

    override fun onResume() {
        super.onResume()
        markVisible()
    }

    override fun onStop() {
        // 退到后台不取消请求，只让应用内兜底弹窗接管，回到Eta即可继续选择。
        markHidden()
        super.onStop()
    }

    private fun answer(requestId: String, mode: AgentTaskSurfaceMode?) {
        AgentTaskPrompt.answer(requestId, mode)
        // 没有下一个请求就立刻退场，工具线程不用多等一帧重组。
        if (AgentTaskPrompt.pending.value == null) finish()
    }

    private fun markVisible() {
        if (countedVisible) return
        countedVisible = true
        AgentTaskPrompt.hostStarted()
    }

    private fun markHidden() {
        if (!countedVisible) return
        countedVisible = false
        AgentTaskPrompt.hostStopped()
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    override fun onDestroy() {
        // 未经 onStop 直接销毁时也要扣回计数，避免 awaitHostHidden 白等。
        markHidden()
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_REQUEST_ID = "io.github.mangi.eta.extra.TASK_SURFACE_REQUEST"
        private val DEFAULT_APPEARANCE = AppearanceSettings()

        /** 已在显示时（singleInstance）走 onNewIntent，弹窗仍按 pending 队首显示。 */
        fun launch(context: Context, requestId: String) {
            val intent = Intent(context, AgentTaskSurfaceDialogActivity::class.java)
                .putExtra(EXTRA_REQUEST_ID, requestId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            runCatching { context.startActivity(intent) }.onFailure { throwable ->
                // 后台启动受限时退回应用内弹窗，回到Eta即可看到。
                AndroidAgentLogger.warnThrottled("task_surface_dialog_launch_failed") {
                    "Task surface dialog launch failed: type=${throwable.safeLogType()}"
                }
            }
        }
    }
}
