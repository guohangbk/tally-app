package io.github.guohangbk.tally.ui.shell

import android.content.Intent
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.roundToInt
import io.github.guohangbk.tally.data.debug.DebugLog
import io.github.guohangbk.tally.data.speech.SpeechOutcome
import io.github.guohangbk.tally.di.rememberAppGraph
import io.github.guohangbk.tally.ui.design.ButtonVariant
import io.github.guohangbk.tally.ui.design.InlineIcon
import io.github.guohangbk.tally.ui.design.TallyButton
import io.github.guohangbk.tally.ui.design.TallyType
import io.github.guohangbk.tally.ui.design.tallyColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 调试控制台：只有设置里连点包名 5 次开启后才会出现。
 * 它要解决的是一个具体问题——麦克风权限在 logcat 里，而手机不一定连得上电脑。
 * 点开能看到应用自己记的日志，也能一键把整段分享出来。
 */
/** 现场体检：权限、识别引擎、以及系统把识别请求交给哪个包。 */
private fun environmentSummary(context: android.content.Context): String {
    val mic = androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED
    val engine = android.speech.SpeechRecognizer.isRecognitionAvailable(context)
    val service = runCatching {
        // 识别服务是用 RecognitionService 这个 action 暴露的，不是 ACTION_RECOGNIZE_SPEECH。
        val intent = android.content.Intent("android.speech.RecognitionService")
        context.packageManager.queryIntentServices(intent, 0).firstOrNull()?.serviceInfo?.packageName
    }.getOrNull()
    return "麦克风=${if (mic) "已授权" else "未授权"} · 识别引擎=${if (engine) "可用" else "不可用"}" +
        (service?.let { " · 服务=$it" } ?: "")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugConsoleOverlay(modifier: Modifier = Modifier) {
    val colors = tallyColors
    val context = LocalContext.current
    val lines by DebugLog.lines.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf(false) }
    // 悬浮气泡可拖动：offset 相对其对齐好的初始位置累加。
    var offsetX by remember { mutableStateOf(0f) }
    var offsetY by remember { mutableStateOf(0f) }
    val graph = rememberAppGraph()
    val scope = rememberCoroutineScope()
    var testing by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
                .clip(RoundedCornerShape(11.dp))
                .background(colors.ink.copy(alpha = 0.82f))
                .clickable { open = true }
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        offsetX += dragAmount.x
                        offsetY += dragAmount.y
                    }
                }
                .padding(horizontal = 9.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            InlineIcon(iconKey = "document", contentDescription = null, size = 11.dp, tint = colors.gold)
            Text(
                text = "console ${lines.size}",
                fontSize = 10.sp, lineHeight = 12.5.sp,
                fontFamily = FontFamily.Monospace,
                color = colors.paper,
            )
        }
    }

    if (open) {
        ModalBottomSheet(
            onDismissRequest = { open = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = colors.paper,
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 15.dp).padding(bottom = 18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("控制台", style = TallyType.sectionTitle, color = colors.ink, modifier = Modifier.weight(1f))
                    Text(
                        text = "${lines.size} 行",
                        style = TallyType.helper,
                        color = colors.ink3,
                        modifier = Modifier.padding(end = 12.dp),
                    )
                    InlineIcon(
                        iconKey = "close",
                        contentDescription = "关闭控制台",
                        size = 16.dp,
                        tint = colors.ink3,
                        modifier = Modifier.clickable {
                            open = false
                            // 直接关掉调试模式：气泡从所有页面移走，要去设置里连点 5 次才会再开。
                            // 用脱离组合作用域的 scope：debugMode 一变 false 本组件就被移除，
                            // rememberCoroutineScope 会被取消，可能中断这次 DataStore 写入。
                            CoroutineScope(Dispatchers.IO).launch { graph.preferences.setDebugMode(false) }
                        },
                    )
                }
                Spacer(Modifier.height(6.dp))
                // 打开控制台时现算一次环境：这两行基本能直接回答「为什么录不了音」。
                val env = remember(open) { environmentSummary(context) }
                Text(
                    text = env,
                    fontSize = 10.sp,
                    lineHeight = 14.sp,
                    fontFamily = FontFamily.Monospace,
                    color = colors.ink3,
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    TallyButton(
                        label = if (testing) "识别测试中…" else "语音识别测试",
                        onClick = {
                            if (!testing) {
                                testing = true
                                scope.launch {
                                    DebugLog.log("SherpaTest", "语音识别测试：请对麦克风说一句话，约 4 秒后自动结束")
                                    val listen = async { graph.speechPort.listen() }
                                    delay(4_000)
                                    val stopAt = System.currentTimeMillis()
                                    graph.speechPort.stopListening()
                                    val outcome = try {
                                        listen.await()
                                    } catch (t: Throwable) {
                                        null
                                    }
                                    val cost = System.currentTimeMillis() - stopAt
                                    when (outcome) {
                                        is SpeechOutcome.Heard -> DebugLog.log("SherpaTest", "识别成功「${outcome.text}」· 松手后 ${cost}ms 出结果")
                                        is SpeechOutcome.NothingHeard -> DebugLog.log("SherpaTest", "没听清：${outcome.reason}（收尾 ${cost}ms）")
                                        is SpeechOutcome.Unavailable -> DebugLog.log("SherpaTest", "不可用：${outcome.reason}（多半是没有可用的系统语音服务）")
                                        is SpeechOutcome.NeedsSystemInput -> DebugLog.log("SherpaTest", "转系统输入：${outcome.reason}")
                                        SpeechOutcome.Cancelled, null -> DebugLog.log("SherpaTest", "测试被取消或异常")
                                    }
                                    testing = false
                                }
                            }
                        },
                        variant = ButtonVariant.GOLD,
                        height = 34,
                        enabled = !testing,
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    TallyButton(
                        label = "分享日志",
                        onClick = {
                            val dump = DebugLog.dump(
                                "Tally ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                            )
                            context.startActivity(
                                Intent.createChooser(
                                    Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_TEXT, dump)
                                    },
                                    "分享日志",
                                ),
                            )
                        },
                        variant = ButtonVariant.BRAND,
                        height = 34,
                        modifier = Modifier.weight(1f),
                    )
                    TallyButton(
                        label = "清空",
                        onClick = DebugLog::clear,
                        variant = ButtonVariant.GHOST,
                        height = 34,
                        modifier = Modifier.size(width = 76.dp, height = 34.dp),
                    )
                }
                Spacer(Modifier.height(6.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp, max = 460.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(colors.card)
                        .border(1.dp, colors.line, RoundedCornerShape(12.dp)),
                ) {
                    if (lines.isEmpty()) {
                        Text(
                            "还没有日志。用一次语音或触发一次权限请求，这里就会有记录。",
                            style = TallyType.helper,
                            color = colors.ink3,
                            modifier = Modifier.padding(10.dp),
                        )
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                            items(lines) { line ->
                                Text(
                                    text = line,
                                    fontSize = 10.sp,
                                    lineHeight = 14.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = colors.ink2,
                                    modifier = Modifier.padding(vertical = 1.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
