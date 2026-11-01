package io.github.guohangbk.tally.data.speech

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.content.ContextCompat
import io.github.guohangbk.tally.data.debug.DebugLog
import io.github.guohangbk.tally.domain.model.TallyError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * 一次聆听的结果。[Unavailable] 与 [NothingHeard] 是分开的两回事：
 * 前者要降级成文本输入，后者只是没听清，重试或改打字都行（异常表）。
 */
sealed interface SpeechOutcome {
    data class Heard(val text: String) : SpeechOutcome

    data class NothingHeard(val reason: String) : SpeechOutcome

    data class Unavailable(val reason: String) : SpeechOutcome

    /**
     * 本机引擎自己起不来（小米这类 ROM 上很常见：应用有麦克风权限，
     * 系统语音服务却回 error=9）。这时该改走系统语音输入界面——
     * 麦克风由系统应用持有，不受这条限制。
     */
    data class NeedsSystemInput(val reason: String) : SpeechOutcome

    data object Cancelled : SpeechOutcome
}

/** 页面只依赖这个接口，UI 测试可以塞一个假的聆听结果。 */
interface SpeechPort {
    /** 有识别引擎且已拿到录音权限，才算真正可用。 */
    fun isAvailable(): Boolean

    /** 权限单独暴露：没有权限时由页面发起系统授权请求，而不是直接报错。 */
    fun hasPermission(): Boolean

    /**
     * 开始收音并挂起到出结果；配合 [stopListening] 实现「按住说、松手结束」。
     * [onLevel] 收引擎回传的音量（0f..1f），录音条用它画波形。
     */
    suspend fun listen(onLevel: (Float) -> Unit = {}): SpeechOutcome

    /** 松手时调用：让引擎立刻收尾，而不是等它自己判断静音结束。 */
    fun stopListening()
}

/**
 * 系统 SpeechRecognizer 的挂起封装（不自建 ASR，也不把音频发给小莉）。
 *
 * SpeechRecognizer 只能在主线程创建与回调，所以整段收进 `Dispatchers.Main.immediate`；
 * 取消时先 cancel() 再 destroy()，否则退出页面后上一次识别还会继续吐结果。
 *
 * 各家的内置引擎对参数组合的容忍度差别很大：小米 / 讯飞一类不认 `EXTRA_PREFER_OFFLINE`，
 * 缺 `EXTRA_CALLING_PACKAGE` 会直接回 ERROR_SERVER，Google 那套又要求语言包。
 * 所以这里按「端侧优先 → 引擎默认」试两次，两次都失败才降级；失败码一律进 logcat。
 */
class SystemSpeechPort(context: Context) : SpeechPort {
    private val appContext = context.applicationContext

    /** 当前这一句的识别器；只在主线程读写，用于松手时立刻 stopListening。 */
    private var active: SpeechRecognizer? = null

    override fun isAvailable(): Boolean = hasPermission() && SpeechRecognizer.isRecognitionAvailable(appContext)

    override fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    override fun stopListening() {
        // 松手即收尾：不这样做，引擎会一直等到自己的静音超时才出结果。
        runCatching { active?.stopListening() }
    }

    override suspend fun listen(onLevel: (Float) -> Unit): SpeechOutcome = withContext(Dispatchers.Main.immediate) {
        // 有引擎 ≠ 有权限：两件事分开报，否则用户会照着错误的提示去查设置。
        val mic = hasPermission()
        val engine = SpeechRecognizer.isRecognitionAvailable(appContext)
        DebugLog.log(TAG, "listen 开始 · RECORD_AUDIO=${if (mic) "已授权" else "未授权"} · 识别引擎=${if (engine) "可用" else "不可用"}")
        if (!mic) {
            DebugLog.log(TAG, "拒绝原因：应用没有 RECORD_AUDIO")
            return@withContext SpeechOutcome.Unavailable("需要麦克风权限才能语音记账，也可以直接打字")
        }
        if (!engine) {
            DebugLog.log(TAG, "拒绝原因：系统里没有 RecognitionService（或包可见性没放行）")
            return@withContext SpeechOutcome.Unavailable(TallyError.SpeechNotAvailable.message)
        }
        val first = attempt(onLevel, preferOffline = true)
        if (!first.retryable) return@withContext first.outcome
        DebugLog.log(TAG, "端侧优先那次没成（${first.outcome.log()}），改用引擎默认参数再试一次")
        Log.i(TAG, "端侧优先那次没成（${first.outcome.log()}），改用引擎默认参数再试一次")
        val second = attempt(onLevel, preferOffline = false)
        // 两套参数都被拒，就不是「没听清」而是这台机器的引擎不给用了。
        // 小米/HyperOS 上系统语音服务会回 9（没麦克风）或 11（断连），而应用权限明明给了——
        // 这条只能绕开：交给系统自己的语音输入界面，麦克风由系统应用持有。
        if (second.retryable) {
            DebugLog.log(TAG, "两次都被拒（error=9/11 这类），转系统语音输入界面")
            SpeechOutcome.NeedsSystemInput(ENGINE_DEAD)
        } else {
            second.outcome
        }
    }

    private suspend fun attempt(onLevel: (Float) -> Unit, preferOffline: Boolean): Attempt {
        val recognizer = create(appContext)
        active = recognizer
        val appHasMic = hasPermission()
        return try {
            val outcome = suspendCancellableCoroutine<Pair<SpeechOutcome, Int?>> { continuation ->
                recognizer.setRecognitionListener(
                    SingleShotListener(onLevel, appHasMic) { result, errorCode ->
                        if (continuation.isActive) continuation.resume(result to errorCode)
                    },
                )
                continuation.invokeOnCancellation {
                    runCatching { recognizer.cancel() }
                    if (continuation.isActive) continuation.resume(SpeechOutcome.Cancelled to null)
                }
                recognizer.startListening(listeningIntent(preferOffline))
            }
            val (result, code) = outcome
            DebugLog.log(
                TAG,
                "attempt(preferOffline=$preferOffline) → ${result.log()}" + (code?.let { " · error=$it" } ?: ""),
            )
            Attempt(result, retryable = code != null && code in retryableErrors)
        } finally {
            active = null
            runCatching { recognizer.destroy() }
        }
    }

    private data class Attempt(val outcome: SpeechOutcome, val retryable: Boolean)

    private fun create(context: Context): SpeechRecognizer =
        // 带 Context 的重载从 API 1 就有；无参那个才是被删掉的历史签名，别再用反射去够。
        SpeechRecognizer.createSpeechRecognizer(context)

    private fun listeningIntent(preferOffline: Boolean): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        // 由 Service 侧发起时引擎拿不到调用方包名，缺这个参数不少国产引擎会立刻回错。
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, preferOffline)
        }
        if (preferOffline) {
            // 只在端侧那一次钉死语言：离线包缺失时引擎会回 12/13，
            // 第二次按引擎默认走，不能把语言也一起钉死。
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
        }
    }

    private companion object {

        /**
         * 「没听清 / 说完了没说话」是正常结果，换参数重试也没意义；其余都算引擎侧故障。
         * 12/13 是「没有离线语言包」，9 也常被引擎用来表示「我自己拿不到麦克风」——
         * 这三条换一套参数往往就通了，所以都进重试集合。
         */
        val retryableErrors = setOf(
            SpeechRecognizer.ERROR_SERVER,
            SpeechRecognizer.ERROR_CLIENT,
            SpeechRecognizer.ERROR_AUDIO,
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS,
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
            SpeechRecognizer.ERROR_SERVER_DISCONNECTED,
            SpeechRecognizer.ERROR_NETWORK,
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
        )
    }
}

private const val TAG = "TallySpeech"

/** 两套参数都被拒时的说法：应用自己的权限已经给了，卡的是引擎——要说清去哪儿开，而不是让用户对着同一句失败提示反复按。 */
private const val ENGINE_DEAD =
    "Tally 已有麦克风权限，但系统语音引擎没能录音。请到系统设置里给语音输入服务（「语音输入 / 输入法」那一类）也允许麦克风，再试一次；也可以直接打字记账。"

private fun SpeechOutcome.log(): String = when (this) {
    is SpeechOutcome.Heard -> "听到了「$text」"
    is SpeechOutcome.NothingHeard -> reason
    is SpeechOutcome.Unavailable -> reason
    is SpeechOutcome.NeedsSystemInput -> reason
    SpeechOutcome.Cancelled -> "已取消"
}

private class SingleShotListener(
    private val onLevel: (Float) -> Unit,
    private val appHasMic: Boolean,
    private val emit: (SpeechOutcome, Int?) -> Unit,
) : RecognitionListener {
    override fun onReadyForSpeech(params: Bundle?) = Unit

    override fun onBeginningOfSpeech() = Unit

    /** 引擎回传的 rms 大致在 -2..10dB，归一化成 0f..1f 给录音条画波形。 */
    override fun onRmsChanged(rmsdB: Float) {
        onLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
    }

    override fun onBufferReceived(buffer: ByteArray?) = Unit

    override fun onEndOfSpeech() = Unit

    override fun onPartialResults(partialResults: Bundle?) = Unit

    override fun onEvent(eventType: Int, params: Bundle?) = Unit

    override fun onError(error: Int) {
        // 上机排查全靠这一行：引擎回的错误码是唯一能区分「权限」「没引擎」「参数不接受」的证据。
        Log.w(TAG, "识别失败 error=$error (${describeError(error, appHasMic)})")
        DebugLog.log(TAG, "onError error=$error → ${describeError(error, appHasMic)}")
        emit(SpeechOutcome.NothingHeard(describeError(error, appHasMic)), error)
    }

    override fun onResults(results: Bundle?) {
        val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
        emit(
            if (text.isBlank()) {
                SpeechOutcome.NothingHeard("没有听清，请重试或输入文字")
            } else {
                Log.i(TAG, "识别成功「$text」")
                SpeechOutcome.Heard(text)
            },
            null,
        )
    }
}

private fun describeError(error: Int, appHasMic: Boolean): String = when (error) {
    SpeechRecognizer.ERROR_AUDIO -> "麦克风被占用或不可用，关掉别的录音应用再试"
    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
        // Tally 自己有权限时还回 9，是引擎自己拿不到麦克风——别说成「Tally 没授权」，
        // 那会让用户在自己的设置页里反复找一件已经做对的事。
        if (appHasMic) {
            "系统语音服务没拿到麦克风（Tally 的录音权限已允许）：可在系统设置里允许语音服务使用麦克风，或直接打字记账"
        } else {
            "没有录音权限，请在系统设置里允许"
        }

    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
        "系统语音服务缺少中文语言包，联网后重试，或直接打字记账"

    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "网络异常，可改用文字输入，记账不受影响"
    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "识别正忙，稍后再试"
    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "没有听清，请重试或输入文字"
    else -> "识别失败，可改用文字输入"
}

/** 测试与无权限场景下的固定输入；页面降级路径靠它验证。 */
class FakeSpeechPort(private val outcome: SpeechOutcome) : SpeechPort {
    override fun isAvailable(): Boolean = outcome !is SpeechOutcome.Unavailable

    override fun hasPermission(): Boolean = true

    override suspend fun listen(onLevel: (Float) -> Unit): SpeechOutcome = outcome

    override fun stopListening() = Unit
}
