package io.github.mangi.eta.agent.device

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager

/** 日历读取/写入的运行时权限；未授权时由能力投影从模型目录中隐藏对应工具。 */
internal object CalendarPermissions {
    fun granted(context: Context, write: Boolean): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED &&
            (!write ||
                context.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) ==
                    PackageManager.PERMISSION_GRANTED)
}
