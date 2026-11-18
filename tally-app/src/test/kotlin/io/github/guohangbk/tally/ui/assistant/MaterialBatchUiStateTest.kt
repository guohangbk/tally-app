package io.github.guohangbk.tally.ui.assistant

import io.github.guohangbk.tally.domain.model.BillType
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 确认卡的收尾判据。
 *
 * 这里盯的是一个真机撞见过的坑：**全部存入之后列表恰好空了**，此时 [MaterialBatchUiState.done]
 * 反而是假（它要求「有几行或几条失败」才算跑完），卡就永远留在屏幕上。
 * 收卡只看 [MaterialBatchUiState.settled]，所以那条路径必须在这里写死。
 */
class MaterialBatchUiStateTest {

    private fun row() = ScannedBill(
        key = 0,
        imageIndex = 0,
        amountMinor = 1200,
        type = BillType.EXPENSE,
        occurredAt = 1_794_600_000_000,
        categoryL1Id = "seed-expense-daily",
        categoryL2Id = null,
        categoryLabel = "三餐",
        merchant = null,
        grade = "HIGH",
    )

    @Test
    fun `全部存入后列表空掉：卡要收`() {
        val state = MaterialBatchUiState(busy = false, bills = emptyList(), failures = emptyList())
        assertFalse(state.done)
        assertTrue(state.settled)
    }

    @Test
    fun `还有待确认的行：卡留着`() {
        val state = MaterialBatchUiState(bills = listOf(row()), failures = emptyList())
        assertTrue(state.done)
        assertFalse(state.settled)
    }

    /** 「已经记过」只走气泡、不进列表；有没识别到的图也照样收，不必把空表钉在屏幕上。 */
    @Test
    fun `只剩已记过的笔加失败项：卡收掉`() {
        val state = MaterialBatchUiState(
            bills = emptyList(),
            failures = listOf(ScanFailure(imageIndex = 1, reason = "这张图片读不出来")),
            duplicates = 1,
        )
        assertTrue(state.done)
        assertTrue(state.settled)
    }

    /** 全是「已经记过」的：算跑完（好让气泡弹一次），但卡上没有可处理的行。 */
    @Test
    fun `全是已记过的笔：只弹气泡不摆卡`() {
        val state = MaterialBatchUiState(bills = emptyList(), duplicates = 2)
        assertTrue(state.done)
        assertTrue(state.settled)
    }

    @Test
    fun `识别中途：既不算跑完也不算收尾`() {
        val state = MaterialBatchUiState(busy = true, imageTotal = 3, imageDone = 1)
        assertFalse(state.done)
        assertFalse(state.settled)
    }
}
