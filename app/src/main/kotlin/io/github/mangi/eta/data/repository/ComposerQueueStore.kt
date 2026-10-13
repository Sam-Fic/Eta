package io.github.mangi.eta.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONObject

private val Context.composerQueueStore by preferencesDataStore(name = "eta_composer_queue")

/** 待发送的定时消息。 */
internal data class ScheduledSend(
    val conversationId: String,
    val text: String,
    val dueAtMillis: Long,
)

/**
 * 输入框草稿与「定时发送」队列的本机持久化（按会话隔离）。
 *
 * - 草稿：点「保存草稿」写入，重新进入该会话时若输入框为空则自动恢复。
 * - 定时消息：到点由 [io.github.mangi.eta.ui.components.ScheduledSendReceiver] 提醒；
 *   应用在前台且正停留在目标会话时自动发出，发出后清除。
 */
internal object ComposerQueueStore {
    private const val DRAFT_PREFIX = "draft_"
    private const val SCHEDULED_PREFIX = "scheduled_"

    private fun draftKey(conversationId: String) = stringPreferencesKey(DRAFT_PREFIX + conversationId)
    private fun scheduledKey(conversationId: String) =
        stringPreferencesKey(SCHEDULED_PREFIX + conversationId)

    suspend fun saveDraft(context: Context, conversationId: String, text: String) {
        context.composerQueueStore.edit { prefs ->
            val key = draftKey(conversationId)
            if (text.isBlank()) prefs.remove(key) else prefs[key] = text
        }
    }

    fun draftFlow(context: Context, conversationId: String): Flow<String> =
        context.composerQueueStore.data.map { it[draftKey(conversationId)].orEmpty() }

    suspend fun scheduleSend(
        context: Context,
        conversationId: String,
        text: String,
        dueAtMillis: Long,
    ) {
        val payload = JSONObject()
            .put(FIELD_TEXT, text)
            .put(FIELD_DUE_AT, dueAtMillis)
            .toString()
        context.composerQueueStore.edit { it[scheduledKey(conversationId)] = payload }
    }

    /** 当前所有待发的定时消息。 */
    fun scheduledSendsFlow(context: Context): Flow<List<ScheduledSend>> =
        context.composerQueueStore.data.map { prefs ->
            prefs.asMap().entries.mapNotNull { (key, value) ->
                val name = key.name
                if (!name.startsWith(SCHEDULED_PREFIX)) return@mapNotNull null
                val conversationId = name.removePrefix(SCHEDULED_PREFIX)
                val raw = value as? String ?: return@mapNotNull null
                val json = runCatching { JSONObject(raw) }.getOrNull() ?: return@mapNotNull null
                val text = json.optString(FIELD_TEXT)
                if (text.isBlank()) return@mapNotNull null
                ScheduledSend(
                    conversationId = conversationId,
                    text = text,
                    dueAtMillis = json.optLong(FIELD_DUE_AT),
                )
            }
        }

    suspend fun clearScheduled(context: Context, conversationId: String) {
        context.composerQueueStore.edit { it.remove(scheduledKey(conversationId)) }
    }

    private const val FIELD_TEXT = "text"
    private const val FIELD_DUE_AT = "dueAt"
}
