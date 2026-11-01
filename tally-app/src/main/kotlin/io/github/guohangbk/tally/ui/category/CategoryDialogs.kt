package io.github.guohangbk.tally.ui.category

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.guohangbk.tally.domain.model.Category
import io.github.guohangbk.tally.ui.design.TallyConfirmDialog
import io.github.guohangbk.tally.ui.design.TallyType
import io.github.guohangbk.tally.ui.design.tallyColors


/** 有账单引用时，去向必须由用户显式选定；未选定不下发给仓储。 */
@Composable
internal fun CategoryDeleteDialog(
    category: Category,
    billCount: Int,
    childCount: Int,
    targets: List<Category>,
    error: String?,
    onDismiss: () -> Unit,
    onConfirm: (CategoryDeleteTarget) -> Unit,
) {
    val colors = tallyColors
    val requiresChoice = billCount > 0
    var destination by remember(category.id) {
        mutableStateOf<CategoryDeleteTarget?>(if (requiresChoice) null else CategoryDeleteTarget.Unclassified)
    }
    val message = when {
        requiresChoice && childCount > 0 ->
            "该分类下有 $childCount 个二级分类，全部账本共 $billCount 笔账单在用；删除后二级一并删除，账单必须选择去向。"

        requiresChoice -> "全部账本共 $billCount 笔账单正在使用它，请选择迁移目标。"
        childCount > 0 -> "其下 $childCount 个二级分类会一起删除，但没有账单引用。"
        else -> "没有账单引用它，删除后不可恢复。"
    }
    TallyConfirmDialog(
        title = "删除「${category.name}」",
        message = message,
        confirmLabel = "删除",
        destructive = true,
        onConfirm = { onConfirm(destination ?: CategoryDeleteTarget.Pending) },
        onDismiss = onDismiss,
        content = {
            if (requiresChoice) {
                Text("账单去向", style = TallyType.helper, color = colors.ink3)
                Spacer(Modifier.height(6.dp))
                // 分类多的账本这一列能到几十行，必须限高可滚；「不迁移」固定在列表外面，
                // 否则它会被挤到屏幕之外，用户只剩「随便挑一个分类」这一条路。
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 252.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    targets.forEach { target ->
                        DestinationRow(
                            title = target.name,
                            selected = (destination as? CategoryDeleteTarget.Migrate)?.targetId == target.id,
                            onClick = { destination = CategoryDeleteTarget.Migrate(target.id) },
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                DestinationRow(
                    title = "不迁移 · 标记为未分类",
                    selected = destination is CategoryDeleteTarget.Unclassified,
                    onClick = { destination = CategoryDeleteTarget.Unclassified },
                )
                Spacer(Modifier.height(8.dp))
            }
            if (error != null) {
                Text(error, style = TallyType.helper, color = colors.expense)
                Spacer(Modifier.height(4.dp))
            }
            Text(
                text = if (requiresChoice) "必须选定一个去向才能删除，避免产生无主账单。" else "该分类没有账单引用，可直接删除。",
                style = TallyType.helper,
                color = colors.ink3,
            )
        },
    )
}

@Composable
private fun DestinationRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = tallyColors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) colors.brandSurface else colors.paper)
            .border(1.dp, if (selected) colors.brand else colors.line, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(16.dp)
                .border(1.dp, if (selected) colors.brand else colors.line, RoundedCornerShape(5.dp))
                .background(if (selected) colors.brand else colors.card, RoundedCornerShape(5.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Text("✓", fontSize = 10.sp, lineHeight = 12.5.sp, color = colors.onBrand)
        }
        Spacer(Modifier.width(9.dp))
        Text(
            text = title,
            style = TallyType.listTitle,
            color = colors.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

