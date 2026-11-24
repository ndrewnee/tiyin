package uz.tiyin.core

import java.time.Duration
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/**
 * Everything is derived from the stored raw SMS and Payme export rows on every read, so a parser or
 * category fix applies to the whole history without migrations, and an SMS that arrives after an
 * export pairs with its row instead of being counted twice.
 */
class Ledger(
    raw: List<Sms>,
    lookedUpCategories: Map<String, String> = emptyMap(),
    payme: List<PaymeOp> = emptyList(),
) {
    val transactions: List<Txn>
    val unparsed: List<Sms>
    val ignored: Int

    /** Merchants of purchases that neither the dictionary nor an earlier lookup could place. */
    val unknownMerchants: Set<String>
        get() = transactions.filter { it.kind == Kind.PURCHASE && it.category == Categories.OTHER }.map { it.merchant }.toSet()

    init {
        val txns = mutableListOf<Txn>()
        val failed = mutableListOf<Sms>()
        var skipped = 0
        for (sms in dedupe(raw)) {
            when (val r = Parsers.parse(sms)) {
                is ParseResult.Parsed -> txns += r.txn
                is ParseResult.Unparsed -> failed += sms
                is ParseResult.Ignored -> skipped++
            }
        }
        transactions = withPayme(txns, payme)
            .map { it.copy(category = Categories.categorize(it, lookedUpCategories)) }
            .sortedByDescending { it.at }
        unparsed = failed.sortedByDescending { it.receivedAt }
        ignored = skipped
    }

    fun between(from: LocalDateTime, to: LocalDateTime): List<Txn> =
        transactions.filter { !it.at.isBefore(from) && it.at.isBefore(to) }

    data class CategoryTotal(val category: String, val total: Money, val count: Int)

    companion object {
        private val paymePlaceholder = Regex("^PAYME\\b", RegexOption.IGNORE_CASE)
        private const val MATCH_WINDOW_SECONDS = 60L

        /**
         * Pairs each SMS with the export row for the same card, amount and minute. The SMS keeps kind,
         * amount and time; the row only names a merchant the SMS hides behind "PAYME OPLATA" and lends
         * its category. Rows nothing pairs with are operations the SMS never told about.
         */
        fun withPayme(txns: List<Txn>, ops: List<PaymeOp>): List<Txn> {
            if (ops.isEmpty()) return txns
            val pool = HashMap<Pair<String?, Money>, MutableList<PaymeOp>>()
            for (op in ops) pool.getOrPut(op.card to op.amount) { mutableListOf() } += op
            val paired = txns.sortedBy { it.at }.map { t ->
                val candidates = t.account?.let { pool[it to t.amount] } ?: return@map t
                fun gap(op: PaymeOp) = Math.abs(Duration.between(t.at, op.at.truncatedTo(ChronoUnit.MINUTES)).seconds)
                val op = candidates.filter { gap(it) <= MATCH_WINDOW_SECONDS }.minByOrNull(::gap) ?: return@map t
                candidates.remove(op)
                val named = paymePlaceholder.containsMatchIn(t.merchant) && op.supplier.isNotEmpty()
                t.copy(payme = op, merchant = if (named) op.supplier else t.merchant)
            }
            val alone = pool.values.flatten().map { op ->
                Txn(
                    sms = null, bank = op.cardName.takeIf { op.card != null && it.isNotEmpty() } ?: "Payme", account = op.card, kind = PaymeExport.kind(op),
                    amount = op.amount, merchant = op.supplier, at = op.at, balance = null, payme = op,
                )
            }
            return paired + alone
        }

        /**
         * The same SMS can be stored twice (delivered twice, or captured by the receiver and again from the
         * inbox with a different timestamp). Bank texts carry their own time and the new balance, so an
         * identical sender and text is always the same message.
         */
        fun dedupe(raw: List<Sms>): List<Sms> =
            raw.sortedBy { it.receivedAt }.distinctBy { it.sender.uppercase() to it.body.replace(Regex("\\s+"), " ").trim() }

        /** Totals per currency and category; own transfers are never counted. */
        fun totals(txns: List<Txn>, kinds: Set<Kind>): Map<String, List<CategoryTotal>> =
            txns.filter { it.kind in kinds }
                .groupBy { it.amount.currency }
                .mapValues { (currency, list) ->
                    list.groupBy { it.category }
                        .map { (cat, items) -> CategoryTotal(cat, Money(items.sumOf { it.amount.minor }, currency), items.size) }
                        .sortedByDescending { it.total.minor }
                }
    }
}
