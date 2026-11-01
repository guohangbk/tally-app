package io.github.guohangbk.tally.ui.design

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView

/**
 * 操作反馈的统一出口。
 *
 * 只用系统 HapticFeedbackConstants，不申请 VIBRATE 权限：
 * 轻点走 CONTEXT_CLICK，键盘走 KEYBOARD_TAP，长按走 LONG_PRESS，
 * 成功与失败在 Android 11+ 分别用 CONFIRM / REJECT。系统里关掉「触感反馈」时，
 * performHapticFeedback 会自己尊重这个开关，不需要我们在业务侧再判断一次。
 */
@Stable
class TallyHaptics internal constructor(private val view: View) {
    fun tap() = fire(HapticFeedbackConstants.CONTEXT_CLICK)

    fun key() = fire(HapticFeedbackConstants.KEYBOARD_TAP)

    fun longPress() = fire(HapticFeedbackConstants.LONG_PRESS)

    fun confirm() = fire(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            HapticFeedbackConstants.CONFIRM
        } else {
            HapticFeedbackConstants.CONTEXT_CLICK
        },
    )

    fun reject() = fire(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            HapticFeedbackConstants.REJECT
        } else {
            HapticFeedbackConstants.LONG_PRESS
        },
    )

    private fun fire(constant: Int) {
        if (view.isAttachedToWindow) {
            @Suppress("DEPRECATION")
            view.performHapticFeedback(constant)
        }
    }
}

@Composable
fun rememberTallyHaptics(): TallyHaptics {
    val view = LocalView.current
    return remember(view) { TallyHaptics(view) }
}
