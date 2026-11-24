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
    OWN_TRANSFER,
}

data class Txn(
    val sms: Sms,
    val bank: String,
    val account: String?,
    val kind: Kind,
    val amount: Money,
    val merchant: String,
    val at: LocalDateTime,
    val balance: Money?,
    val category: String = "",
)

sealed interface ParseResult {
    data class Parsed(val txn: Txn) : ParseResult
    data class Ignored(val reason: String) : ParseResult
    data class Unparsed(val sms: Sms) : ParseResult
}
