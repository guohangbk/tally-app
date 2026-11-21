package io.github.guohangbk.tally.data.notify

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import io.github.guohangbk.tally.MainActivity
import io.github.guohangbk.tally.R
import io.github.guohangbk.tally.data.debug.DebugLog
import io.github.guohangbk.tally.ui.design.tallyColors

/**
 * 待确认角标：通知抓到一笔以后在屏幕上浮一枚圆形小角标，点它进 App 打开待确认卡。
 *
 * 只做「入口」，不画整张卡：确认卡要靠记账页往返、ViewModel 与导航，搬进浮窗等于重写半套 UI。
 * 没有悬浮窗权限、锁屏息屏、或 App 正在前台时都不浮——后台静默的那条路本来就够用。
 */
object PendingBadgeOverlay {

    const val EXTRA_OPEN_PENDING = "io.github.guohangbk.tally.extra.OPEN_PENDING"

    private const val AUTO_HIDE_MILLIS = 10_000L

    private val count = mutableStateOf(0)
    private var view: ComposeView? = null
    /**
     * 必须**迟一点**建：LifecycleRegistry 只允许在主线程推状态，而 [show] 是从通知服务的
     * IO 协程里调进来的；在对象初始化时建，会直接抛 ExceptionInInitializerError。
     * 真正用它的 [add] 已经跑在主线程上。
     */
    private val owner by lazy { OverlayLifecycleOwner() }
    private val handler = Handler(Looper.getMainLooper())
    private val autoHide = Runnable { remove() }

    /** 抓到一笔就调一次：同一批连续来几条只更新数字，并把 10 秒重新计。 */
    fun show(context: Context, pending: Int) {
        val app = context.applicationContext
        if (pending <= 0) {
            remove()
            return
        }
        // 不浮的时候要说清为什么：这一步静默返回过，结果是「通知抓到了但人没看见角标」查不出原因。
        val skip = when {
            !canShowNow(app) -> "锁屏或息屏"
            isAppForeground(app) -> "App 在前台"
            else -> null
        }
        if (skip != null) {
            DebugLog.log("待确认角标", "不浮：$skip")
            return
        }
        handler.post {
            count.value = pending
            if (view == null) add(app)
            if (view != null) DebugLog.log("待确认角标", "浮出 $pending 笔")
            handler.removeCallbacks(autoHide)
            handler.postDelayed(autoHide, AUTO_HIDE_MILLIS)
        }
    }

    fun hide() = remove()

    private fun add(context: Context) {
        val compose = ComposeView(context)
        compose.setViewTreeLifecycleOwner(owner)
        compose.setViewTreeViewModelStoreOwner(owner)
        compose.setViewTreeSavedStateRegistryOwner(owner)
        compose.setContent { Badge(count = count.value) { openApp(context) } }
        val layout = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 12
            y = context.resources.displayMetrics.heightPixels * 2 / 5
        }
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        // 权限不在这里先判：小米上 Settings.canDrawOverlays() 会和实际能力不一致
        // （系统检查说没权限，窗口却加得上，反之也有）。真正的判据只有 addView 成不成。
        runCatching { wm.addView(compose, layout) }.onFailure { failure ->
            DebugLog.log(
                "待确认角标",
                "浮窗加不上：${failure.javaClass.simpleName} ${failure.message.orEmpty()}".trim() +
                    "；系统权限检查=${Settings.canDrawOverlays(context)}",
            )
            return
        }
        if (!Settings.canDrawOverlays(context)) {
            DebugLog.log("待确认角标", "系统权限检查说没权限，但浮窗已经加上")
        }
        view = compose
    }

    private fun remove() {
        handler.removeCallbacks(autoHide)
        val compose = view ?: return
        val context = compose.context
        runCatching {
            (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(compose)
        }
        view = null
    }

    private fun openApp(context: Context) {
        remove()
        runCatching {
            context.startActivity(
                Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    putExtra(EXTRA_OPEN_PENDING, true)
                },
            )
        }
    }

    /** 锁屏与息屏不展示：金额和笔数不该亮给旁边的人看。 */
    private fun canShowNow(context: Context): Boolean {
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        return power.isInteractive && !keyguard.isKeyguardLocked
    }

    /** App 在前台时不浮：首页本来就有那枚「有 N 笔待确认」的入口。 */
    private fun isAppForeground(context: Context): Boolean = runCatching {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        manager.runningAppProcesses?.firstOrNull { it.pid == android.os.Process.myPid() }
            ?.importance == android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }.getOrDefault(false)

    @Composable
    private fun Badge(count: Int, onClick: () -> Unit) {
        val colors = tallyColors
        Box(modifier = Modifier.size(52.dp)) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(colorResource(id = R.color.ic_launcher_background))
                    .border(1.5.dp, colors.gold, CircleShape)
                    .clickable(onClick = onClick),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(id = R.drawable.ic_launcher_foreground),
                    contentDescription = "待确认 $count 笔",
                    modifier = Modifier.requiredSize(66.dp),
                )
            }
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(colors.expense),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (count > 9) "9+" else count.toString(),
                    color = androidx.compose.ui.graphics.Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }

    /** 浮窗挂在 WindowManager 上，Compose 需要一套 view 树 owner。 */
    private class OverlayLifecycleOwner : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
        private val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle = registry

        private val savedStateController = SavedStateRegistryController.create(this)
        override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

        private val store = ViewModelStore()
        override val viewModelStore: ViewModelStore get() = store

        init {
            savedStateController.performAttach()
            savedStateController.performRestore(null)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        }
    }
}
