package uz.tiyin.core

import java.io.InputStream
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * One operation from Payme's "Upload data to Excel" export. Only what the ledger needs is kept:
 * account numbers from payment details and full card masks are dropped on import.
 */
data class PaymeOp(
    val at: LocalDateTime,
    val debit: Boolean,
    val supplier: String,
    val organization: String,
    val amount: Money,
    val category: String,
    /** Last four digits; null for Payme's own wallet, which has no card. */
    val card: String?,
    val cardName: String,
    val viaPayme: Boolean,
)

object PaymeExport {
    class NotPaymeExport(message: String) : Exception(message)

    /** [skipped] counts rows whose operation type or status this parser does not know. */
    data class Parsed(val ops: List<PaymeOp>, val skipped: Int)

    private enum class Col { DATE, TIME, TYPE, SUPPLIER, ORGANIZATION, AMOUNT, CATEGORY, CARD, CARD_NAME, CHEQUE_TYPE, STATUS }

    // Payme localizes headers and a few values by app language; categories and supplier names stay Russian.
    private val headers = mapOf(
        Col.DATE to setOf("Pay date", "Дата платежа", "To'lov sanasi"),
        Col.TIME to setOf("Pay time", "Время платежа", "To'lov vaqti"),
        Col.TYPE to setOf("Operation type", "Тип операции", "Tranzaksiya turi"),
        Col.SUPPLIER to setOf("Supplier name", "Имя поставщика", "Yetkazib beruvchi nomi"),
        Col.ORGANIZATION to setOf("Name of supplier organization", "Название организации поставщика", "Yetkazib beruvchi tashkilotning nomi"),
        Col.AMOUNT to setOf("Amount", "Сумма платежа", "To'lov miqdori"),
        Col.CATEGORY to setOf("Category", "Категория", "Kategoriya"),
        Col.CARD to setOf("Card number", "Номер карты", "Karta raqami"),
        Col.CARD_NAME to setOf("Card name", "Название карты", "Karta nomi"),
        Col.CHEQUE_TYPE to setOf("Cheque type", "Тип чека", "Kvitansiya turi"),
        Col.STATUS to setOf("Cheque status", "Состояние чека", "To'lov holati"),
    )
    private val required = listOf(Col.DATE, Col.TIME, Col.TYPE, Col.SUPPLIER, Col.AMOUNT, Col.CARD)
    private val debits = setOf("Debiting", "Списание", "Chiqim")
    private val credits = setOf("Enrollment", "Поступление", "Kirim")
    private val paid = setOf("Paid", "Оплачен", "To'langan")
    private val canceled = setOf("Canceled", "Cancelled", "Отменен", "Отменён", "Bekor qilingan")

    private val stamp = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss")
    private val lastFour = Regex("(\\d{4})$")

    fun read(input: InputStream): Parsed =
        try {
            parse(Xlsx.firstSheet(input))
        } catch (e: Xlsx.NotXlsx) {
            throw NotPaymeExport(e.message ?: "not an xlsx file")
        } catch (e: java.util.zip.ZipException) {
            throw NotPaymeExport("not an xlsx file")
        } catch (e: org.xml.sax.SAXException) {
            throw NotPaymeExport("broken xlsx file")
        }

    private fun columns(row: List<String>): Map<Col, Int>? {
        val cells = row.map(::clean)
        val found = headers.mapNotNull { (col, names) -> cells.indexOfFirst { it in names }.takeIf { it >= 0 }?.let { col to it } }.toMap()
        return found.takeIf { it.keys.containsAll(required) }
    }

    fun parse(rows: List<List<String>>): Parsed {
        val headerAt = rows.indexOfFirst { columns(it) != null }
        if (headerAt < 0) throw NotPaymeExport("no Payme header row")
        val col = columns(rows[headerAt])!!
        var skipped = 0
        val ops = rows.drop(headerAt + 1).mapNotNull { row ->
            fun cell(c: Col) = col[c]?.let { row.getOrNull(it) }?.let(::clean) ?: ""
            if (row.all { it.isBlank() }) return@mapNotNull null
            val status = cell(Col.STATUS)
            if (status in canceled) return@mapNotNull null
            val type = cell(Col.TYPE)
            val debit = when {
                type in debits -> true
                type in credits -> false
                else -> null
            }
            val at = runCatching { LocalDateTime.parse("${cell(Col.DATE)} ${cell(Col.TIME)}", stamp) }.getOrNull()
            val amount = runCatching {
                BigDecimal(cell(Col.AMOUNT)).abs().movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact()
            }.getOrNull()
            if (debit == null || at == null || amount == null || (status.isNotEmpty() && status !in paid)) {
                skipped++
                return@mapNotNull null
            }
            PaymeOp(
                at = at,
                debit = debit,
                supplier = cell(Col.SUPPLIER),
                organization = cell(Col.ORGANIZATION),
                amount = Money(amount, "UZS"),
                category = cell(Col.CATEGORY),
                card = lastFour.find(cell(Col.CARD))?.groupValues?.get(1),
                cardName = cell(Col.CARD_NAME),
                viaPayme = cell(Col.CHEQUE_TYPE) == "Payme",
            )
        }
        return Parsed(ops, skipped)
    }

    private fun clean(s: String) = s.replace(Regex("\\s+"), " ").trim()

    private val savings = Regex("DEPOSIT|DEPOZIT|VKLAD|OMONAT", RegexOption.IGNORE_CASE)

    /**
     * Kind for an operation no SMS describes. Checked against every operation that both sources saw;
     * incoming "Перевод на карту" there was always the user's own Visa-to-Humo conversion, and the
     * export does not show the sender, so such rows are kept out of income.
     */
    fun kind(op: PaymeOp): Kind = when {
        op.category == "вклады" || savings.containsMatchIn(op.supplier) -> Kind.OWN_TRANSFER
        !op.debit -> if (op.supplier == "Перевод на карту") Kind.OWN_TRANSFER else Kind.INCOME
        op.category == "перевод" -> Kind.TRANSFER_OUT
        else -> Kind.PURCHASE
    }

    private val categories = mapOf(
        "такси" to "Такси",
        "продукты" to "Продукты и доставка",
        "общепит" to "Кафе и рестораны",
        "питомцы" to "Животные",
        "здоровье" to "Здоровье",
        "аптеки" to "Здоровье",
        "транспорт" to "Транспорт",
        "ком. услуги" to "Коммунальные",
        "интернет" to "Связь",
        "мобильная связь" to "Связь",
        "услуги" to "Услуги",
        "магазин" to "Покупки",
        "техника" to "Техника",
        "одежда" to "Одежда",
        "красота" to "Красота",
        "образование" to "Образование",
        "развлечения" to "Спорт и развлечения",
        "программы" to "Подписки и сервисы",
        "наличные" to "Наличные",
        "путешествия" to "Путешествия",
        "для дома" to "Дом",
    )

    /** Tiyin's name for Payme's category; null where Payme itself does not know ("прочее"). */
    fun category(paymeCategory: String): String? {
        val c = paymeCategory.trim().lowercase()
        if (c.isEmpty() || c == "прочее" || c == "перевод" || c == "вклады") return null
        return categories[c] ?: c.replaceFirstChar { it.uppercase() }
    }
}
