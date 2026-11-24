package uz.tiyin.core

import java.time.LocalDateTime

/**
 * Everything is derived from the stored raw SMS on every read, so a parser or category fix
 * applies to the whole history without migrations.
 */
class Ledger(raw: List<Sms>, lookedUpCategories: Map<String, String> = emptyMap()) {
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
                is ParseResult.Parsed -> txns += r.txn.copy(category = Categories.categorize(r.txn, lookedUpCategories))
                is ParseResult.Unparsed -> failed += sms
                is ParseResult.Ignored -> skipped++
            }
        }
        transactions = txns.sortedByDescending { it.at }
        unparsed = failed.sortedByDescending { it.receivedAt }
        ignored = skipped
    }

    fun between(from: LocalDateTime, to: LocalDateTime): List<Txn> =
        transactions.filter { !it.at.isBefore(from) && it.at.isBefore(to) }

    data class CategoryTotal(val category: String, val total: Money, val count: Int)

    companion object {
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
