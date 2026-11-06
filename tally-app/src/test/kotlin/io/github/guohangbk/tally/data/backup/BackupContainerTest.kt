package io.github.guohangbk.tally.data.backup

import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 加密备份包：清单免密可读、密文往返保真、错口令与篡改必须失败。 */
class BackupContainerTest {

    private val password = "test1234".toCharArray()

    private fun manifest() = BackupManifestJson(
        formatVersion = 1,
        createdAt = 1_700_000_000_000L,
        appVersion = "0.1.2-dev",
        billCount = 42,
        ledgerCount = 3,
        attachmentCount = 1,
        includesImages = true,
        payloadBytes = 1234L,
    )

    @Test
    fun `清单不需要口令`() {
        val bytes = BackupContainer.pack(manifest(), "{}", emptyMap(), password)
        assertEquals(manifest(), BackupContainer.readManifest(bytes))
    }

    @Test
    fun `结构化数据与图片往返保真`() {
        val json = """{"hello":"世界","n":1}"""
        val image = byteArrayOf(1, 2, 3, 0, -1, 42)
        val bytes = BackupContainer.pack(manifest(), json, mapOf("a.png" to image), password)
        val payload = BackupContainer.unpack(bytes, password)
        assertEquals(manifest(), payload.manifest)
        assertEquals(json, payload.json)
        assertContentEquals(image, payload.images["a.png"])
    }

    @Test
    fun `口令错误必须失败而不是吐出乱码`() {
        val bytes = BackupContainer.pack(manifest(), "{}", emptyMap(), password)
        assertFailsWith<Exception> { BackupContainer.unpack(bytes, "wrong".toCharArray()) }
    }

    @Test
    fun `密文被篡改必须被 GCM 拒绝`() {
        val bytes = BackupContainer.pack(manifest(), "{}", emptyMap(), password)
        // 精确改 data.enc 的密文部分（salt 16 + iv 12 之后），别改 zip 结构字节。
        val entries = readZip(bytes)
        val blob = entries.getValue("data.enc").clone()
        val at = 16 + 12 + 3
        blob[at] = (blob[at].toInt() xor 0x01).toByte()
        val tampered = writeZip(entries.toMutableMap().apply { put("data.enc", blob) }, entries.keys.toList())
        assertFailsWith<Exception> { BackupContainer.unpack(tampered, password) }
    }

    /** zip 读写只服务于篡改用例；生产侧用 ZipInputStream / ZipOutputStream，此处对称实现。 */
    private fun readZip(bytes: ByteArray): Map<String, ByteArray> {
        val result = linkedMapOf<String, ByteArray>()
        java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) result[entry.name] = zip.readBytes()
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return result
    }

    private fun writeZip(entries: Map<String, ByteArray>, order: List<String>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { zip ->
            order.forEach { name ->
                zip.putNextEntry(java.util.zip.ZipEntry(name))
                zip.write(entries.getValue(name))
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Test
    fun `附件路径按 mime 给扩展名`() {
        assertEquals("abc.png", BackupContainer.attachmentPath("abc", "image/png"))
        assertEquals("abc.jpg", BackupContainer.attachmentPath("abc", "image/jpeg"))
        assertEquals("abc.webp", BackupContainer.attachmentPath("abc", "image/webp"))
    }

    @Test
    fun `同一份明文两次加密不一样`() {
        val a = BackupContainer.encrypt("same".toByteArray(), password)
        val b = BackupContainer.encrypt("same".toByteArray(), password)
        assertFalse(a.contentEquals(b), "salt / iv 必须每次随机")
        assertContentEquals("same".toByteArray(), BackupContainer.decrypt(a, password))
    }

    @Test
    fun `口令长度下限存在`() {
        assertTrue(BackupContainer.MIN_PASSWORD_CHARS >= 4)
    }
}
