package io.github.guohangbk.tally.data.notify

import android.content.Context
import android.provider.Settings

/** 通知使用权的真实状态：只读系统设置，不猜、不缓存。 */
object NotificationAccess {

    fun enabled(context: Context): Boolean {
        val listeners = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
            ?: return false
        return listeners.split(':').any { it.substringBefore('/') == context.packageName }
    }
}
