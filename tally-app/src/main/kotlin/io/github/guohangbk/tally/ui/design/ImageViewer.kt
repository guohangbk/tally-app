package io.github.guohangbk.tally.ui.design

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.guohangbk.tally.di.rememberAppGraph

/**
 * 账单附件 / 对话图片共用的看图组件：[ImageThumbnail] 缩略图 + [FullScreenImageViewer] 大图层。
 * 两者都按目标边长采样解码——缩略图小、大图 1600px，不把整张原图塞进内存。
 */

/** 按目标边长采样解码，避免把一张几 M 的原图整个解码进内存。 */
private fun decodeSampled(path: String, targetPx: Int): android.graphics.Bitmap? = runCatching {
    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= targetPx || bounds.outHeight / (sample * 2) >= targetPx) sample *= 2
    android.graphics.BitmapFactory.decodeFile(
        path,
        android.graphics.BitmapFactory.Options().apply { inSampleSize = sample },
    )
}.getOrNull()

@Composable
private fun rememberAttachmentBitmap(relativePath: String, targetPx: Int): android.graphics.Bitmap? {
    val graph = rememberAppGraph()
    return remember(relativePath, targetPx) {
        graph.attachmentStore.fileAt(relativePath)?.let { decodeSampled(it.absolutePath, targetPx) }
    }
}

/** 一张可点开放大的方形缩略图；文件已不在本机时留一个占位，不崩。 */
@Composable
fun ImageThumbnail(
    relativePath: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Int = 44,
) {
    val colors = tallyColors
    val bitmap = rememberAttachmentBitmap(relativePath, size * 3)
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = modifier
            .size(size.dp)
            .clip(shape)
            .background(colors.card)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "图片",
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size.dp).clip(shape),
            )
        } else {
            InlineIcon(iconKey = "image", contentDescription = "图片已不在本机", size = 15.dp, tint = colors.ink3)
        }
    }
}

/** 全屏看大图：多图可左右滑，点任意处关闭。[startIndex] 决定先落在哪一张。 */
@Composable
fun FullScreenImageViewer(
    relativePaths: List<String>,
    startIndex: Int,
    onDismiss: () -> Unit,
) {
    if (relativePaths.isEmpty()) return
    val pagerState = rememberPagerState(initialPage = startIndex.coerceIn(0, relativePaths.lastIndex)) { relativePaths.size }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.96f))
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                ViewerPage(relativePath = relativePaths[page])
            }
            if (relativePaths.size > 1) {
                Text(
                    text = "${pagerState.currentPage + 1} / ${relativePaths.size}",
                    style = TallyType.helper,
                    color = Color.White.copy(alpha = 0.82f),
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 26.dp),
                )
            }
        }
    }
}

@Composable
private fun ViewerPage(relativePath: String) {
    val full = rememberAttachmentBitmap(relativePath, 1600)
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (full != null) {
            Image(
                bitmap = full.asImageBitmap(),
                contentDescription = "大图",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(8.dp),
            )
        } else {
            Text("这张图已经不在本机了", style = TallyType.helper, color = tallyColors.paper)
        }
    }
}
