package uz.tiyin.core

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

object Parsers {
    val bankSenders: Set<String> = setOf("HUMO", "UZCARD", "ASAKABANK", "KAPITALBANK", "PAYME")

    fun isBankSender(sender: String): Boolean = sender.trim().uppercase() in bankSenders

    private val otp = Regex(
        "(kod|код|code|parol|пароль|otp|tasdiq|подтвержд|не сообщайте|hech kimga)",
        RegexOption.IGNORE_CASE,
    )

    fun parse(sms: Sms): ParseResult {
        if (!isBankSender(sms.sender)) return ParseResult.Ignored("not a bank sender")
        val body = sms.body.replace(Regex("\\s+"), " ").trim()
        val parsed = when (sms.sender.trim().uppercase()) {
            "HUMO" -> humo(sms, body)
            "UZCARD" -> uzcard(sms, body)
            "KAPITALBANK" -> kapitalbank(sms, body)
            "ASAKABANK" -> asakabank(sms, body)
            else -> null
        }
        // Checked only after the transaction formats, so a merchant named like "CODE CAFE" is not dropped.
        if (parsed == null && otp.containsMatchIn(sms.body)) return ParseResult.Ignored(CONFIRMATION_CODE)
        return parsed ?: ParseResult.Unparsed(sms)
    }

    private const val CONFIRMATION_CODE = "confirmation code"

    /** Only bank SMS are kept, and never confirmation codes. */
    fun shouldStore(sms: Sms): Boolean {
        if (!isBankSender(sms.sender)) return false
        val r = parse(sms)
        return !(r is ParseResult.Ignored && r.reason == CONFIRMATION_CODE)
    }

    private fun amount(s: String, currency: String): Money {
        val clean = s.replace(" ", "").replace(",", ".").removePrefix("-")
        val parts = clean.split(".")
        val whole = parts[0].toLong()
        val frac = parts.getOrNull(1)?.padEnd(2, '0')?.take(2)?.toLong() ?: 0
        return Money(whole * 100 + frac, currency.uppercase().let { if (it == "SUM") "UZS" else it })
    }

    private val ymdShort = DateTimeFormatter.ofPattern("yy-MM-dd HH:mm")
    private val dmyShort = DateTimeFormatter.ofPattern("dd.MM.yy HH:mm")
    private val dmyLong = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm")
    private val dmyDate = DateTimeFormatter.ofPattern("dd.MM.yyyy")

    private fun dateOnly(s: String, sms: Sms): LocalDateTime {
        val d = LocalDate.parse(s, dmyDate)
        return if (sms.receivedAt.toLocalDate() == d) sms.receivedAt else LocalDateTime.of(d, LocalTime.NOON)
    }

    private fun txn(
        sms: Sms, bank: String, account: String?, kind: Kind, amount: Money,
        merchant: String, at: LocalDateTime, balance: Money?,
    ) = ParseResult.Parsed(Txn(sms, bank, account, kind, amount, merchant.trim(), at, balance))

    // HUMOCARD *1111: oplata 25500.00 UZS; YandexGO Taxi UB OPL; 26-11-24 09:10; Dostupno: 100000.00 UZS
    private val humoRe = Regex(
        "^HUMOCARD \\*(\\d+): (\\w+) ([\\d.]+) (\\w+); (.*?); (\\d\\d-\\d\\d-\\d\\d \\d\\d:\\d\\d); Dostupno: ([\\d.]+) (\\w+)",
    )
    private val humoTransferOut = Regex("P2P|HUMO NA|HUMO UZCAR|to Visa|H2H", RegexOption.IGNORE_CASE)
    private val humoOwnIn = Regex("KB to HUMO|OMONAT|VKLAD|DEPOZIT", RegexOption.IGNORE_CASE)

    private fun humo(sms: Sms, body: String): ParseResult? {
        val m = humoRe.find(body) ?: return null
        val (card, op, amt, cur, merchant, at, bal, balCur) = m.destructured
        val kind = when (op.lowercase()) {
            "oplata", "operacija" -> if (humoTransferOut.containsMatchIn(merchant)) Kind.TRANSFER_OUT else Kind.PURCHASE
            "popolnenie" -> if (humoOwnIn.containsMatchIn(merchant)) Kind.OWN_TRANSFER else Kind.INCOME
            else -> return null
        }
        return txn(
            sms, "HUMO", card, kind, amount(amt, cur), merchant.substringBefore(">"),
            LocalDateTime.parse(at, ymdShort), amount(bal, balCur),
        )
    }

    // Xarid: ZOO DOCTOR XK, TASHKENT, MIRABAD 33 22.11.26 14:21 karta ***2222. Miqdor:295000.00 UZS, Qoldiq:100000.00 UZS
    private val uzcardRe = Regex(
        "^(Xarid|Pokupka|Oplata|To'lov|Tolov|Popolnenie|Toldirish|To'ldirish|Perevod|O'tkazma|Otkazma|Snyatie|Yechish): (.*?) (\\d\\d\\.\\d\\d\\.\\d\\d \\d\\d:\\d\\d) karta \\*+(\\d+)\\. Miqdor: ?([\\d.]+) (\\w+), Qoldiq: ?([\\d.]+) (\\w+)",
        RegexOption.IGNORE_CASE,
    )

    private fun uzcard(sms: Sms, body: String): ParseResult? {
        val m = uzcardRe.find(body) ?: return null
        val (op, merchant, at, card, amt, cur, bal, balCur) = m.destructured
        val kind = when (op.lowercase()) {
            "xarid", "pokupka", "oplata", "to'lov", "tolov" -> Kind.PURCHASE
            "popolnenie", "toldirish", "to'ldirish" -> Kind.INCOME
            "perevod", "o'tkazma", "otkazma" -> Kind.TRANSFER_OUT
            else -> Kind.PURCHASE
        }
        return txn(
            sms, "UZCARD", card, kind, amount(amt, cur), merchant.substringBefore(","),
            LocalDateTime.parse(at, dmyShort), amount(bal, balCur),
        )
    }

    // Snyatiye, 15.11.26 v 17:10. Karta (*1111). Summa: 100.00 USD. VISA MC USD 2 HUMO KB>Tashkent UZ. Dostupno: 100.00 USD
    private val kbCardRe = Regex(
        "^(Snyatiye|Popolneniye), (\\d\\d\\.\\d\\d\\.\\d\\d) v (\\d\\d:\\d\\d)\\. Karta \\(\\*(\\d+)\\)\\. Summa: ([\\d.]+) (\\w+)\\. (.*?)\\. Dostupno: ([\\d.]+) (\\w+)",
    )

    // Karta *1111. Xarid/Pokupka "ANTHROPIC* CLAUDE SU", -200.00, USD, "19-11-2026 15:05". Dostupno: 100.00, USD.
    private val kbPurchaseRe = Regex(
        "^Karta \\*(\\d+)\\. Xarid/Pokupka \"(.*?)\", (-?[\\d.]+), (\\w+), \"(\\d\\d-\\d\\d-\\d{4} \\d\\d:\\d\\d)\"\\. Dostupno: ([\\d.]+), (\\w+)",
    )

    // S vklada 'Test vklad' spisano 500.00 USD. Ostatok - 1000.00 USD. 18.11.2026.
    private val kbDepositOutRe = Regex("^S vklada '(.*?)' spisano ([\\d.]+) (\\w+)\\. Ostatok - ([\\d.]+) (\\w+)\\. (\\d\\d\\.\\d\\d\\.\\d{4})")

    // Vyplata % po vkladu 'Test vklad' - 10.00 USD. Ostatok na schete 2020... - 10.00 USD. 19.11.2026
    private val kbInterestRe = Regex("^Vyplata % po vkladu '(.*?)' - ([\\d.]+) (\\w+)\\..*?(\\d\\d\\.\\d\\d\\.\\d{4})")

    // The bank sends every deposit notice twice, in Russian and in Uzbek; the Uzbek copy is dropped.
    private val kbUzbekDuplicate = Regex("omonatidan .* yechildi|omonati bo'yicha to'langan")
    private val kbInternal = Regex("\\b2\\b") // "DEP USD 2 VISA", "VISA MC USD 2 HUMO KB": transfers inside the bank

    private fun kapitalbank(sms: Sms, body: String): ParseResult? {
        if (kbUzbekDuplicate.containsMatchIn(body)) return ParseResult.Ignored("uzbek duplicate of a deposit notice")
        kbCardRe.find(body)?.let { m ->
            val (op, date, time, card, amt, cur, desc, bal, balCur) = m.destructured
            val kind = when {
                kbInternal.containsMatchIn(desc) -> Kind.OWN_TRANSFER
                op == "Snyatiye" -> Kind.PURCHASE
                else -> Kind.INCOME
            }
            return txn(
                sms, "KAPITALBANK", card, kind, amount(amt, cur), desc.substringBefore(">"),
                LocalDateTime.parse("$date $time", dmyShort), amount(bal, balCur),
            )
        }
        kbPurchaseRe.find(body)?.let { m ->
            val (card, merchant, amt, cur, at, bal, balCur) = m.destructured
            val kind = if (amt.startsWith("-")) Kind.PURCHASE else Kind.INCOME
            return txn(
                sms, "KAPITALBANK", card, kind, amount(amt, cur), merchant,
                LocalDateTime.parse(at, dmyLong), amount(bal, balCur),
            )
        }
        kbDepositOutRe.find(body)?.let { m ->
            val (name, amt, cur, bal, balCur, date) = m.destructured
            return txn(sms, "KAPITALBANK", name, Kind.OWN_TRANSFER, amount(amt, cur), "Вклад $name", dateOnly(date, sms), amount(bal, balCur))
        }
        kbInterestRe.find(body)?.let { m ->
            val (name, amt, cur, date) = m.destructured
            return txn(sms, "KAPITALBANK", name, Kind.INCOME, amount(amt, cur), "Проценты по вкладу $name", dateOnly(date, sms), null)
        }
        return null
    }

    // Po vkladu XX-123 proizvedena operatsiya "Prisoedinenie nachislennyh" na summu 1 234 567.89 sum. Ostatok vklada 50 000 000.00 sum, Data 24.11.2026
    private val asakaDepositRe = Regex(
        "^Po vkladu (\\S+) proizvedena operatsiya \"(.*?)\" na summu (-?[\\d .]+?) (\\w+)\\. Ostatok vklada ([\\d .]+?) (\\w+), Data (\\d\\d\\.\\d\\d\\.\\d{4})",
    )

    private fun asakabank(sms: Sms, body: String): ParseResult? {
        val m = asakaDepositRe.find(body) ?: return null
        val (deposit, op, amt, cur, bal, balCur, date) = m.destructured
        val isInterest = op.contains("nachisl", ignoreCase = true)
        return txn(
            sms, "ASAKABANK", deposit,
            if (isInterest) Kind.INCOME else Kind.OWN_TRANSFER,
            amount(amt, cur),
            if (isInterest) "Проценты по вкладу $deposit" else "Вклад $deposit",
            dateOnly(date, sms), amount(bal, balCur),
        )
    }
}
