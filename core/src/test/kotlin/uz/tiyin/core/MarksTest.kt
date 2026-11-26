package uz.tiyin.core

import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MarksTest {
    private val at = LocalDateTime.of(2026, 10, 2, 16, 4, 30)

    private fun op(supplier: String, amount: Long, category: String, at: LocalDateTime, debit: Boolean = true, card: String? = "1111") =
        PaymeOp(at, debit, supplier, supplier, Money(amount * 100, "UZS"), category, card, "Test Humo", viaPayme = false)

    private fun humo(op: String, amount: String, merchant: String, time: String) =
        Sms("HUMO", "HUMOCARD *1111: $op $amount UZS; $merchant; $time;  Dostupno: 5000000.00 UZS", LocalDateTime.of(2026, 10, 3, 9, 0))

    private val ops = listOf(
        op("Перевод с карты", 219_000_000, "перевод", at),
        op("Перевод с карты", 3_000_000, "перевод", at.plusDays(1)),
        op("BN 1 OPLATA", 10_000_000, "прочее", at.plusDays(2)),
        op("TEST SHOP", 50_000, "магазин", at.plusDays(3)),
        op("TEST SHOP", 50_000, "магазин", at.plusDays(4), debit = false),
    )

    private fun ledger(marks: Map<String, Mark> = emptyMap(), payme: List<PaymeOp> = ops, sms: List<Sms> = emptyList()) =
        Ledger(sms, payme = payme, marks = Marks(marks))

    private fun Ledger.one(merchant: String, debit: Boolean = true) =
        transactions.filter { it.merchant == merchant && it.outgoing == debit }.maxBy { it.amount.minor }

    @Test
    fun anOperationMarkBeatsTheRulesAndTouchesNothingElse() {
        val plain = ledger()
        val transfer = plain.one("Перевод с карты")
        assertEquals(Kind.TRANSFER_OUT, transfer.kind)

        val marked = ledger(mapOf(transfer.key to Mark(Kind.OWN_TRANSFER)))
        val now = marked.one("Перевод с карты")
        assertEquals(Kind.OWN_TRANSFER, now.kind)
        assertEquals(Categories.OWN, now.category)
        assertEquals(MarkScope.OPERATION, now.markedBy)
        assertEquals(Kind.TRANSFER_OUT, marked.transactions.single { it.amount.minor == 300_000_000L }.kind)
    }

    @Test
    fun aSpendingMarkSetsTheCategoryAndKeepsTheMerchantFromLookups() {
        val bn = ledger().one("BN 1 OPLATA")
        assertEquals(setOf("BN 1 OPLATA"), ledger().unknownMerchants.intersect(setOf("BN 1 OPLATA")))
        val marked = ledger(mapOf(bn.key to Mark(Kind.PURCHASE, "Жильё")))
        assertEquals(Kind.PURCHASE to "Жильё", marked.one("BN 1 OPLATA").let { it.kind to it.category })
        assertEquals(emptySet(), marked.unknownMerchants.intersect(setOf("BN 1 OPLATA")))
    }

    @Test
    fun aMerchantMarkCoversOneDirectionOnly() {
        val shop = ledger().one("TEST SHOP")
        val marked = ledger(mapOf(Marks.merchantTarget(shop) to Mark(Kind.PURCHASE, "Дом")))
        assertEquals("Дом", marked.one("TEST SHOP").category)
        assertEquals(MarkScope.MERCHANT, marked.one("TEST SHOP").markedBy)
        val refund = marked.one("TEST SHOP", debit = false)
        assertEquals(Kind.INCOME, refund.kind)
        assertNull(refund.markedBy)
    }

    @Test
    fun anOperationMarkWinsOverTheMerchantMark() {
        val shop = ledger().one("TEST SHOP")
        val marked = ledger(mapOf(Marks.merchantTarget(shop) to Mark(Kind.PURCHASE, "Дом"), shop.key to Mark(Kind.TRANSFER_OUT)))
        assertEquals(Kind.TRANSFER_OUT to MarkScope.OPERATION, marked.one("TEST SHOP").let { it.kind to it.markedBy })
    }

    @Test
    fun aMarkThatWouldReverseTheMoneyIsIgnored() {
        val refund = ledger().one("TEST SHOP", debit = false)
        val marked = ledger(mapOf(refund.key to Mark(Kind.PURCHASE, "Дом")))
        assertEquals(Kind.INCOME, marked.one("TEST SHOP", debit = false).kind)
        assertNull(marked.one("TEST SHOP", debit = false).markedBy)
    }

    @Test
    fun aMarkMadeOnAnExportRowStillAppliesOnceAnSmsPairsWithIt() {
        val row = op("YANDEX LAVKA", 25_500, "продукты", LocalDateTime.of(2026, 11, 24, 9, 10, 41))
        val alone = ledger(payme = listOf(row)).transactions.single()
        val mark = mapOf(alone.key to Mark(Kind.PURCHASE, "Дом"))
        val paired = ledger(mark, payme = listOf(row), sms = listOf(humo("oplata", "25500.00", "YANDEX LAVKA", "26-11-24 09:10"))).transactions.single()
        assertEquals("s", paired.key.take(1))
        assertEquals("Дом" to MarkScope.OPERATION, paired.category to paired.markedBy)
    }

    @Test
    fun keysDoNotDependOnTheKindSoMarksSurviveRuleChanges() {
        val plain = ledger().transactions
        assertEquals(plain.size, plain.map { it.key }.toSet().size)
        val all = plain.associate { it.key to Mark(Kind.OWN_TRANSFER) }
        val marked = ledger(all).transactions
        assertEquals(plain.map { it.key }.toSet(), marked.map { it.key }.toSet())
        assertEquals(plain.size, marked.count { it.markedBy == MarkScope.OPERATION })
    }

    @Test
    fun withoutMarksEverythingIsAsBefore() {
        assertEquals(ledger().transactions.map { it.kind to it.category }, ledger(emptyMap()).transactions.map { it.kind to it.category })
        assertEquals(emptyList(), ledger().transactions.mapNotNull { it.markedBy })
    }
}
