package uz.tiyin.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.LocalDateTime
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class PaymeTest {
    private val header = listOf(
        "Pay date", "Pay time", "Operation type", "Supplier name", "Name of supplier organization", "Amount", "Category",
        "Recipient / Sender", "Payment details", "Card number", "Card name", "Comment to payment", "Cheque type",
        "Cheque status", "Service point", "Terminal",
    )

    private fun row(
        date: String, time: String, op: String, supplier: String, amount: String, category: String,
        card: String = "986010******1111", cardName: String = "Test Humo", cheque: String = "External", status: String = "Paid",
    ) = listOf(date, time, op, supplier, "ООО «$supplier»", amount, category, "", "Номер счета:\n 123\t", card, cardName, "Покупка/Платёж", cheque, status, "0098\t", "T1\t")

    /** Mirrors the real export: text as shared strings, amounts as numbers, explicit cell references. */
    private fun xlsx(rows: List<List<String>>): ByteArray {
        val shared = LinkedHashMap<String, Int>()
        val sheet = StringBuilder("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>")
        rows.forEachIndexed { r, cells ->
            sheet.append("<row r=\"${r + 1}\">")
            cells.forEachIndexed { c, v ->
                if (v.isEmpty() && c == 7) return@forEachIndexed
                val ref = "${'A' + c}${r + 1}"
                if (r > 0 && c == 5) sheet.append("<c r=\"$ref\" t=\"n\"><v>$v</v></c>")
                else sheet.append("<c r=\"$ref\" t=\"s\"><v>${shared.getOrPut(v) { shared.size }}</v></c>")
            }
            sheet.append("</row>")
        }
        sheet.append("</sheetData></worksheet>")
        val sst = shared.keys.joinToString("", "<sst xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">", "</sst>") {
            "<si><t xml:space=\"preserve\">${it.replace("&", "&amp;").replace("<", "&lt;")}</t></si>"
        }
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            z.putNextEntry(ZipEntry("xl/worksheets/sheet1.xml")); z.write(sheet.toString().toByteArray())
            z.putNextEntry(ZipEntry("xl/sharedStrings.xml")); z.write(sst.toByteArray())
        }
        return out.toByteArray()
    }

    private fun read(vararg rows: List<String>) = PaymeExport.read(ByteArrayInputStream(xlsx(listOf(header) + rows))).ops

    @Test
    fun readsTheExportAndDropsCanceledAndUndatedRows() {
        val ops = read(
            row("22-11-2026", "10:20:30", "Debiting", "Test Clinic", "777700", "услуги", cheque = "Payme"),
            row("21-11-2026", "09:15:00", "Debiting", "Test Cafe", "12345.6", "общепит"),
            row("20-11-2026", "10:00:00", "Debiting", "Test Shop", "1000", "услуги", status = "Canceled"),
            row("", "", "Debiting", "Test Net", "250000", "интернет", status = "Canceled"),
            row("15-11-2026", "08:00:00", "Debiting", "Test Mobile", "30000", "интернет", card = "plus", cardName = "00000000-0000-4000-8000-000000000000"),
        )
        assertEquals(3, ops.size)
        val clinic = ops[0]
        assertEquals(LocalDateTime.of(2026, 11, 22, 10, 20, 30), clinic.at)
        assertEquals(Money(77770000, "UZS"), clinic.amount)
        assertEquals("1111", clinic.card)
        assertEquals(true, clinic.viaPayme)
        assertEquals(Money(1234560, "UZS"), ops[1].amount)
        assertNull(ops[2].card)
    }

    @Test
    fun readsRussianAndUzbekExports() {
        val ru = listOf(
            "Дата платежа", "Время платежа", "Тип операции", "Имя поставщика", "Название организации поставщика", "Сумма платежа",
            "Категория", "Получатель / Отправитель", "Реквизиты платежа", "Номер карты", "Название карты", "Комментарий к платежу",
            "Тип чека", "Состояние чека", "Пункт обслуживания", "Терминал",
        )
        val uz = listOf(
            "To'lov sanasi", "To'lov vaqti", "Tranzaksiya turi", "Yetkazib beruvchi nomi", "Yetkazib beruvchi tashkilotning nomi",
            "To'lov miqdori", "Kategoriya", "Qabul qiluvchi / jo'natuvchi", "To'lov tafsilotlari", "Karta raqami", "Karta nomi",
            "To'lovga izoh", "Kvitansiya turi", "To'lov holati", "xizmat ko'rsatish punkti", "Terminal",
        )
        val ruRows = listOf(
            row("24-11-2026", "12:00:00", "Списание", "Test Shop", "5000", "магазин", cheque = "Внешний", status = "Оплачен"),
            row("24-11-2026", "12:05:00", "Поступление", "Перевод на карту", "9000", "перевод", cheque = "Внешний", status = "Оплачен"),
            row("24-11-2026", "12:10:00", "Списание", "Test Net", "3000", "интернет", cheque = "Payme", status = "Отменен"),
        )
        val uzRows = listOf(
            row("24-11-2026", "12:00:00", "Chiqim", "Test Shop", "5000", "магазин", cheque = "Tashqi", status = "To'langan"),
            row("24-11-2026", "12:05:00", "Kirim", "Перевод на карту", "9000", "перевод", cheque = "Tashqi", status = "To'langan"),
            row("24-11-2026", "12:10:00", "Chiqim", "Test Net", "3000", "интернет", cheque = "Payme", status = "Bekor qilingan"),
        )
        for ((h, rows) in listOf(ru to ruRows, uz to uzRows)) {
            val parsed = PaymeExport.read(ByteArrayInputStream(xlsx(listOf(h) + rows)))
            assertEquals(listOf(true, false), parsed.ops.map { it.debit })
            assertEquals(0, parsed.skipped)
        }
    }

    @Test
    fun countsRowsItCannotReadInsteadOfGuessingTheirDirection() {
        val parsed = PaymeExport.read(
            ByteArrayInputStream(
                xlsx(
                    listOf(
                        header,
                        row("24-11-2026", "12:00:00", "Refund", "Test Shop", "5000", "магазин"),
                        row("24-11-2026", "12:01:00", "Debiting", "Test Shop", "5000", "магазин", status = "Pending"),
                        row("24-11-2026", "12:02:00", "Debiting", "Test Shop", "5000", "магазин"),
                    ),
                ),
            ),
        )
        assertEquals(1, parsed.ops.size)
        assertEquals(2, parsed.skipped)
    }

    @Test
    fun rejectsFilesThatAreNotPaymeExports() {
        assertFailsWith<PaymeExport.NotPaymeExport> { PaymeExport.read(ByteArrayInputStream("not a zip".toByteArray())) }
        assertFailsWith<PaymeExport.NotPaymeExport> {
            PaymeExport.read(ByteArrayInputStream(xlsx(listOf(listOf("Date", "Sum"), listOf("01-11-2026", "5")))))
        }
    }

    private val at = LocalDateTime.of(2026, 11, 22, 13, 53)
    private fun humo(op: String, amount: String, merchant: String, time: String = "26-11-22 13:53") =
        Sms("HUMO", "HUMOCARD *1111: $op $amount UZS; $merchant; $time;  Dostupno: 100000.00 UZS", at)

    private fun op(supplier: String, amount: Long, category: String, at: LocalDateTime, debit: Boolean = true, card: String? = "1111") =
        PaymeOp(at, debit, supplier, "", Money(amount * 100, "UZS"), category, card, "Test Humo", viaPayme = true)

    @Test
    fun namesTheMerchantBehindPaymeAndCountsThePairOnce() {
        val ledger = Ledger(
            listOf(humo("oplata", "777700.00", "PAYME OPLATA QR>TASH")),
            payme = listOf(op("Test Clinic", 777700, "услуги", at.plusSeconds(3))),
        )
        val t = ledger.transactions.single()
        assertEquals("Test Clinic", t.merchant)
        assertEquals("Здоровье", t.category)
        assertEquals(Kind.PURCHASE, t.kind)
    }

    @Test
    fun keepsSmsMerchantAndTakesPaymeCategoryOnlyWhenDictionaryIsSilent() {
        val ledger = Ledger(
            listOf(
                humo("oplata", "49900.00", "TEST RETAIL STORE"),
                humo("oplata", "25500.00", "YandexGO Taxi UB OPL"),
                humo("oplata", "70000.00", "OOO TEST BOOKS"),
            ),
            payme = listOf(
                op("TEST RETAIL STORE", 49900, "техника", at),
                op("YandexGO Taxi UB OPLATA", 25500, "прочее", at),
                op("OOO TEST BOOKS", 70000, "прочее", at),
            ),
        )
        val byMerchant = ledger.transactions.associate { it.merchant to it.category }
        assertEquals("Техника", byMerchant["TEST RETAIL STORE"])
        assertEquals("Такси", byMerchant["YandexGO Taxi UB OPL"])
        assertEquals(Categories.OTHER, byMerchant["OOO TEST BOOKS"])
        assertEquals(setOf("OOO TEST BOOKS"), ledger.unknownMerchants)
    }

    @Test
    fun rowsNoSmsDescribesAreAddedWithTheirOwnKind() {
        val early = LocalDateTime.of(2026, 11, 3, 10, 0, 7)
        val ledger = Ledger(
            emptyList(),
            payme = listOf(
                op("OOO TEST WORKS", 44000, "прочее", early),
                op("Перевод на карту", 1111000, "перевод", early.plusMinutes(1), debit = false),
                op("Test Person A.", 222000, "перевод", early.plusMinutes(2)),
                op("TEST DEPOSIT", 333000, "прочее", early.plusMinutes(3)),
                op("YANDEX LAVKA POPOL KART", 700, "продукты", early.plusMinutes(4), debit = false),
                op("Payme Plus | Всё включено", 9990, "услуги", early.plusMinutes(5), card = null),
            ),
        )
        val kinds = ledger.transactions.associate { it.merchant to (it.kind to it.category) }
        assertEquals(Kind.PURCHASE to Categories.OTHER, kinds["OOO TEST WORKS"])
        assertEquals(Kind.OWN_TRANSFER, kinds["Перевод на карту"]!!.first)
        assertEquals(Kind.TRANSFER_OUT, kinds["Test Person A."]!!.first)
        assertEquals(Kind.OWN_TRANSFER, kinds["TEST DEPOSIT"]!!.first)
        assertEquals(Kind.INCOME, kinds["YANDEX LAVKA POPOL KART"]!!.first)
        assertEquals(Kind.PURCHASE to "Услуги", kinds["Payme Plus | Всё включено"])
        assertEquals("Payme", ledger.transactions.single { it.account == null }.bank)
        assertEquals(ledger.transactions.size, ledger.transactions.map { it.key }.toSet().size)
    }

    @Test
    fun pairsByCardAmountAndMinuteSoRepeatedFaresDoNotCollapse() {
        val ledger = Ledger(
            listOf(
                humo("oplata", "21000.00", "YandexGO Taxi UB OPL", "26-11-22 12:53"),
                humo("oplata", "21000.00", "YandexGO Taxi UB OPL", "26-11-22 14:01"),
                humo("oplata", "21000.00", "YandexGO Taxi UB OPL", "26-11-22 16:00"),
            ),
            payme = listOf(
                op("YandexGO Taxi UB OPLATA", 21000, "такси", LocalDateTime.of(2026, 11, 22, 12, 53, 40)),
                op("YandexGO Taxi UB OPLATA", 21000, "такси", LocalDateTime.of(2026, 11, 22, 14, 1, 5)),
                op("YandexGO Taxi UB OPLATA", 21000, "такси", LocalDateTime.of(2026, 11, 22, 16, 5, 0)),
                op("YandexGO Taxi UB OPLATA", 21000, "такси", LocalDateTime.of(2026, 11, 22, 12, 53, 10), card = "2222"),
            ),
        )
        assertEquals(5, ledger.transactions.size)
        assertEquals(2, ledger.transactions.count { it.sms != null && it.payme != null })
        assertEquals(2, ledger.transactions.count { it.sms == null })
    }
}
