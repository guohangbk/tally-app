package io.github.guohangbk.tally.data.ingest

import io.github.guohangbk.tally.domain.model.BillType
import io.github.guohangbk.tally.domain.model.DraftRow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 多图待确认清单的落盘契约：**杀进程之后还接得上**，全靠这一层。
 *
 * 所以要盯住三件事：往返不丢字段、写入是「先写临时文件再改名」、首页那枚入口只数还能动的行。
 * 这里用真实的临时目录跑，不 mock 文件系统——这条链路出错就是用户那批账没了，值得用真 IO 守。
 */
class PendingIngestStoreTest {

    private val root: File = Files.createTempDirectory("pending-ingest").toFile()
    private val file = File(root, "ingest_pending/v1.json")

    @AfterEach
    fun cleanUp() {
        root.deleteRecursively()
    }

    private fun row(duplicate: Boolean = false, saved: Boolean = false) = DraftRow(
        amountMinor = 1285,
        type = BillType.EXPENSE.name,
        occurredAt = 1_794_600_000_000,
        source = "SCREENSHOT",
        categoryL1Id = "seed-expense-daily",
        categoryLabel = "三餐",
        note = "午饭",
        merchant = "盒马",
        externalId = "20260101001",
        duplicate = duplicate,
        saved = saved,
    )

    @Test
    fun `落盘再读回：字段一个不丢`() = runBlocking {
        PendingIngestStore(root).save(
            source = "SCREENSHOT",
            droppedCount = 2,
            failures = listOf(PendingFailure(imageIndex = 3, reason = "这张图片读不出来")),
            entries = listOf(PendingIngestEntry(key = 7, imageIndex = 1, checked = false, row = row())),
        )

        // 换一个实例读，等于杀进程重开：只认文件，不认内存。
        val reopened = PendingIngestStore(root)
        val batch = reopened.load()
        assertEquals("SCREENSHOT", batch.source)
        assertEquals(2, batch.droppedCount)
        assertEquals(listOf(PendingFailure(3, "这张图片读不出来")), batch.failures)
        assertEquals(1, batch.entries.size)
        assertEquals(7, batch.entries.first().key)
        assertEquals(1, batch.entries.first().imageIndex)
        assertFalse(batch.entries.first().checked)
        assertEquals(row(), batch.entries.first().row)
        assertEquals(1, reopened.count.value)
    }

    /** 写到一半被杀不能留下半个 JSON：目录里只该有正式文件，没有 `.tmp`。 */
    @Test
    fun `写过一轮之后不残留临时文件`() = runBlocking {
        val store = PendingIngestStore(root)
        store.save("VOICE", 0, emptyList(), listOf(PendingIngestEntry(0, 0, row = row())))
        store.save("VOICE", 0, emptyList(), listOf(PendingIngestEntry(0, 0, row = row())))

        assertTrue(file.isFile)
        assertEquals(listOf("v1.json"), file.parentFile?.list()?.sorted())
    }

    /** 已经记过、或已经撤过的行点不了，也不该让首页那枚「还有 N 笔」一直挂着。 */
    @Test
    fun `入口只数还能动的行`() = runBlocking {
        PendingIngestStore(root).save(
            source = "VOICE",
            droppedCount = 0,
            failures = emptyList(),
            entries = listOf(
                PendingIngestEntry(0, 0, row = row(duplicate = true)),
                PendingIngestEntry(1, 1, row = row(duplicate = true, saved = true)),
                PendingIngestEntry(2, 2, row = row()),
            ),
        )

        val reopened = PendingIngestStore(root)
        assertEquals(3, reopened.load().entries.size)
        assertEquals(1, reopened.count.value)
    }

    @Test
    fun `存一批空的等于清掉清单`() = runBlocking {
        val store = PendingIngestStore(root)
        store.save("VOICE", 0, emptyList(), listOf(PendingIngestEntry(0, 0, row = row())))
        assertTrue(file.isFile)

        store.save("VOICE", 0, emptyList(), emptyList())
        assertFalse(file.exists())
        assertEquals(0, store.count.value)
    }

    /** 文件格式改过就整批丢掉：宁可让人重识别一次，也不能把结构不对的行摆上卡让人确认。 */
    @Test
    fun `版本对不上按没有处理`() = runBlocking {
        file.parentFile?.mkdirs()
        file.writeText(
            """{"version":99,"savedAt":0,"source":"VOICE","droppedCount":0,"failures":[],""" +
                """"entries":[{"key":0,"imageIndex":0,"checked":true,"row":{"amountMinor":1,""" +
                """"type":"EXPENSE","occurredAt":1,"source":"VOICE"}}]}""",
        )

        val store = PendingIngestStore(root)
        assertEquals(emptyList(), store.load().entries)
        assertEquals(0, store.count.value)
    }

    @Test
    fun `文件读不出来时不炸页面`() = runBlocking {
        file.parentFile?.mkdirs()
        file.writeText("{ 这不是 JSON")

        val store = PendingIngestStore(root)
        assertEquals(emptyList(), store.load().entries)
        assertEquals(0, store.count.value)
    }
}
