package io.github.guohangbk.tally.data.speech

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.content.ContextCompat
import io.github.guohangbk.tally.data.debug.DebugLog
import io.github.guohangbk.tally.domain.model.TallyError
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
     * [onLevel] 收引擎回传的音量（0f..1f），气泡用它画声纹。
     * [onPartial] 收半截文字；引擎不支持 partial 时一次都不会回调，气泡只显示声纹。
     */
    suspend fun listen(
        onLevel: (Float) -> Unit = {},
        onPartial: (String) -> Unit = {},
    ): SpeechOutcome

    /** 松手时调用：让引擎立刻收尾，而不是等它自己判断静音结束。 */
    fun stopListening()
}

/**
 * 系统 SpeechRecognizer 的挂起封装（不自建 ASR，也不把音频发给小莉）。
 *
 * **引擎调用只能在主线程**：`createSpeechRecognizer` / `setRecognitionListener` /
 * `startListening` / `stopListening` / `cancel` 入口都会检查 `Looper.myLooper()`，
 * 换到别的线程直接抛 RuntimeException（见 `SpeechRecognizerImpl.checkIsCalledFromMainThread`）。
 * 而且框架自己把命令塞进一条绑在主 Looper 的 Handler、回调也从主 Looper 出来——
 * 「用专用线程把语音服务隔离在外」在这个 API 上做不到。
 *
 * 所以这里只保证两件事：调用方协程不在主线程干等（[listen] 跑在 Default 上），
 * 结果另有一颗独立看门狗兜底。主线程真被引擎堵住时界面仍会停，
 * 但这一轮一定会在 [RESULT_GRACE_MILLIS] 后被收尾，不会永远挂在「正在识别」。
 *
 * **一句一颗识别器**：建、用、destroy 都落在同一句里。真机上所有冻结都出在
 * 「进程内第二句复用上一句留下的识别器」上，第一句从未冻过，所以不再复用。
 *
 * 另有一只看门狗线程盯着主线程心跳：卡住超过两秒就把主线程栈写进 logcat 与文件日志，
 * 让下一次冻结自己留下现场。
 *
 * 各家的内置引擎对参数组合的容忍度差别很大：小米 / 讯飞一类不认 `EXTRA_PREFER_OFFLINE`，
 * 缺 `EXTRA_CALLING_PACKAGE` 会直接回 ERROR_SERVER，Google 那套又要求语言包。
 * 所以这里按「端侧优先 → 引擎默认」试两次，两次都失败才降级；失败码一律进 logcat。
 */
class SystemSpeechPort(context: Context) : SpeechPort {
    private val appContext = context.applicationContext

    init {
        MainThreadWatch.install()
    }

    /** 引擎调用唯一合法的线程：只用来排队，不让调用方协程 `withContext(Main)` 硬等。 */
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 当前这一句的识别器；只在一次 [attempt] 的生命周期内存在。 */
    @Volatile
    private var recognizer: SpeechRecognizer? = null

    /** 正在「台上」的那一次：0 表示没有活跃会话，松手时不必去叫引擎。 */
    @Volatile
    private var activeAttempt = 0

    /**
     * 松手早于 `startListening` 落地时的补救位。
     *
     * 置位（松手）与消费（下发那一步）都在主线程上，不需要额外的同步。
     */
    @Volatile
    private var stopWanted = false

    /**
     * 当前这一句的兜底看门狗。
     *
     * 它不在外层协程的 Job 树上（主线程被引擎堵住时，外层取消不一定跑得到），
     * 所以由这里持有引用：下一次尝试开始时掐掉上一颗，不会留下一直在 `delay(80)` 轮询的孤儿协程。
     */
    @Volatile
    private var watchdog: Job? = null

    /** 每次收听的序号：上一句迟到的回调不能落进这一句。 */
    @Volatile
    private var attemptSeq = 0

    /** 上一次会话收尾的时刻：紧挨着再开一句，引擎会当成同一条还没收完的会话。 */
    @Volatile
    private var lastSessionEndedAt = 0L

    /** 松手时刻（elapsedRealtime）；0 表示这一轮还没松手。 */
    @Volatile
    private var stopRequestedAt = 0L

    override fun isAvailable(): Boolean = hasPermission() && SpeechRecognizer.isRecognitionAvailable(appContext)

    override fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /**
     * 松手：记下时刻，并让引擎**立刻**收尾，而不是等它自己判断静音结束。
     *
     * 框架只允许在主线程叫引擎，所以这里只排队、不等待：主线程万一被堵住，
     * 这一句仍由看门狗收尾。
     */
    override fun stopListening() {
        stopRequestedAt = SystemClock.elapsedRealtime()
        // 手指抬得比「下发」还早也要算数：补位由 attempt 里「下发」那一步消费。
        stopWanted = true
        if (activeAttempt == 0) return
        mainHandler.post { stopEngine() }
    }

    /** 必须在主线程调用：叫停当前会话；引擎还没建出来就记下来，等「下发」那一步补。 */
    private fun stopEngine() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { stopEngine() }
            return
        }
        // 这一句已经收尾（识别器已排队销毁）时迟到的叫停不能落进下一句。
        if (activeAttempt == 0) return
        val engine = recognizer
        if (engine == null) {
            stopWanted = true
            return
        }
        runCatching { engine.stopListening() }
            .onFailure { Log.w(TAG, "stopListening 失败：${it::class.simpleName} ${it.message.orEmpty()}") }
    }

    override suspend fun listen(
        onLevel: (Float) -> Unit,
        onPartial: (String) -> Unit,
    ): SpeechOutcome = withContext(Dispatchers.Default) {
        stopRequestedAt = 0L
        // 有引擎 ≠ 有权限：两件事分开报，否则用户会照着错误的提示去查设置。
        val mic = hasPermission()
        val engine = SpeechRecognizer.isRecognitionAvailable(appContext)
        DebugLog.log(TAG, "listen 开始 · RECORD_AUDIO=${if (mic) "已授权" else "未授权"} · 识别引擎=${if (engine) "可用" else "不可用"}")
        Log.i(TAG, "listen 开始 · RECORD_AUDIO=${if (mic) "已授权" else "未授权"} · 识别引擎=${if (engine) "可用" else "不可用"}")
        if (!mic) {
            DebugLog.log(TAG, "拒绝原因：应用没有 RECORD_AUDIO")
            return@withContext SpeechOutcome.Unavailable("需要麦克风权限才能语音记账，也可以直接打字")
        }
        if (!engine) {
            DebugLog.log(TAG, "拒绝原因：系统里没有 RecognitionService（或包可见性没放行）")
            return@withContext SpeechOutcome.Unavailable(TallyError.SpeechNotAvailable.message)
        }
        val first = attemptWithQuietRestart(onLevel, onPartial, preferOffline = true)
        // 已经松手就不再起第二次：第二次没人负责收尾，界面会白白多等一轮。
        if (!first.retryable || stopRequestedAt > 0L) return@withContext first.outcome
        DebugLog.log(TAG, "端侧优先那次没成（${first.outcome.log()}），改用引擎默认参数再试一次")
        Log.i(TAG, "端侧优先那次没成（${first.outcome.log()}），改用引擎默认参数再试一次")
        val second = attemptWithQuietRestart(onLevel, onPartial, preferOffline = false)
        when {
            !second.retryable || stopRequestedAt > 0L -> second.outcome
            // 上一句还没在服务里收完（取消后立刻又按住）：等一句就好，别把人踢去系统语音界面。
            second.code == SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> SpeechOutcome.NothingHeard("识别正忙，稍后再试")
            // 两套参数都被拒，就不是「没听清」而是这台机器的引擎不给用了。
            // 小米/HyperOS 上系统语音服务会回 9（没麦克风）或 11（断连），而应用权限明明给了——
            // 这条只能绕开：交给系统自己的语音输入界面，麦克风由系统应用持有。
            else -> {
                DebugLog.log(TAG, "两次都被拒（error=9/11 这类），转系统语音输入界面")
                SpeechOutcome.NeedsSystemInput(ENGINE_DEAD)
            }
        }
    }

    /**
     * 一次尝试，外加「按住没说话」的自愈：引擎按静音判停（NO_MATCH / SPEECH_TIMEOUT）时，
     * 只要手指还没松开就重开一次收音，别让几秒沉默把这一次按住判成失败。
     * 重启有上限，否则一直不说话就会无限占着麦克风。
     */
    private suspend fun attemptWithQuietRestart(
        onLevel: (Float) -> Unit,
        onPartial: (String) -> Unit,
        preferOffline: Boolean,
    ): Attempt {
        var result = attempt(onLevel, onPartial, preferOffline)
        var restarts = 0
        while (result.code != null && result.code in quietErrors && stopRequestedAt == 0L && restarts < MAX_QUIET_RESTARTS) {
            restarts++
            DebugLog.log(TAG, "按住时引擎判了静音（error=${result.code}），重开收音第 $restarts 次")
            Log.i(TAG, "按住时引擎判了静音（error=${result.code}），重开收音第 $restarts 次")
            result = attempt(onLevel, onPartial, preferOffline)
        }
        return result
    }

    /**
     * 一次识别尝试。结果要么由引擎回调给出，要么由看门狗兜底：
     * 松手之后引擎最多再宽限 [RESULT_GRACE_MILLIS]，再不给结果就自行收尾。
     * 没有这条，任何一次引擎不回话都会让界面永远停在「正在识别」。
     */
    private suspend fun attempt(
        onLevel: (Float) -> Unit,
        onPartial: (String) -> Unit,
        preferOffline: Boolean,
    ): Attempt {
        // 上一句刚收完就再按住：先隔开一点，免得引擎把两次当成同一条还没收完的会话。
        val sinceLast = SystemClock.elapsedRealtime() - lastSessionEndedAt
        if (lastSessionEndedAt > 0L && sinceLast in 1 until SESSION_GAP_MILLIS) {
            delay(SESSION_GAP_MILLIS - sinceLast)
        }
        // 松手比「下发」更早到时靠这个补位；每句开始先清掉上一句的残留。
        stopWanted = false
        // 这一句自己的识别器：建连接、下发、收尾、销毁都落在这一句里，不跨句复用。
        val engine = createRecognizer()
            ?: return Attempt(SpeechOutcome.Unavailable(TallyError.SpeechNotAvailable.message), retryable = false)
        val id = ++attemptSeq
        val startedAt = SystemClock.elapsedRealtime()
        val gate = CompletableDeferred<Attempt>()
        // 上一句迟到的结果不能当成这一句的：回调只在本句还「在台上」时才放行。
        val emit: (SpeechOutcome, Int?) -> Unit = { result, code ->
            if (id == attemptSeq) {
                gate.complete(Attempt(result, retryable = code != null && code in retryableErrors, code = code))
            }
        }
        val released = { stopRequestedAt > 0L }
        val listener = SingleShotListener(onLevel, onPartial, hasPermission(), emit, released, ::stopEngine)
        // 看门狗跑在自己的调度器上，而且不带外层的 Job：主线程被引擎堵住时，
        // 这一句仍然会被按时收尾。它只被「这一次收尾」或「下一次尝试」掐掉，所以不会有孤儿协程。
        watchdog?.cancel()
        val guard = CoroutineScope(Dispatchers.Default).launch {
            while (isActive) {
                delay(80)
                val now = SystemClock.elapsedRealtime()
                val releasedAt = stopRequestedAt
                val quiet = releasedAt > 0L && now - releasedAt >= RESULT_GRACE_MILLIS
                // 手势丢了、一直没松手也不能无限等：硬上限一到就自己收尾。
                val stuck = now - startedAt >= HARD_LIMIT_MILLIS
                if (quiet || stuck) {
                    DebugLog.log(
                        TAG,
                        if (quiet) {
                            "松手 ${RESULT_GRACE_MILLIS}ms 引擎仍无结果，自行收尾"
                        } else {
                            "超过 ${HARD_LIMIT_MILLIS / 1000} 秒仍无结果，自行收尾"
                        },
                    )
                    Log.i(TAG, if (quiet) "松手兜底收尾" else "硬上限收尾")
                    emit(SpeechOutcome.NothingHeard(HEARD_NOTHING), null)
                    break
                }
            }
        }
        watchdog = guard
        return try {
            activeAttempt = id
            // 框架硬性要求这两条在主线程，而且它自己的命令队列与回调也都绑在主 Looper，没有别的线程可换。
            withContext(Dispatchers.Main.immediate) {
                runCatching {
                    engine.setRecognitionListener(listener)
                    engine.startListening(listeningIntent(preferOffline))
                }.onFailure { failure ->
                    DebugLog.log(TAG, "引擎调用失败：${failure::class.simpleName} ${failure.message.orEmpty()}")
                    Log.w(TAG, "引擎调用失败：${failure::class.simpleName} ${failure.message.orEmpty()}")
                    emit(SpeechOutcome.NothingHeard(HEARD_NOTHING), null)
                }
                DebugLog.log(TAG, "识别已下发 · preferOffline=$preferOffline")
                Log.i(TAG, "识别已下发 · preferOffline=$preferOffline")
                // 松手比这条消息还早到（手指一点就抬）：这里补一次收尾，别让引擎一直录到硬上限。
                if (stopWanted) {
                    stopWanted = false
                    stopEngine()
                }
            }
            val result = gate.await()
            DebugLog.log(
                TAG,
                "attempt(preferOffline=$preferOffline) → ${result.outcome.log()}" +
                    (result.code?.let { " · error=$it" } ?: "") +
                    " · 半截文字=${if (listener.sawPartial) "有" else "无"}" +
                    " · 音量回调=${if (listener.sawLevel) "有" else "无"}",
            )
            result
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            // 个别 ROM 在语音服务被杀时会从 startListening 当场抛，这属于「引擎用不了」，
            // 不该带着整页一起崩。
            DebugLog.log(TAG, "引擎调用失败：${failure::class.simpleName} ${failure.message.orEmpty()}")
            Attempt(SpeechOutcome.NothingHeard(HEARD_NOTHING), retryable = false)
        } finally {
            if (activeAttempt == id) activeAttempt = 0
            guard.cancel()
            destroyEngine(engine)
            lastSessionEndedAt = SystemClock.elapsedRealtime()
        }
    }

    private data class Attempt(val outcome: SpeechOutcome, val retryable: Boolean, val code: Int? = null)

    /**
     * 每一句建一颗新识别器。真机上每一次冻结都出在「复用上一句留下的识别器」上，
     * 进程内第一句（新连的识别器）从来没冻过——所以连接从这一句开始，也在这一句结束。
     *
     * **必须在主线程调用**：框架在入口检查线程，换一条线程直接抛 RuntimeException。
     */
    private suspend fun createRecognizer(): SpeechRecognizer? {
        val createdAt = SystemClock.elapsedRealtime()
        val created = try {
            withContext(Dispatchers.Main.immediate) {
                DebugLog.log(TAG, "创建识别器… · 线程=${Thread.currentThread().name}")
                Log.i(TAG, "创建识别器… · 线程=${Thread.currentThread().name}")
                SpeechRecognizer.createSpeechRecognizer(appContext)
            }
        } catch (failure: Exception) {
            DebugLog.log(TAG, "识别器创建失败：${failure::class.simpleName} ${failure.message.orEmpty()}")
            Log.w(TAG, "识别器创建失败：${failure::class.simpleName} ${failure.message.orEmpty()}")
            return null
        }
        // 建连接这一步会同步 bindService：它慢，说明语音服务这次不妙，先把耗时记下来。
        DebugLog.log(TAG, "识别器已就绪 · 建连接耗时 ${SystemClock.elapsedRealtime() - createdAt}ms")
        Log.i(TAG, "识别器已就绪 · 建连接耗时 ${SystemClock.elapsedRealtime() - createdAt}ms")
        recognizer = created
        return created
    }

    /** 收尾：把这一句的识别器排到主线程销毁，调用方不等它。主线程真被堵住时，界面不受影响。 */
    private fun destroyEngine(engine: SpeechRecognizer) {
        if (recognizer === engine) recognizer = null
        mainHandler.post {
            runCatching { engine.destroy() }
                .onFailure { Log.w(TAG, "destroy 失败：${it::class.simpleName} ${it.message.orEmpty()}") }
        }
    }

    private fun listeningIntent(preferOffline: Boolean): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        // 由 Service 侧发起时引擎拿不到调用方包名，缺这个参数不少国产引擎会立刻回错。
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        // 按住说话的静音容忍：引擎默认几秒不说话就判「说完了」，
        // 手指还按着的人会突然收到「没听清」。拉长到 [SILENCE_LENGTH_MILLIS]。
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, SILENCE_LENGTH_MILLIS)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, SILENCE_LENGTH_MILLIS)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, MINIMUM_LENGTH_MILLIS)
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

        /** 引擎「没听到话」的两个码：按住时遇到它们不算失败，重开一次收音接着听。 */
        val quietErrors = setOf(SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT)

        /** 一句话最多重开几次收音；再多就等于一直占着麦克风不放了。 */
        const val MAX_QUIET_RESTARTS = 2

        /** 判定「说完了」的静音长度，拉长到手指按住时的耐心范围。 */
        const val SILENCE_LENGTH_MILLIS = 8_000L

        /** 收音最短时长：低于它的静音判停不触发。 */
        const val MINIMUM_LENGTH_MILLIS = 1_000L
    }
}

private const val TAG = "TallySpeech"

/**
 * 主线程心跳：两秒没跳就取两帧相隔 1s 的栈来区分「真卡死」和「CPU 被抢」。
 *
 * 真机上冻结时整个进程都不再出日志（连后台写文件的线程也停），只有 system_server 侧的
 * logcat 缓冲还留着。这颗线程只读一个计数，常驻开销可以忽略；下一次冻结自己会留下栈。
 *
 * 两帧栈相同 = 主线程真停在同一处；两帧不同 = 主线程还在往前走，多半是整机 CPU 被
 * 离线识别等负载抢到饿死——这时把全线程栈一并写出，看是谁在吃 CPU。
 */
private object MainThreadWatch {
    private const val BEAT_MILLIS = 500L
    private const val STALL_MILLIS = 2_000L
    private const val SAMPLE_GAP_MILLIS = 1_000L

    private val beatAt = AtomicLong(SystemClock.elapsedRealtime())
    private val beatSeq = AtomicLong(0L)
    private val reportedSeq = AtomicLong(-1L)
    private val stalledAt = AtomicLong(0L)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val beat = object : Runnable {
        override fun run() {
            beatAt.set(SystemClock.elapsedRealtime())
            beatSeq.incrementAndGet()
            val stallStart = stalledAt.get()
            if (stallStart != 0L && stalledAt.compareAndSet(stallStart, 0L)) {
                val recovered = "主线程恢复 · 卡了 ${SystemClock.elapsedRealtime() - stallStart}ms"
                Log.w(TAG, recovered)
                DebugLog.log(TAG, recovered)
            }
            mainHandler.postDelayed(this, BEAT_MILLIS)
        }
    }

    @Volatile
    private var installed = false

    fun install() {
        if (installed) return
        installed = true
        mainHandler.post(beat)
        Thread({
            while (true) {
                Thread.sleep(BEAT_MILLIS)
                val silentFor = SystemClock.elapsedRealtime() - beatAt.get()
                if (silentFor < STALL_MILLIS) continue
                val seq = beatSeq.get()
                if (reportedSeq.getAndSet(seq) == seq) continue
                report(silentFor)
            }
        }, "TallyMainWatch").apply { isDaemon = true }.start()
    }

    private fun report(silentFor: Long) {
        stalledAt.compareAndSet(0L, beatAt.get())
        val main = Looper.getMainLooper().thread
        val first = main.stackTrace
        Thread.sleep(SAMPLE_GAP_MILLIS)
        val second = main.stackTrace
        val moving = !first.contentEquals(second)
        val text = buildString {
            append("主线程 ${silentFor}ms 没有心跳，间隔 ${SAMPLE_GAP_MILLIS}ms 两帧栈")
            append(if (moving) "不同 → 主线程还在动，像 CPU 被抢而不是卡死" else "相同 → 真卡在这一处")
            append("，栈：")
            second.take(30).forEach { append("\n    at ").append(it) }
            if (moving) {
                append("\n全线程：")
                Thread.getAllStackTraces().entries
                    .sortedByDescending { it.key.state == Thread.State.RUNNABLE }
                    .forEach { (thread, stack) ->
                        append("\n    ").append(thread.name).append(" [").append(thread.state).append("] ")
                            .append(stack.firstOrNull() ?: "—")
                    }
            }
        }
        Log.w(TAG, text)
        DebugLog.log(TAG, text)
    }
}

/** 松手之后等结果的上限：端点一般一秒内就回，超过这个数多半是引擎自己卡住了。 */
private const val RESULT_GRACE_MILLIS = 3_000L

/** 一次收音的硬上限（含按住的那 20 秒）：到点无论引擎说什么都收尾，不把页面挂在识别上。 */
private const val HARD_LIMIT_MILLIS = 30_000L

/** 两句之间至少隔这么久：引擎刚收完尾就再下发，MIUI 的语音服务会当成同一条会话。 */
private const val SESSION_GAP_MILLIS = 300L

private const val HEARD_NOTHING = "没有听清，请重试或输入文字"

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
    private val onPartial: (String) -> Unit,
    private val appHasMic: Boolean,
    private val emit: (SpeechOutcome, Int?) -> Unit,
    /** 手指是否已经松开（松手才允许去叫停引擎）。 */
    private val released: () -> Boolean,
    /** 主线程上的「叫停」，由 [onEndOfSpeech] 触发；设备端识别不出来时靠它兜一次。 */
    private val stopEngine: () -> Unit,
) : RecognitionListener {
    /** 同一句会被反复回传，只把变化过的那一版交给 UI，避免组合阶段被同一条字符串重刷。 */
    private var lastPartial: String? = null

    /** 引擎有没有回过音量：一直没回调，气泡就只剩静态图标，记下来好判断是引擎不给还是我们没接。 */
    @Volatile
    var sawLevel = false
        private set

    /** 这一次到底有没有半截文字：写进日志，好回答「实时文字到底是引擎不给还是我们没接」。 */
    val sawPartial: Boolean get() = lastPartial != null

    override fun onReadyForSpeech(params: Bundle?) {
        DebugLog.log(TAG, "引擎就绪，可以说了")
        Log.i(TAG, "引擎就绪，可以说了")
    }

    override fun onBeginningOfSpeech() {
        DebugLog.log(TAG, "引擎开始收音频")
        Log.i(TAG, "引擎开始收音频")
    }

    /** 引擎回传的 rms 大致在 -2..10dB，归一化成 0f..1f 给气泡画声纹。 */
    override fun onRmsChanged(rmsdB: Float) {
        if (!sawLevel) {
            sawLevel = true
            DebugLog.log(TAG, "第一帧音量 rms=$rmsdB")
            Log.i(TAG, "第一帧音量 rms=$rmsdB")
        }
        onLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
    }

    override fun onBufferReceived(buffer: ByteArray?) = Unit

    /**
     * 引擎自己判到了「说完了」，而且已经松手：这时候再叫停一次，逼它把最终结果吐出来。
     * 只在这个回调是「最后一条」时才回一次——真机上就见过端点判完却迟迟不给 onResults 的。
     */
    override fun onEndOfSpeech() {
        if (!ended) {
            ended = true
            DebugLog.log(TAG, "引擎判定说完了 · 已松手=${released()}")
            Log.i(TAG, "引擎判定说完了 · 已松手=${released()}")
        }
        if (released()) stopEngine()
    }

    private var ended = false

    /** 半截结果不是所有引擎都给：一直收不到就只显示声纹，不算失败。 */
    override fun onPartialResults(partialResults: Bundle?) {
        val text = partialResults
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            ?.trim()
            .orEmpty()
        if (text.isBlank() || text == lastPartial) return
        val first = lastPartial == null
        lastPartial = text
        if (first) DebugLog.log(TAG, "引擎开始回半截文字「$text」")
        onPartial(text)
    }

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
