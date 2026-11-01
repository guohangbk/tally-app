package io.github.guohangbk.tally.ui.assistant

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.guohangbk.tally.data.assistant.ClipboardImages
import io.github.guohangbk.tally.di.rememberAppGraph
import io.github.guohangbk.tally.domain.model.BillType
import io.github.guohangbk.tally.ui.common.CategoryPickerSheet
import io.github.guohangbk.tally.ui.common.formatDateTime
import io.github.guohangbk.tally.ui.common.tallyViewModel
import io.github.guohangbk.tally.ui.design.ButtonVariant
import io.github.guohangbk.tally.ui.design.InlineIcon
import io.github.guohangbk.tally.ui.design.StatusDot
import io.github.guohangbk.tally.ui.design.TagTone
import io.github.guohangbk.tally.ui.design.TallyButton
import io.github.guohangbk.tally.ui.design.TallyCard
import io.github.guohangbk.tally.ui.design.TallySectionLabel
import io.github.guohangbk.tally.ui.design.TallyTag
import io.github.guohangbk.tally.ui.design.TallyType
import io.github.guohangbk.tally.ui.design.TallyToastEffects
import io.github.guohangbk.tally.ui.design.tallyColors
import io.github.guohangbk.tally.ui.shell.TallyTopBar

/**
 * P16 截图记账草稿。识别链路、是否发图、是否留图，全部在这一屏上写明白；
 * 「保存到账单」复选框常驻且默认不勾选，不受设置里的「账单图片」开关影响。
 */
@Composable
fun ScreenshotScreen(onBack: () -> Unit, onSaved: () -> Unit) {
    val graph = rememberAppGraph()
    val viewModel = tallyViewModel(key = "screenshot") { ScreenshotViewModel(graph) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = tallyColors
    val context = LocalContext.current

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let(viewModel::load)
    }

    // 分享与剪贴板都汇入这条流：Activity 只负责把 uri 放进依赖图，导航层不认识它。
    LaunchedEffect(graph) {
        graph.sharedScreenshot.collect { uri ->
            if (uri != null) {
                graph.sharedScreenshot.value = null
                viewModel.load(uri)
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(colors.paper)) {
        TallyTopBar(
            title = "截图记账",
            onBack = onBack,
            subtitle = if (state.imageLoaded) "已在本机识别" else "相册 · 分享 · 剪贴板",
            trailing = {
                TallyTag(
                    text = "端侧 OCR",
                    tone = TagTone.NEUTRAL,
                )
            },
        )

        // 提示改成贴底气泡，保留「这一步没有完成」这个定性说法。
        TallyToastEffects(
            notice = state.notice,
            error = state.error,
            errorTitle = "这一步没有完成",
            onNoticeShown = viewModel::dismissMessages,
            onErrorShown = viewModel::dismissMessages,
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(start = 15.dp, end = 15.dp, top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp), modifier = Modifier.height(IntrinsicSize.Min)) {
                Thumb(uri = state.source, modifier = Modifier.width(112.dp))
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    PipelineLine("端侧 OCR ${if (state.ocrText != null) "已识别" else "待运行"}", colors.ink3, done = state.ocrText != null)
                    PipelineLine("规则抽取 · 图片不出设备", colors.ink3, done = true)
                    PipelineLine("只读你选的这张图", colors.ink3, done = true)
                    if (!state.imageLoaded) {
                        TallyButton(
                            label = "从相册选截图",
                            onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                            variant = ButtonVariant.PRIMARY,
                            height = 38,
                            leadingIcon = "image",
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            TallyButton(
                                label = "读剪贴板",
                                onClick = {
                                    val uri = ClipboardImages.imageUri(context)
                                    if (uri != null) viewModel.load(uri) else viewModel.missingClipboardImage()
                                },
                                variant = ButtonVariant.GHOST,
                                height = 34,
                            )
                        }
                    }
                }
            }

            state.stage?.let { stage ->
                Text(stage, style = TallyType.helper, color = colors.ink3)
            }

            state.duplicateHint?.let { hint ->
                Text(hint, style = TallyType.helper, color = colors.expense)
            }

            if (state.hasDraft) {
                TallySectionLabel("识别结果 · 可任意修改")
                AiCard(glow = false) {
                    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        AiField(label = "金额", value = state.amountInput, onValueChange = viewModel::onAmountChange, placeholder = "未识别到金额", keyboardType = KeyboardType.Decimal)
                        AiField(label = "商户", value = state.merchant, onValueChange = viewModel::onMerchantChange, placeholder = "未识别到商户")

                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("分类建议", style = TallyType.helper, color = colors.ink3)
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(
                                        if (state.categoryLabel == null) colors.expenseSurface else colors.brandSurface,
                                    )
                                    .clickable(onClick = viewModel::openPicker)
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                            ) {
                                Text(
                                    text = state.categoryLabel ?: "选一个分类",
                                    style = TallyType.helper,
                                    color = if (state.categoryLabel == null) colors.expense else colors.brand,
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                            Spacer(Modifier.weight(1f))
                            Text(
                                text = state.fields?.let { f ->
                                    listOfNotNull(
                                        f.occurredAt?.let { formatDateTime(it) },
                                        f.paymentMethod,
                                    ).joinToString(" · ")
                                }.orEmpty(),
                                style = TallyType.helper,
                                color = colors.ink3,
                            )
                        }

                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            Text("收支", style = TallyType.helper, color = colors.ink3)
                            TypeChoice(selected = state.type.name) { viewModel.setType(if (it) BillType.INCOME else BillType.EXPENSE) }
                        }

                        AiField(label = "备注", value = state.noteInput, onValueChange = viewModel::onNoteChange, placeholder = "想补一句就写")

                        if (state.missingFields.isNotEmpty()) {
                            Text("以下字段需你确认：" + state.missingFields.joinToString("、") { it.label }, style = TallyType.helper, color = colors.expense)
                        }
                    }
                }

                KeepImageCard(checked = state.keepImage, onCheckedChange = { viewModel.toggleKeepImage() })

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TallyButton(
                        label = "重新识别",
                        onClick = { state.source?.let(viewModel::load) },
                        variant = ButtonVariant.GHOST,
                        height = 44,
                        enabled = state.imageLoaded && !state.busy,
                        modifier = Modifier.weight(1f),
                    )
                    TallyButton(
                        label = "确认入账",
                        onClick = { viewModel.save(onSaved) },
                        variant = ButtonVariant.PRIMARY,
                        height = 44,
                        enabled = state.canSave,
                        modifier = Modifier.weight(1f),
                    )
                }

                state.ocrText?.takeIf { it.isNotBlank() }?.let { text ->
                    TallyCard(corner = 14, contentPadding = 11.dp) {
                        Column {
                            AiEyebrow(text = "OCR 原文", trailing = "只在设备内")
                            Spacer(Modifier.height(5.dp))
                            Text(
                                text = text,
                                style = TallyType.helper,
                                color = colors.ink2,
                                maxLines = 8,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            } else if (state.loaded && !state.imageLoaded) {
                TallyCard(corner = 14, contentPadding = 13.dp) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("截图从哪来", style = TallyType.sectionTitle, color = colors.ink)
                        Text("相册选一张、系统分享里选「塔莉」、或者复制图片后进来点剪贴板。三种入口都在本机识别，不联网。", style = TallyType.helper, color = colors.ink2)
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    if (state.showPicker) {
        CategoryPickerSheet(
            categories = state.pickerCategories,
            selectedL1 = state.categoryL1Id,
            selectedL2 = state.categoryL2Id,
            onConfirm = { l1, l2 -> viewModel.pickCategory(l1, l2) },
            onDismiss = viewModel::closePicker,
        )
    }
}

@Composable
private fun Thumb(uri: Uri?, modifier: Modifier = Modifier) {
    val colors = tallyColors
    val context = LocalContext.current
    val image by produceState<ImageBitmap?>(initialValue = null, key1 = uri) {
        value = uri?.let { target ->
            runCatching {
                context.contentResolver.openInputStream(target)?.use { stream ->
                    val bytes = stream.readBytes()
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                }
            }.getOrNull()
        }
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(colors.card)
            .border(1.dp, colors.line, RoundedCornerShape(14.dp)),
        contentAlignment = Alignment.Center,
    ) {
        val bitmap = image
        if (bitmap == null) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                InlineIcon("image", contentDescription = null, size = 22.dp, tint = colors.ink3)
                Spacer(Modifier.height(4.dp))
                Text("未选择", style = TallyType.helper, color = colors.ink3)
            }
        } else {
            Image(
                bitmap = bitmap,
                contentDescription = "截图预览",
                modifier = Modifier.fillMaxWidth().heightIn(min = 84.dp, max = 148.dp),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

@Composable
private fun PipelineLine(label: String, color: Color, done: Boolean) {
    val colors = tallyColors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        StatusDot(color = if (done) colors.brand else colors.ink3, halo = colors.line, size = 4.dp)
        Text(label, style = TallyType.helper, color = color)
    }
}

@Composable
private fun KeepImageCard(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    AiCheckRow(
        checked = checked,
        onCheckedChange = onCheckedChange,
        text = "保存到账单（本次截图）· 默认不勾选。勾选后截图作为附件压缩保存（WebP ≤2 MB）；不勾选只留解析结果，原图仍在系统相册。",
    )
}

@Composable
private fun TypeChoice(selected: String, onPick: (Boolean) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ChooseChip(label = "支出", selected = selected == "EXPENSE") { onPick(false) }
        ChooseChip(label = "收入", selected = selected == "INCOME") { onPick(true) }
    }
}

@Composable
private fun ChooseChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = tallyColors
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(if (selected) colors.ink else colors.card)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(label, style = TallyType.helper, color = if (selected) colors.paper else colors.ink3)
    }
}


