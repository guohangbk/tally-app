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
    private val now = 1_794_600_000_000L

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

        // 已解决的行在写盘时就被丢掉，文件里只剩还能动的那条；入口与卡都只认它。
        val reopened = PendingIngestStore(root)
        assertEquals(1, reopened.load().entries.size)
        assertEquals(1, reopened.count.value)
    }

    /**
     * 存一批空的不等于清清单：文件里还没确认的行是「还没处理」、不是「可以丢」，
     * 合并写盘要把它们留下，只有 [PendingIngestStore.clear] 才收走。
     */
    @Test
    fun `存一批空的保留已有未确认行`() = runBlocking {
        val store = PendingIngestStore(root)
        store.save("VOICE", 0, emptyList(), listOf(PendingIngestEntry(0, 0, row = row())))
        assertTrue(file.isFile)

        store.save("VOICE", 0, emptyList(), emptyList())
        assertTrue(file.isFile)
        assertEquals(1, store.load().entries.size)
        assertEquals(1, store.count.value)
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

    /** 通知是追加，不是整批替换：识别批次里还没确认的行必须留在清单里。 */
    @Test
    fun `通知追加不覆盖识别批次里没确认的行`() = runBlocking {
        val store = PendingIngestStore(root)
        store.save(
            source = "SCREENSHOT",
            droppedCount = 0,
            failures = emptyList(),
            entries = listOf(
                PendingIngestEntry(0, 0, source = "SCREENSHOT", createdAt = now, row = row()),
            ),
        )
        store.append(
            listOf(
                PendingIngestEntry(
                    key = 1,
                    imageIndex = 0,
                    source = "NOTIFICATION",
                    createdAt = now,
                    orderNo = "X1",
                    auto = true,
                    row = row(),
                ),
            ),
        )

        val batch = store.load()
        assertEquals("SCREENSHOT", batch.source)
        assertEquals(2, batch.entries.size)
        assertEquals("SCREENSHOT", batch.entries.first().source)
        assertEquals("NOTIFICATION", batch.entries.last().source)
        assertTrue(batch.entries.last().auto)
        assertEquals("X1", batch.entries.last().orderNo)
    }

    /** 自动草稿 30 天没处理就过期：只清自动行，手动识别行不受它影响。 */
    @Test
    fun `自动草稿三十天过期只清自动行`() = runBlocking {
        val store = PendingIngestStore(root, now = { now })
        store.save(
            source = "NOTIFICATION",
            droppedCount = 0,
            failures = emptyList(),
            entries = listOf(
                PendingIngestEntry(0, 0, source = "NOTIFICATION", createdAt = now - 31L * 24 * 60 * 60 * 1000, auto = true, row = row()),
                PendingIngestEntry(1, 0, source = "NOTIFICATION", createdAt = now - 31L * 24 * 60 * 60 * 1000, auto = false, row = row()),
            ),
        )

        val batch = store.load()
        assertEquals(1, batch.entries.size)
        assertEquals(1, batch.entries.first().key)
        assertEquals(1, store.count.value)
    }

    /** 人点某一行的「×」：那一行真的从文件里消失，摘空了连文件一起收掉。 */
    @Test
    fun `移除单行会摘掉落盘记录`() = runBlocking {
        val store = PendingIngestStore(root, now = { now })
        store.save(
            source = "NOTIFICATION",
            droppedCount = 0,
            failures = emptyList(),
            entries = listOf(
                PendingIngestEntry(key = 1, imageIndex = 0, source = "NOTIFICATION", createdAt = now, auto = true, row = row()),
                PendingIngestEntry(key = 2, imageIndex = 0, source = "NOTIFICATION", createdAt = now, auto = true, row = row()),
            ),
        )

        store.removeKeys(setOf(1))
        val after = PendingIngestStore(root, now = { now })
        assertEquals(listOf(2), after.load().entries.map { it.key })
        assertEquals(1, after.count.value)

        store.removeKeys(setOf(2))
        assertFalse(file.exists())
        assertEquals(0, store.count.value)
    }

    /** 从确认卡跳记账页保存后，这一笔必须精确从落盘清单里摘掉。 */
    @Test
    fun `已保存的行会被精确移除`() = runBlocking {
        val store = PendingIngestStore(root, now = { now })
        val first = PendingIngestEntry(key = 1, imageIndex = 0, source = "SCREENSHOT", createdAt = now, row = row())
        val second = PendingIngestEntry(key = 2, imageIndex = 1, source = "SCREENSHOT", createdAt = now, row = row())
        store.save("SCREENSHOT", 0, emptyList(), listOf(first, second))

        store.removeKeys(setOf(1))

        val reopened = PendingIngestStore(root, now = { now })
        assertEquals(listOf(2), reopened.load().entries.map { it.key })
        assertEquals(1, reopened.count.value)
    }
}
