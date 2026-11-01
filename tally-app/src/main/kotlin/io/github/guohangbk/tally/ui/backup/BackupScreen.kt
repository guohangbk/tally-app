package io.github.guohangbk.tally.ui.backup

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.guohangbk.tally.data.backup.BackupContainer
import io.github.guohangbk.tally.data.backup.BackupService
import io.github.guohangbk.tally.di.rememberAppGraph
import io.github.guohangbk.tally.domain.model.BackupManifest
import io.github.guohangbk.tally.domain.model.WebDavConfig
import io.github.guohangbk.tally.domain.model.WebDavFile
import io.github.guohangbk.tally.ui.common.TallyDialogField
import io.github.guohangbk.tally.ui.common.tallyViewModel
import io.github.guohangbk.tally.ui.design.ButtonVariant
import io.github.guohangbk.tally.ui.design.InlineIcon
import io.github.guohangbk.tally.ui.design.TallyButton
import io.github.guohangbk.tally.ui.design.TallyCard
import io.github.guohangbk.tally.ui.design.TallyConfirmDialog
import io.github.guohangbk.tally.ui.design.TallyListRow
import io.github.guohangbk.tally.ui.design.TallyRowChevron
import io.github.guohangbk.tally.ui.design.TallySectionLabel
import io.github.guohangbk.tally.ui.design.TallyTag
import io.github.guohangbk.tally.ui.design.TallyToastEffects
import io.github.guohangbk.tally.ui.design.TallyType
import io.github.guohangbk.tally.ui.design.TagTone
import io.github.guohangbk.tally.ui.design.tallyColors
import io.github.guohangbk.tally.ui.shell.TallyTopBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId

/**
 * P19 备份与同步：这一屏只回答「换机之后哪些东西会回来」。
 * 导出的包会写进本机存储目录，路径直接印在屏上；恢复先校验，失败不动现有数据。
 */
@Composable
fun BackupScreen(onBack: () -> Unit) {
    val graph = rememberAppGraph()
    val context = LocalContext.current
    val viewModel = tallyViewModel { BackupViewModel(graph, context.applicationContext) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val colors = tallyColors

    var showExportDialog by remember { mutableStateOf(false) }
    var showWebDavDialog by remember { mutableStateOf(false) }
    var uploadPassword by remember { mutableStateOf<String?>(null) }

    fun shareUri(uri: Uri) {
        context.startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "application/octet-stream"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
                "分享备份包",
            ),
        )
    }

    val pickBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val bytes = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                }
                if (bytes != null) {
                    viewModel.prepareRestore(bytes, uri.lastPathSegment?.substringAfterLast('/') ?: "backup.tally")
                }
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(colors.paper)) {
        TallyTopBar(
            title = "备份与同步",
            onBack = onBack,
            trailing = { TallyTag(text = "本机", tone = TagTone.NEUTRAL) },
        )

        // 提示改成贴底气泡，保留「操作没有完成」这个定性说法。
        TallyToastEffects(
            notice = state.notice,
            error = state.error,
            errorTitle = "操作没有完成",
            onNoticeShown = viewModel::clearMessages,
            onErrorShown = viewModel::clearMessages,
        )

        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 15.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            BackupSteps(
                labels = listOf("导出", "打包", "校验", "完成"),
                current = when {
                    state.exportResult != null -> 3
                    state.busy -> 1
                    state.history.isNotEmpty() -> 0
                    else -> 0
                },
            )

            TallyCard(corner = 15, contentPadding = 4.dp) {
                Column {
                    TallyListRow(
                        title = "导出本地数据库备份",
                        subtitle = ".tally · ZIP + AES-256 · " +
                            (state.settings.lastBackupAt?.let { "上次 ${formatBackupTime(it)}" } ?: "尚未备份"),
                        iconKey = "backup",
                        onClick = { showExportDialog = true },
                        trailing = { InlineIcon(iconKey = "export", contentDescription = null, size = 15.dp, tint = colors.brand) },
                    )
                    TallyListRow(
                        title = "从备份包恢复",
                        subtitle = "先校验后替换 · 失败则现有数据不动",
                        iconKey = "import",
                        onClick = { pickBackup.launch(arrayOf("*/*")) },
                        trailing = { TallyRowChevron() },
                    )
                }
            }

            state.exportResult?.let { saved ->
                VerifiedStrip(
                    title = if (saved.digest.isBlank()) "备份已生成" else "校验通过 · SHA-256 ${saved.digest}…",
                    meta = "${formatBytes(saved.sizeBytes)} · ${saved.billCount} 笔账单 · " +
                        if (saved.includesImages) "含图片" else "不含图片",
                    path = saved.absolutePath,
                )
            }

            Column {
                TallySectionLabel("备份历史 · 手动快照")
                if (state.history.isEmpty()) {
                    Text(
                        "还没有本机备份包。",
                        style = TallyType.helper,
                        color = colors.ink3,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                } else {
                    TallyCard(corner = 15, contentPadding = 4.dp) {
                        Column {
                            state.history.forEachIndexed { index, item ->
                                if (index > 0) Spacer(Modifier.height(1.dp))
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { shareUri(item.uri) }
                                        .padding(horizontal = 10.dp, vertical = 9.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(item.fileName, style = TallyType.listTitle, color = colors.ink, maxLines = 1)
                                        Text(
                                            "${formatBytes(item.sizeBytes)} · ${formatBackupTime(item.createdAt)} · " +
                                                if (state.remoteFiles.any { it.name == item.fileName }) "WebDAV 已同步" else "未上传",
                                            style = TallyType.helper,
                                            color = colors.ink3,
                                        )
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    InlineIcon(iconKey = "share", contentDescription = "分享", size = 15.dp, tint = colors.ink3)
                                }
                            }
                        }
                    }
                }
            }

            WebDavSection(
                config = state.webDav,
                hasPassword = state.hasWebDavPassword,
                busy = state.busy,
                remoteFiles = state.remoteFiles,
                remoteLoaded = state.remoteLoaded,
                onConfigure = { showWebDavDialog = true },
                onRefresh = viewModel::refreshRemote,
                onUpload = { uploadPassword = "" },
                onDownload = viewModel::downloadFromWebDav,
            )

            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Footnote("API Key 与 WebDAV 密码永不进入备份包")
                Footnote("只做手动快照与每周提醒，不后台静默上传")
            }
            Spacer(Modifier.height(14.dp))
        }
    }

    if (showExportDialog) {
        ExportPasswordDialog(
            busy = state.busy,
            includeImages = state.includeImages,
            estimateBytes = state.imageEstimateBytes,
            onToggleImages = viewModel::setIncludeImages,
            onConfirm = { password ->
                showExportDialog = false
                viewModel.export(password)
            },
            onDismiss = { showExportDialog = false },
        )
    }

    state.exportResult?.let { saved ->
        ExportReadyDialog(
            saved = saved,
            onShare = {
                shareUri(saved.uri)
                viewModel.dismissExport()
            },
            onDismiss = viewModel::dismissExport,
        )
    }

    state.pendingRestore?.let { pending ->
        RestoreDialog(
            manifest = pending.manifest,
            busy = state.busy,
            onConfirm = viewModel::restore,
            onDismiss = viewModel::cancelRestore,
        )
    }

    if (showWebDavDialog) {
        WebDavConfigDialog(
            initial = state.webDav,
            hasPassword = state.hasWebDavPassword,
            busy = state.busy,
            onSave = { config, password ->
                showWebDavDialog = false
                viewModel.saveWebDav(config, password)
            },
            onDismiss = { showWebDavDialog = false },
        )
    }

    uploadPassword?.let {
        PasswordDialog(
            title = "上传到 WebDAV",
            message = "将生成新的加密备份包并上传到 ${state.webDav.remoteDirectory} 目录。",
            confirmLabel = "上传",
            busy = state.busy,
            onConfirm = { password ->
                uploadPassword = null
                viewModel.uploadToWebDav(password)
            },
            onDismiss = { uploadPassword = null },
        )
    }
}

/** 导出 → 打包 → 校验 → 完成：让人知道现在卡在哪一段。 */
@Composable
private fun BackupSteps(labels: List<String>, current: Int) {
    val colors = tallyColors
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        labels.forEachIndexed { index, label ->
            val done = index <= current
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(if (done) colors.brand else colors.line),
            )
            Text(label, style = TallyType.helper, color = if (done) colors.ink2 else colors.ink3)
            if (index != labels.lastIndex) {
                Box(modifier = Modifier.weight(1f).height(1.dp).background(colors.line))
            }
        }
    }
}

@Composable
private fun VerifiedStrip(title: String, meta: String, path: String) {
    val colors = tallyColors
    TallyCard(corner = 14, borderColor = colors.brand.copy(alpha = 0.32f), contentPadding = 11.dp) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .background(colors.brandSurface),
                    contentAlignment = Alignment.Center,
                ) {
                    InlineIcon(iconKey = "check", contentDescription = null, size = 12.dp, tint = colors.brand)
                }
                Text(title, style = TallyType.listTitle, color = colors.ink, modifier = Modifier.weight(1f), maxLines = 1)
                TallyTag(text = "已校验", tone = TagTone.BRAND)
            }
            Spacer(Modifier.height(5.dp))
            Text(meta, style = TallyType.helper, color = colors.ink3)
            Text(
                "存于 $path",
                style = TallyType.helper.copy(fontSize = 10.5.sp, lineHeight = 15.sp),
                color = colors.ink3,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
    }
}

@Composable
private fun Footnote(text: String) {
    val colors = tallyColors
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("·", style = TallyType.helper, color = colors.ink3)
        Text(text, style = TallyType.helper, color = colors.ink3)
    }
}

@Composable
private fun WebDavSection(
    config: WebDavConfig,
    hasPassword: Boolean,
    busy: Boolean,
    remoteFiles: List<WebDavFile>,
    remoteLoaded: Boolean,
    onConfigure: () -> Unit,
    onRefresh: () -> Unit,
    onUpload: () -> Unit,
    onDownload: (WebDavFile) -> Unit,
) {
    val colors = tallyColors
    TallyCard(corner = 15, contentPadding = 4.dp) {
        Column {
            TallyListRow(
                title = config.baseUrl.ifBlank { "未配置 WebDAV" },
                subtitle = if (config.isConfigured) {
                    "${config.userName} · ${config.remoteDirectory}${if (hasPassword) " · 密码已保存" else " · 缺少密码"}"
                } else {
                    "坚果云 / Nextcloud 等标准 WebDAV · 手动快照"
                },
                iconKey = "cloud",
                onClick = onConfigure,
                trailing = { TallyRowChevron() },
            )
            if (config.isConfigured) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    TallyButton(
                        label = if (busy) "处理中…" else "刷新列表",
                        onClick = onRefresh,
                        variant = ButtonVariant.GHOST,
                        height = 32,
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                    )
                    TallyButton(
                        label = "上传快照",
                        onClick = onUpload,
                        variant = ButtonVariant.BRAND,
                        height = 32,
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (remoteLoaded && remoteFiles.isEmpty()) {
                    Text("远端目录暂无备份包", style = TallyType.helper, color = colors.ink3, modifier = Modifier.padding(10.dp))
                }
                remoteFiles.take(6).forEach { file ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { onDownload(file) }.padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(file.name, style = TallyType.helper, color = colors.ink, modifier = Modifier.weight(1f), maxLines = 1)
                        Text(
                            "${file.sizeText} · ${formatBackupTime(file.lastModified)}",
                            style = TallyType.helper,
                            color = colors.ink3,
                        )
                        Spacer(Modifier.width(7.dp))
                        InlineIcon(iconKey = "arrow_down", contentDescription = "下载", size = 14.dp, tint = colors.ink3)
                    }
                }
            }
        }
    }
}

@Composable
private fun RestoreDialog(
    manifest: BackupManifest,
    busy: Boolean,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var password by remember { mutableStateOf("") }
    val colors = tallyColors
    TallyConfirmDialog(
        title = "恢复备份",
        message = "备份时间 ${formatBackupTime(manifest.createdAt)} · ${manifest.billCount} 笔账单 · " +
            if (manifest.includesImages) "包含图片" else "不含图片",
        confirmLabel = if (busy) "正在恢复…" else "覆盖恢复",
        destructive = true,
        onConfirm = { if (password.isNotEmpty()) onConfirm(password) },
        onDismiss = onDismiss,
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(
                    "恢复为覆盖式：当前账本、账单、分类与设置会被备份内容替换。",
                    style = TallyType.helper,
                    color = colors.ink3,
                )
                TallyDialogField(
                    value = password,
                    onValueChange = { password = it },
                    label = "备份密码",
                    password = true,
                )
                if (!manifest.includesImages && manifest.attachmentCount > 0) {
                    Text(
                        "这份备份不含图片：恢复后图片位置显示「图片未包含在备份中」，可重新挂图。",
                        style = TallyType.helper,
                        color = colors.onGlow,
                    )
                }
            }
        },
    )
}

/** 导出前唯一一次问「要不要带图片」：勾选框放在这里，页面中间不再有独立模块。 */
@Composable
private fun ExportPasswordDialog(
    busy: Boolean,
    includeImages: Boolean,
    estimateBytes: Long,
    onToggleImages: (Boolean) -> Unit,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var password by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    val colors = tallyColors
    TallyConfirmDialog(
        title = "导出本地数据库备份",
        message = "备份包包含账本、账单、分类、周期、分期与设置。",
        confirmLabel = if (busy) "正在打包…" else "导出",
        onConfirm = {
            if (password.length >= BackupContainer.MIN_PASSWORD_CHARS && password == repeat) onConfirm(password)
        },
        onDismiss = onDismiss,
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(11.dp))
                        .background(if (includeImages) colors.brandSurface else colors.card)
                        .border(1.dp, if (includeImages) colors.brand.copy(alpha = 0.4f) else colors.line, RoundedCornerShape(11.dp))
                        .clickable { onToggleImages(!includeImages) }
                        .padding(start = 4.dp, end = 10.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = includeImages,
                        onCheckedChange = onToggleImages,
                        colors = CheckboxDefaults.colors(checkedColor = colors.brand, uncheckedColor = colors.ink3),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text("导出图片", style = TallyType.listTitle, color = colors.ink)
                        Text("预估 ${formatBytes(estimateBytes)} · 勾选后包体积明显增大", style = TallyType.helper, color = colors.ink3)
                    }
                }
                TallyDialogField(
                    value = password,
                    onValueChange = { password = it },
                    label = "设置密码",
                    password = true,
                )
                TallyDialogField(
                    value = repeat,
                    onValueChange = { repeat = it },
                    label = "再次输入",
                    password = true,
                )
                PasswordRuleNote(password)
                if (password.isNotEmpty() && repeat.isNotEmpty() && password != repeat) {
                    Text("两次输入不一致", style = TallyType.helper, color = colors.expense)
                }
            }
        },
    )
}

@Composable
private fun PasswordDialog(
    title: String,
    message: String,
    confirmLabel: String,
    busy: Boolean,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var password by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    val colors = tallyColors
    TallyConfirmDialog(
        title = title,
        message = message,
        confirmLabel = if (busy) "处理中…" else confirmLabel,
        onConfirm = {
            if (password.length >= BackupContainer.MIN_PASSWORD_CHARS && password == repeat) onConfirm(password)
        },
        onDismiss = onDismiss,
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                TallyDialogField(
                    value = password,
                    onValueChange = { password = it },
                    label = "设置密码",
                    password = true,
                )
                TallyDialogField(
                    value = repeat,
                    onValueChange = { repeat = it },
                    label = "再次输入",
                    password = true,
                )
                PasswordRuleNote(password)
                if (password.isNotEmpty() && repeat.isNotEmpty() && password != repeat) {
                    Text("两次输入不一致", style = TallyType.helper, color = colors.expense)
                }
            }
        },
    )
}

/** 备份口令的下限：说清规则，也数出还差几位——不给确认又不说为什么，只会让人反复按。 */
@Composable
private fun PasswordRuleNote(password: String) {
    val colors = tallyColors
    val short = password.length < BackupContainer.MIN_PASSWORD_CHARS
    Text(
        text = when {
            password.isEmpty() -> "至少 ${BackupContainer.MIN_PASSWORD_CHARS} 位，字母与数字都可以"
            short -> "还差 ${BackupContainer.MIN_PASSWORD_CHARS - password.length} 位"
            else -> "字母与数字都可以，恢复时要用同一串"
        },
        style = TallyType.helper,
        color = if (password.isNotEmpty() && short) colors.expense else colors.ink3,
    )
}

@Composable
private fun WebDavConfigDialog(
    initial: WebDavConfig,
    hasPassword: Boolean,
    busy: Boolean,
    onSave: (WebDavConfig, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var url by remember { mutableStateOf(initial.baseUrl) }
    var user by remember { mutableStateOf(initial.userName) }
    var directory by remember { mutableStateOf(initial.remoteDirectory) }
    var password by remember { mutableStateOf("") }
    val colors = tallyColors
    TallyConfirmDialog(
        title = "WebDAV 配置",
        message = "填写后先测试连接，成功才会保存。密码只存在本机 Keystore，不进备份包。",
        confirmLabel = if (busy) "测试中…" else "测试并保存",
        onConfirm = {
            onSave(
                WebDavConfig(baseUrl = url, userName = user, remoteDirectory = directory.ifBlank { "tally" }),
                password,
            )
        },
        onDismiss = onDismiss,
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                TallyDialogField(value = url, onValueChange = { url = it }, label = "服务器地址")
                TallyDialogField(value = user, onValueChange = { user = it }, label = "用户名")
                TallyDialogField(
                    value = password,
                    onValueChange = { password = it },
                    label = if (hasPassword) "密码（留空沿用已保存）" else "密码",
                    password = true,
                )
                TallyDialogField(value = directory, onValueChange = { directory = it }, label = "远端目录")
                Text("密码保存在 Android Keystore，不写数据库、不进日志。", style = TallyType.helper, color = colors.ink3)
            }
        },
    )
}

/** 成功之后必须说清文件在哪：路径逐字印出来，不让人去猜存储目录结构。 */
@Composable
private fun ExportReadyDialog(
    saved: SavedBackup,
    onShare: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = tallyColors
    TallyConfirmDialog(
        title = "备份已生成",
        message = "${saved.fileName}\n${formatBytes(saved.sizeBytes)} · ${saved.billCount} 笔账单 · " +
            if (saved.includesImages) "包含图片" else "不含图片",
        confirmLabel = "分享",
        dismissLabel = "知道了",
        onConfirm = onShare,
        onDismiss = onDismiss,
        content = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(11.dp))
                    .background(colors.card)
                    .border(1.dp, colors.line, RoundedCornerShape(11.dp))
                    .padding(horizontal = 11.dp, vertical = 9.dp),
            ) {
                Text("保存在本机", style = TallyType.helper, color = colors.ink3, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(3.dp))
                Text(
                    saved.absolutePath,
                    style = TallyType.helper.copy(fontSize = 11.sp, lineHeight = 17.sp),
                    color = colors.ink,
                    modifier = Modifier.widthIn(max = 320.dp),
                )
            }
        },
    )
}

internal fun formatBackupTime(epochMillis: Long): String =
    if (epochMillis <= 0L) "—"
    else Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).format(BackupService.DATE_FORMAT)

internal fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "%.2f GB".format(bytes / 1024.0 / 1024.0 / 1024.0)
    bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    bytes >= 1024L -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
