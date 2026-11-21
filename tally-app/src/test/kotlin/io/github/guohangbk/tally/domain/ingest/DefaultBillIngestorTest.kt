package io.github.guohangbk.tally.domain.ingest

import io.github.guohangbk.tally.domain.model.BillType
import io.github.guohangbk.tally.domain.model.TimePrecision
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DefaultBillIngestorTest {
    private val now = 1_700_000_000_000L

    private fun request(
        text: String = "昨天午饭 35.00",
        source: IngestSource = IngestSource.VOICE,
        ocrText: String = "",
        allowLlm: Boolean = true,
        images: List<ByteArray> = emptyList(),
    ) = IngestRequest(
        source = source,
        userText = text,
        ocrText = ocrText,
        images = images,
        nowMillis = now,
        fallbackMillis = now,
        allowLlm = allowLlm,
        redactedOcrText = IngestRedactor.redact(ocrText).text,
    )

    private fun ruleBill(
        amount: Long? = 3500L,
        category: String? = "food",
        date: Long? = now,
    ) = ExtractedBill(
        amountMinor = amount,
        type = BillType.EXPENSE,
        occurredAt = date,
        timePrecision = TimePrecision.DATE,
        categoryL1Id = category,
        categoryL2Id = null,
        note = "午饭",
        merchant = null,
        orderNo = null,
        origins = originMap(
            IngestKeys.AMOUNT to amount,
            IngestKeys.TYPE to BillType.EXPENSE,
            IngestKeys.DATE to date,
            IngestKeys.CATEGORY to category,
            IngestKeys.NOTE to "午饭",
            IngestKeys.MERCHANT to null,
            IngestKeys.ORDER to null,
        ),
    )

    private class StaticRules(private val bill: ExtractedBill?) : RuleExtractor {
        override fun extract(request: IngestRequest, text: String): RuleBill =
            bill?.let { RuleBill.Single(it) } ?: RuleBill.None
    }

    private class SequenceRules(private val outcomes: List<RuleBill>) : RuleExtractor {
        private var cursor = 0
        override fun extract(request: IngestRequest, text: String): RuleBill =
            outcomes.getOrElse(cursor++) { RuleBill.None }
    }

    private class MultipleRules(private val bills: List<ExtractedBill>) : RuleExtractor {
        override fun extract(request: IngestRequest, text: String): RuleBill = RuleBill.Multiple(bills)
    }

    private class LlmRule(private val bills: List<ExtractedBill> = emptyList()) : RuleExtractor {
        override fun extract(request: IngestRequest, text: String): RuleBill =
            if (bills.isEmpty()) RuleBill.NeedsLlm else RuleBill.Multiple(bills)
    }

    private class StaticLlm(
        private val bills: List<ExtractedBill> = emptyList(),
        private val chatBills: List<ExtractedBill> = emptyList(),
    ) : LlmExtractor {
        var outboundText: String? = null
        var rawText: String? = null
        var calls = 0

        override suspend fun extract(
            request: IngestRequest,
            outboundText: String,
            rawText: String,
            missing: List<String>,
        ): List<ExtractedBill> {
            calls++
            this.outboundText = outboundText
            this.rawText = rawText
            return bills
        }

        override suspend fun extractChat(
            request: IngestRequest,
            message: String,
            rawText: String,
            history: List<io.github.guohangbk.tally.domain.ai.AiTurn>,
        ): List<ExtractedBill> = chatBills

    }

    private suspend fun runIngestor(
        rules: RuleExtractor,
        llm: LlmExtractor,
        duplicate: Boolean = false,
        request: IngestRequest = request(),
    ): IngestResult = DefaultBillIngestor(
        rules = rules,
        llm = llm,
        duplicates = object : DuplicateLookup {
            override suspend fun isDuplicate(orderNo: String?, imageSha: String?, itemIndex: Int?) = duplicate
        },
    ).ingest(request)

    @Test
    fun `规则字段齐全时不调小莉且判级为高`() = runBlocking {
        val llm = StaticLlm()
        val result = runIngestor(StaticRules(ruleBill()), llm)
        assertEquals(null, llm.outboundText)
        assertEquals(IngestGrade.HIGH, result.bills.single().verdict.grade)
        assertEquals(FieldOrigin.RULE, result.bills.single().fields.origins[IngestKeys.AMOUNT])
        assertFalse(result.bills.single().verdict.dateDefaulted)
    }

    /**
     * 「买菜花了30块5」这种不带日期的说法是最日常的一笔，不能因为没有日期就退回确认卡：
     * 落成采集那一刻（时刻精度），卡上标一句「原文没提」，人看见自己就能改。
     */
    @Test
    fun `原文没提日期时落成采集时刻并标出来`() = runBlocking {
        val result = runIngestor(
            StaticRules(ruleBill(date = null)),
            StaticLlm(),
            request = request(text = "买菜花了35块"),
        )
        val bill = result.bills.single()
        assertEquals(now, bill.fields.occurredAt)
        assertEquals(TimePrecision.DATETIME, bill.fields.timePrecision)
        assertEquals(IngestGrade.HIGH, bill.verdict.grade)
        assertTrue(bill.verdict.dateDefaulted)
    }

    @Test
    fun `规则缺金额时交给 AI`() = runBlocking {
        val llm = StaticLlm(listOf(ruleBill(category = "food")))
        val result = runIngestor(LlmRule(), llm)
        assertTrue(llm.outboundText.orEmpty().isNotBlank())
        assertEquals(IngestGrade.HIGH, result.bills.single().verdict.grade)
    }

    @Test
    fun `规则缺分类时交给 AI 并全量覆盖规则已抽对的字段`() = runBlocking {
        // 规则认出了金额 35 与备注「午饭」但没分类；AI 回的是一笔「盒马 45.80 食材」。
        // 走 AI 就以 AI 为准，不做「规则 + AI」的拼接——否则金额与分类会来自两个不同判断。
        val aiBill = ruleBill(amount = 4580L, category = "food").copy(note = "盒马")
        val llm = StaticLlm(listOf(aiBill))
        val result = runIngestor(StaticRules(ruleBill(category = null)), llm)
        assertTrue(llm.outboundText.orEmpty().isNotBlank())
        assertEquals(4580L, result.bills.single().fields.amountMinor)
        assertEquals("food", result.bills.single().fields.categoryL1Id)
        // 规则原本抽到的「午饭」也被 AI 的结果顶掉了：全量覆盖，不做拼接。
        assertEquals("盒马", result.bills.single().fields.note)
    }

    @Test
    fun `AI 也没给金额时退回规则结果`() = runBlocking {
        val llm = StaticLlm(emptyList())
        val result = runIngestor(StaticRules(ruleBill(category = null)), llm)
        assertTrue(llm.outboundText.orEmpty().isNotBlank())
        assertEquals(3500L, result.bills.single().fields.amountMinor)
        assertEquals(null, result.bills.single().fields.categoryL1Id)
        assertEquals(IngestGrade.MEDIUM, result.bills.single().verdict.grade)
    }

    @Test
    fun `AI 调用失败时退回规则结果`() = runBlocking {
        val llm = object : LlmExtractor {
            override suspend fun extract(
                request: IngestRequest,
                outboundText: String,
                rawText: String,
                missing: List<String>,
            ): List<ExtractedBill> = throw IllegalStateException("连不上")

            override suspend fun extractChat(
                request: IngestRequest,
                message: String,
                rawText: String,
                history: List<io.github.guohangbk.tally.domain.ai.AiTurn>,
            ): List<ExtractedBill> = emptyList()
        }
        val result = runIngestor(StaticRules(ruleBill(category = null)), llm)
        assertEquals(3500L, result.bills.single().fields.amountMinor)
        assertEquals(IngestGrade.MEDIUM, result.bills.single().verdict.grade)
    }

    /** 语音转写是机器出的字，外发一律用脱敏版；原文只留在本机。 */
    @Test
    fun `语音转写的外发文本已脱敏`() = runBlocking {
        val llm = StaticLlm(listOf(ruleBill(amount = 4580L, category = "food")))
        val spoken = "买菜花了45.8，卡号 6222021234567890123"
        val result = runIngestor(
            rules = LlmRule(),
            llm = llm,
            request = request(text = "").copy(
                userText = spoken,
                redactedUserText = IngestRedactor.redact(spoken).text,
            ),
        )
        assertTrue(result.bills.single().evidence.rawText.contains("6222021234567890123"))
        assertFalse(llm.outboundText.orEmpty().contains("6222021234567890123"))
    }

    @Test
    fun `OCR 原文留在本机而外发文本已脱敏`() = runBlocking {
        val llm = StaticLlm(listOf(ruleBill(amount = 3500L, date = null)))
        val ocr = "实付款 ¥35.00 2026-11-13 12:00 手机号 13812345678 商户订单号 ABCDEFGH12345678"
        val result = runIngestor(
            rules = LlmRule(),
            llm = llm,
            request = request(
                text = "",
                source = IngestSource.SCREENSHOT,
                ocrText = ocr,
                images = listOf(byteArrayOf(1, 2, 3)),
            ),
        )
        assertTrue(result.bills.single().evidence.rawText.contains("13812345678"))
        assertTrue(result.bills.single().evidence.rawText.contains("ABCDEFGH12345678"))
        assertFalse(llm.outboundText.orEmpty().contains("13812345678"))
        assertFalse(llm.outboundText.orEmpty().contains("ABCDEFGH1234"))
        assertEquals(ocr, llm.rawText)
        // 原文里写了日期却没进这一笔：卡上必须标出来，判级也停在「要人看一眼」。
        assertEquals(IngestGrade.MEDIUM, result.bills.single().verdict.grade)
        assertTrue(result.bills.single().verdict.dateUnresolved)
    }

    /**
     * 通知补全必须拿到原文：只给「2.90 元」这一句，模型认不出这是买菜还是打车，
     * 分类和备注只能空着——待确认卡上就是一行「还没选分类」，这正是之前的 bug。
     */
    @Test
    fun `通知补全拿到的是脱敏后的通知原文而不是只给金额`() = runBlocking {
        val llm = StaticLlm()
        val body = "支付成功2.9元 【火锅】手作猪肉小馄饨 126g 手机号 13812345678"
        runIngestor(
            rules = LlmRule(),
            llm = llm,
            request = IngestRequest(
                source = IngestSource.NOTIFICATION,
                ocrText = "2.90元",
                redactedOcrText = IngestRedactor.redact(body).text,
                nowMillis = now,
                fallbackMillis = now,
                singleMaterial = true,
            ),
        )
        val outbound = llm.outboundText.orEmpty()
        assertTrue(outbound.contains("手作猪肉小馄饨"))
        assertTrue(outbound.contains("通知里的文字"))
        assertFalse(outbound.contains("13812345678"))
    }

    @Test
    fun `多图批量只向 AI 发送缺字段那几张`() = runBlocking {
        val llm = StaticLlm(listOf(ruleBill(amount = 1200L).copy(materialIndex = 1)))
        val results = DefaultBillIngestor(
            rules = SequenceRules(listOf(RuleBill.Single(ruleBill()), RuleBill.None)),
            llm = llm,
            duplicates = object : DuplicateLookup {
                override suspend fun isDuplicate(orderNo: String?, imageSha: String?, itemIndex: Int?) = false
            },
        ).ingestBatch(
            listOf(
                request(text = "午饭 35.00", ocrText = "第一张").copy(materialIndex = 0),
                request(text = "晚饭 12.00", ocrText = "第二张").copy(materialIndex = 1),
            ),
        )
        assertEquals(IngestGrade.HIGH, results[0].bills.single().verdict.grade)
        assertEquals(1200L, results[1].bills.single().fields.amountMinor)
        // 第 1 张规则已经认全，只把第 2 张发给 AI。
        assertTrue(llm.outboundText.orEmpty().contains("第 2 张"))
        assertFalse(llm.outboundText.orEmpty().contains("（material_index=0）"))
    }

    @Test
    fun `一张图本机拆出多笔时不调小莉只留卡上`() = runBlocking {
        val llm = StaticLlm()
        val first = ruleBill(amount = 3500L).copy(itemIndex = 0)
        val second = ruleBill(amount = 2000L).copy(note = "晚饭", itemIndex = 1)
        val result = runIngestor(
            MultipleRules(listOf(first, second)),
            llm,
            request = request(text = "", ocrText = "实付 ¥35.00 实付 ¥20.00", source = IngestSource.SCREENSHOT),
        )
        assertEquals(null, llm.outboundText)
        assertEquals(2, result.bills.size)
        assertEquals(listOf(0, 1), result.bills.map { it.evidence.itemIndex })
        assertTrue(result.bills.all { it.verdict.grade == IngestGrade.MEDIUM })
    }

    @Test
    fun `单张图多笔只带订单序号也能留在同一张`() = runBlocking {
        val llm = StaticLlm(
            listOf(
                ruleBill(amount = 3500L).copy(itemIndex = 0),
                ruleBill(amount = 2000L, date = now).copy(note = "晚饭", itemIndex = 1),
            ),
        )
        val result = runIngestor(
            LlmRule(),
            llm,
            request = request(
                text = "",
                source = IngestSource.SCREENSHOT,
                ocrText = "实付 ¥35.00\n实付 ¥20.00",
            ),
        )
        assertEquals(2, result.bills.size)
        assertEquals(listOf(0, 1), result.bills.map { it.evidence.itemIndex })
    }

    @Test
    fun `普通实付款截图不会被误判成批量`() = runBlocking {
        val llm = StaticLlm()
        val result = runIngestor(
            StaticRules(ruleBill()),
            llm,
            request = request(text = "", source = IngestSource.SCREENSHOT, ocrText = "实付款 ¥35.00"),
        )
        assertNull(llm.outboundText)
        assertEquals(IngestGrade.HIGH, result.bills.single().verdict.grade)
    }

    @Test
    fun `多图批量漏填来源时逐张重试不串账`() = runBlocking {
        val llm = StaticLlm(listOf(ruleBill(amount = 1200L)))
        val results = DefaultBillIngestor(
            rules = SequenceRules(listOf(RuleBill.None, RuleBill.None)),
            llm = llm,
            duplicates = object : DuplicateLookup {
                override suspend fun isDuplicate(orderNo: String?, imageSha: String?, itemIndex: Int?) = false
            },
        ).ingestBatch(
            listOf(
                request(text = "午饭 35.00", ocrText = "第一张").copy(materialIndex = 0),
                request(text = "晚饭 12.00", ocrText = "第二张").copy(materialIndex = 1),
            ),
        )
        assertEquals(3, llm.calls)
        assertEquals(listOf(0, 1), results.map { it.bills.single().fields.materialIndex })
    }

    @Test
    fun `重复订单号只改去重标记不改事实判级`() = runBlocking {
        val llm = StaticLlm()
        val result = runIngestor(
            rules = StaticRules(ruleBill()),
            llm = llm,
            duplicate = true,
        )
        assertTrue(result.bills.single().verdict.duplicate)
        assertEquals(IngestGrade.HIGH, result.bills.single().verdict.grade)
    }

    /**
     * 一单多件只印一个订单号：两笔都得进卡，订单号只留给第一笔。
     * 账单表的 `external_id` 是唯一索引，两笔共用一个号会让第二笔整笔插不进去——真机上就是「认了两笔只进一笔」。
     */
    @Test
    fun `同一订单号拆出的两笔都能进卡且只占一次号`() = runBlocking {
        val order = "DD202611130001"
        val first = ruleBill(amount = 3500L).copy(orderNo = order, itemIndex = 0)
        val second = ruleBill(amount = 2000L).copy(note = "晚饭", orderNo = order, itemIndex = 1)
        val result = runIngestor(
            MultipleRules(listOf(first, second)),
            StaticLlm(),
            request = request(text = "", ocrText = "实付 ¥35.00\n实付 ¥20.00", source = IngestSource.SCREENSHOT),
        )
        assertEquals(2, result.bills.size)
        assertFalse(result.bills.any { it.verdict.duplicate })
        assertEquals(order, result.bills.first().evidence.orderNo)
        assertNull(result.bills.last().evidence.orderNo)
    }

    /** 同一张单被两张图各认一遍：第二笔当场标成重复，不必等落库那一刻撞唯一索引。 */
    @Test
    fun `两张图认出同一订单号的同一笔时第二笔算重复`() = runBlocking {
        val order = "DD202611130001"
        val results = DefaultBillIngestor(
            rules = StaticRules(ruleBill(amount = 3500L).copy(orderNo = order)),
            llm = StaticLlm(),
            duplicates = object : DuplicateLookup {
                override suspend fun isDuplicate(orderNo: String?, imageSha: String?, itemIndex: Int?) = false
            },
        ).ingestBatch(listOf(request().copy(materialIndex = 0), request().copy(materialIndex = 1)))
        assertFalse(results[0].bills.single().verdict.duplicate)
        assertTrue(results[1].bills.single().verdict.duplicate)
    }

    @Test
    fun `对话通道不加规则过滤直接交给模型`() = runBlocking {
        val chatBill = ruleBill().copy(origins = mapOf(IngestKeys.AMOUNT to FieldOrigin.LLM))
        val result = runIngestor(
            rules = StaticRules(ruleBill(amount = 9900L)),
            llm = StaticLlm(chatBills = listOf(chatBill)),
            request = request(text = "帮我看看这张图", source = IngestSource.CHAT),
        )
        assertEquals(3500L, result.bills.single().fields.amountMinor)
        assertEquals(FieldOrigin.LLM, result.bills.single().fields.origins[IngestKeys.AMOUNT])
    }
}
