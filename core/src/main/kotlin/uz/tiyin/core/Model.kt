package uz.tiyin.core

import java.time.LocalDateTime

data class Sms(val sender: String, val body: String, val receivedAt: LocalDateTime)

data class Money(val minor: Long, val currency: String) {
    override fun toString(): String = "%d.%02d %s".format(minor / 100, minor % 100, currency)
}

enum class Kind {
    /** Counted as spending. */
    PURCHASE,
    /** Money sent to another card or person; shown apart from spending. */
    TRANSFER_OUT,
    /** Money from outside: transfers in, refunds, interest. */
    INCOME,
    /** Movement between the user's own cards and deposits; excluded from every total. */
    OWN_TRANSFER;

    /** True when money leaves the user, false when it arrives, null when the kind alone does not say. */
    val outgoing: Boolean?
        get() = when (this) {
            PURCHASE, TRANSFER_OUT -> true
            INCOME -> false
            OWN_TRANSFER -> null
        }
}

/** Who decided an operation's kind and category, when it was the user rather than the rules. */
enum class MarkScope { OPERATION, MERCHANT }

data class Txn(
    /** Null for an operation known only from a Payme export. */
    val sms: Sms?,
    val bank: String,
    val account: String?,
    val kind: Kind,
    val amount: Money,
    val merchant: String,
    val at: LocalDateTime,
    val balance: Money?,
    val category: String = "",
    /** The export row describing the same operation, or the only source when [sms] is null. */
    val payme: PaymeOp? = null,
    /** Fixed by the source, never by a kind the user picks, so a mark cannot turn a debit into income. */
    val outgoing: Boolean? = payme?.debit ?: kind.outgoing,
    val markedBy: MarkScope? = null,
) {
    /**
     * Every identity the operation has: the SMS text and the export row's own unique fields. Neither
     * depends on the kind the rules chose, so a mark survives a rule change, and a row that an SMS
     * pairs with later still finds the mark made while it stood alone.
     */
    val ids: List<String>
        get() = listOfNotNull(
            sms?.let { "s:${it.sender}:${it.body}" },
            payme?.let { "p:${it.at}:${it.card.orEmpty()}:${it.debit}:${it.amount.minor}:${it.supplier}" },
        )

    /** Stable and unique within a ledger: SMS are deduplicated by text, export rows are unique in the store. */
    val key: String get() = ids.first()
}

/** The user's verdict on an operation; it wins over the dictionary, Payme's category and lookups. */
data class Mark(val kind: Kind, val category: String? = null) {
    fun fits(outgoing: Boolean?): Boolean = outgoing == null || kind.outgoing == null || kind.outgoing == outgoing
}

/** Marks by target: an operation id from [Txn.ids], or [merchantTarget] for every operation with that name. */
class Marks(private val byTarget: Map<String, Mark> = emptyMap()) {
    fun find(txn: Txn): Pair<Mark, MarkScope>? =
        txn.ids.firstNotNullOfOrNull { byTarget[it] }?.let { it to MarkScope.OPERATION }
            ?: byTarget[merchantTarget(txn)]?.let { it to MarkScope.MERCHANT }

    companion object {
        /** Direction is part of it, so marking purchases from a shop leaves its refunds alone. */
        fun merchantTarget(txn: Txn): String = "m:${when (txn.outgoing) { true -> "out"; false -> "in"; null -> "any" }}:${txn.merchant}"
    }
}

sealed interface ParseResult {
    data class Parsed(val txn: Txn) : ParseResult
    data class Ignored(val reason: String) : ParseResult
    data class Unparsed(val sms: Sms) : ParseResult
}
