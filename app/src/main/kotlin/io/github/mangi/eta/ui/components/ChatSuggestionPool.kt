package io.github.mangi.eta.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.DoNotDisturbOn
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.LocalShipping
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.QueryStats
import androidx.compose.material.icons.rounded.Restaurant
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.ui.graphics.vector.ImageVector
import io.github.mangi.eta.R
import kotlin.random.Random

/**
 * 空态建议词的候选池。
 *
 * 每条只声明「属于哪个家族」与「需要什么能力」：
 * 家族用来保证挑出来的四条尽量覆盖不同玩法，能力用来保证没授权的条目根本不出现在首页，
 * 用户不会点到必然失败的卡片。文案全部走资源，随语言切换；图标固定，避免换语言就换脸。
 */
internal enum class ChatSuggestionFamily {
    /** 一句话就办完的设备小事。 */
    QUICK,

    /** 需要去查或汇总点什么。 */
    LOOKUP,

    /** 代用户在别的应用里动手。 */
    OPERATE,

    /** 记忆与文字。 */
    MEMORY,

    /** 和位置有关。 */
    PLACE,

    /** 需要 Root 或终端环境的进阶玩法。 */
    ADVANCED,
}

/** 一条建议词依赖的设备能力；不满足的条目不会进入首页空态。 */
internal enum class ChatSuggestionRequirement {
    NONE,
    ACCESSIBILITY,
    NOTIFICATIONS,
    USAGE,
    LOCATION,
    ROOT,
    TERMINAL,
    MEMORY,
}

/** 空态建议词模板。 */
internal class ChatSuggestionSpec(
    val family: ChatSuggestionFamily,
    val requirement: ChatSuggestionRequirement,
    val icon: ImageVector,
    val titleRes: Int,
    val promptRes: Int,
)

internal val ChatSuggestionPool: List<ChatSuggestionSpec> = listOf(
    ChatSuggestionSpec(
        family = ChatSuggestionFamily.QUICK,
        requirement = ChatSuggestionRequirement.NONE,
        icon = Icons.Rounded.Timer,
        titleRes = R.string.suggestion_timer_title,
        promptRes = R.string.suggestion_timer_prompt,
    ),
    ChatSuggestionSpec(
        family = ChatSuggestionFamily.QUICK,
        requirement = ChatSuggestionRequirement.NONE,
        icon = Icons.Rounded.Alarm,
        titleRes = R.string.suggestion_alarm_title,
        promptRes = R.string.suggestion_alarm_prompt,
    ),
    ChatSuggestionSpec(
        family = ChatSuggestionFamily.QUICK,
        requirement = ChatSuggestionRequirement.NONE,
        icon = Icons.Rounded.MonitorHeart,
        titleRes = R.string.suggestion_device_check_title,
        promptRes = R.string.suggestion_device_check_prompt,
    ),
    ChatSuggestionSpec(
        family = ChatSuggestionFamily.LOOKUP,
        requirement = ChatSuggestionRequirement.NONE,
        icon = Icons.Rounded.Language,
        titleRes = R.string.ui_browse_the_web_da7afb,
        promptRes = R.string.suggestion_browse_web_prompt,
    ),
    ChatSuggestionSpec(
        family = ChatSuggestionFamily.LOOKUP,
        requirement = ChatSuggestionRequirement.NOTIFICATIONS,
        icon = Icons.Rounded.NotificationsActive,
        titleRes = R.string.suggestion_notification_digest_title,
        promptRes = R.string.suggestion_notification_digest_prompt,
    ),
    ChatSuggestionSpec(
        family = ChatSuggestionFamily.LOOKUP,
        requirement = ChatSuggestionRequirement.USAGE,
        icon = Icons.Rounded.QueryStats,
        titleRes = R.string.suggestion_screen_time_title,
        promptRes = R.string.suggestion_screen_time_prompt,
    ),
    ChatSuggestionSpec(
        family = ChatSuggestionFamily.LOOKUP,
        requirement = ChatSuggestionRequirement.NOTIFICATIONS,
        icon = Icons.Rounded.LocalShipping,
        titleRes = R.string.suggestion_parcel_title,
        promptRes = R.string.suggestion_parcel_prompt,
    ),
    ChatSuggestionSpec(
        family = ChatSuggestionFamily.OPERATE,
        requirement = ChatSuggestionRequirement.ACCESSIBILITY,
        icon = Icons.Rounded.ChatBubbleOutline,
        titleRes = R.string.suggestion_who_messaged_title,
        promptRes = R.string.suggestion_who_messaged_prompt,
    ),
    ChatSuggestionSpec(
        family = ChatSuggestionFamily.OPERATE,
        requirement = ChatSuggestionRequirement.ACCESSIBILITY,
        icon = Icons.Rounded.DoNotDisturbOn,
        titleRes = R.string.suggestion_dnd_title,
        promptRes = R.string.suggestion_dnd_prompt,
    ),
    ChatSuggestionSpec(
        family = ChatSuggestionFamily.MEMORY,
        requirement = ChatSuggestionRequirement.MEMORY,
        icon = Icons.Rounded.Psychology,
        titleRes = R.string.suggestion_remember_title,
        promptRes = R.string.suggestion_remember_prompt,
    ),
    ChatSuggestionSpec(
        family = ChatSuggestionFamily.MEMORY,
        requirement = ChatSuggestionRequirement.NONE,
        icon = Icons.Rounded.AutoFixHigh,
        titleRes = R.string.suggestion_polish_title,
        promptRes = R.string.suggestion_polish_prompt,
    ),
    ChatSuggestionSpec(
        family = ChatSuggestionFamily.PLACE,
        requirement = ChatSuggestionRequirement.LOCATION,
        icon = Icons.Rounded.Restaurant,
        titleRes = R.string.suggestion_nearby_food_title,
        promptRes = R.string.suggestion_nearby_food_prompt,
    ),
    ChatSuggestionSpec(
        family = ChatSuggestionFamily.ADVANCED,
        requirement = ChatSuggestionRequirement.ROOT,
        icon = Icons.Rounded.CleaningServices,
        titleRes = R.string.suggestion_storage_cleanup_title,
        promptRes = R.string.suggestion_storage_cleanup_prompt,
    ),
    ChatSuggestionSpec(
        family = ChatSuggestionFamily.ADVANCED,
        requirement = ChatSuggestionRequirement.TERMINAL,
        icon = Icons.Rounded.Terminal,
        titleRes = R.string.ui_check_memory_pressure_2d9600,
        promptRes = R.string.suggestion_memory_pressure_prompt,
    ),
)

/**
 * 从可用条目里挑 [limit] 条：先按家族去重铺满，再用同家族的剩余条目补齐。
 *
 * 池子里「不需要任何授权」的条目始终不止 [limit] 条，所以任何设备都能凑满一屏。
 * [random] 由调用方按「进入空态」的时机固定，同一个 seed 结果稳定，不会随重组跳来跳去。
 */
internal fun selectChatSuggestions(
    limit: Int,
    random: Random,
    isAvailable: (ChatSuggestionSpec) -> Boolean,
    pool: List<ChatSuggestionSpec> = ChatSuggestionPool,
): List<ChatSuggestionSpec> {
    val eligible = pool.filter(isAvailable)
    if (eligible.size <= limit) return eligible
    val shuffled = eligible.shuffled(random)
    val picked = ArrayList<ChatSuggestionSpec>(limit)
    val families = HashSet<ChatSuggestionFamily>()
    for (spec in shuffled) {
        if (picked.size == limit) return picked
        if (families.add(spec.family)) picked += spec
    }
    for (spec in shuffled) {
        if (picked.size == limit) break
        if (picked.none { it === spec }) picked += spec
    }
    return picked
}
