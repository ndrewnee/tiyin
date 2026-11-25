package uz.tiyin.core

import java.io.File
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ParsersTest {
    private val now = LocalDateTime.of(2026, 11, 24, 12, 0)

    private fun parsed(sender: String, body: String): Txn {
        val r = Parsers.parse(Sms(sender, body, now))
        assertIs<ParseResult.Parsed>(r, "not parsed: $body")
        return r.txn
    }

    @Test
    fun humoPurchase() {
        val t = parsed("HUMO", "HUMOCARD *1111: oplata 25500.00 UZS; YandexGO Taxi UB OPL; 26-11-24 09:10;  Dostupno: 100000.00 UZS")
        assertEquals(Kind.PURCHASE, t.kind)
        assertEquals(Money(2_550_000, "UZS"), t.amount)
        assertEquals("YandexGO Taxi UB OPL", t.merchant)
        assertEquals(LocalDateTime.of(2026, 11, 24, 9, 10), t.at)
        assertEquals(Money(10_000_000, "UZS"), t.balance)
        assertEquals("Такси", Categories.categorize(t))
    }

    @Test
    fun humoMerchantCutAtCity() {
        val t = parsed("HUMO", "HUMOCARD *1111: oplata 716000.00 UZS; ZOO DOCTOR XK>TASHKE; 26-11-23 10:51;  Dostupno: 100000.00 UZS")
        assertEquals("ZOO DOCTOR XK", t.merchant)
        assertEquals("Животные", Categories.categorize(t))
    }

    @Test
    fun humoTransfers() {
        assertEquals(Kind.TRANSFER_OUT, parsed("HUMO", "HUMOCARD *1111: oplata 200000.00 UZS; PAYME P2P HUMO NA UZ; 26-11-16 14:40; Dostupno: 100000.00 UZS").kind)
        assertEquals(Kind.TRANSFER_OUT, parsed("HUMO", "HUMOCARD *1111: operacija 500000.00 UZS; PAYME P2P H2H>TASHKE; 26-11-20 10:27; Dostupno: 100000.00 UZS").kind)
        assertEquals(Kind.TRANSFER_OUT, parsed("HUMO", "HUMOCARD *1111: oplata 3000000.00 UZS; ASAKABANK HUMO UZCAR; 26-11-18 23:10; Dostupno: 100000.00 UZS").kind)
        assertEquals(Kind.OWN_TRANSFER, parsed("HUMO", "HUMOCARD *1111: popolnenie 1198000.00 UZS; VISA USD KB to HUMO; 26-11-15 17:10; Dostupno: 100000.00 UZS").kind)
        assertEquals(Kind.INCOME, parsed("HUMO", "HUMOCARD *1111: popolnenie 650.00 UZS; YANDEX LAVKA POPOL K; 26-11-18 15:23; Dostupno: 100000.00 UZS").kind)
    }

    @Test
    fun humoPaymeHasNoMerchant() {
        val t = parsed("HUMO", "HUMOCARD *1111: oplata 1500000.00 UZS; PAYME OPLATA QR>TASH; 26-11-21 10:00; Dostupno: 100000.00 UZS")
        assertEquals(Kind.PURCHASE, t.kind)
        assertEquals(Categories.VIA_PAYME, Categories.categorize(t))
    }

    @Test
    fun uzcardPurchase() {
        val t = parsed("UZCARD", "Xarid: ZOO DOCTOR XK, TASHKENT, MIRABAD 33 22.11.26 14:21 karta ***2222. Miqdor:295000.00 UZS, Qoldiq:100000.00 UZS")
        assertEquals(Kind.PURCHASE, t.kind)
        assertEquals("ZOO DOCTOR XK", t.merchant)
        assertEquals("2222", t.account)
        assertEquals(LocalDateTime.of(2026, 11, 22, 14, 21), t.at)
        assertEquals(Money(29_500_000, "UZS"), t.amount)
    }

    @Test
    fun kapitalbankCard() {
        val out = parsed("kapitalbank", "Snyatiye, 15.11.26 v 17:10. Karta (*3333). Summa: 100.00 USD. VISA MC USD 2 HUMO KB>Tashkent UZ. Dostupno: 100000.00 USD")
        assertEquals(Kind.OWN_TRANSFER, out.kind)
        assertEquals(Money(10_000, "USD"), out.amount)
        assertEquals(LocalDateTime.of(2026, 11, 15, 17, 10), out.at)

        val fromDeposit = parsed("kapitalbank", "Popolneniye, 18.11.26 v 09:09. Karta (*3333). Summa: 500.00 USD. DEP USD 2 VISA MC USD>Tashkent UZ. Dostupno: 100000.00 USD")
        assertEquals(Kind.OWN_TRANSFER, fromDeposit.kind)

        val buy = parsed("kapitalbank", "Karta *3333. Xarid/Pokupka \"ANTHROPIC* CLAUDE SU\", -200.00, USD, \"19-11-2026 15:05\". Dostupno: 100.00, USD.")
        assertEquals(Kind.PURCHASE, buy.kind)
        assertEquals(Money(20_000, "USD"), buy.amount)
        assertEquals("Подписки и сервисы", Categories.categorize(buy))
    }

    @Test
    fun kapitalbankShortNumber() {
        val body = "Snyatiye, 15.11.26 v 14:32. Karta (*3333). Summa: 100.00 USD. VISA MC USD 2 HUMO KB>Tashkent UZ. Dostupno: 534.73 USD"
        val out = parsed("2212", body)
        assertEquals("KAPITALBANK", out.bank)
        assertEquals(Kind.OWN_TRANSFER, out.kind)

        val at = LocalDateTime.of(2026, 11, 15, 14, 33)
        val both = Ledger(listOf(Sms("2212", body, at), Sms("kapitalbank", body, at.plusMinutes(1))))
        assertEquals(1, both.transactions.size)
    }

    @Test
    fun kapitalbankDeposits() {
        val out = parsed("kapitalbank", "S vklada 'Test vklad' spisano 500.00 USD. Ostatok - 1000.00 USD. 18.11.2026.")
        assertEquals(Kind.OWN_TRANSFER, out.kind)
        val interest = parsed("kapitalbank", "Vyplata % po vkladu 'Test vklad' - 10.00 USD. Ostatok na schete 20200000000000000001 - 10.00 USD. 19.11.2026")
        assertEquals(Kind.INCOME, interest.kind)
        assertEquals(Money(1_000, "USD"), interest.amount)

        val uz = Parsers.parse(Sms("kapitalbank", "'Test vklad' omonatidan 500.00 USD yechildi. Qoldiq - 1000.00 USD. 18.11.2026.", now))
        assertIs<ParseResult.Ignored>(uz)
    }

    @Test
    fun asakabankDeposit() {
        val t = parsed("ASAKABANK", "Po vkladu XX-908 proizvedena operatsiya \"Prisoedinenie nachislennyh\" na summu 1 234 567.89 sum. Ostatok vklada 50 000 000.00 sum, Data 24.11.2026")
        assertEquals(Kind.INCOME, t.kind)
        assertEquals(Money(123_456_789, "UZS"), t.amount)
        assertEquals(now, t.at)
    }

    @Test
    fun depositToCardIsOwnTransfer() {
        val out = parsed("ASAKABANK", "Po vkladu XX-1960 proizvedena operatsiya \"Rashod beznalichnymi(Digit\" na summu -1 000 000.00 sum. Ostatok vklada 2 000 000.00 sum, Data 24.11.2026")
        assertEquals(Kind.OWN_TRANSFER, out.kind)
        assertEquals(Money(100_000_000, "UZS"), out.amount)

        val toCard = parsed("HUMO", "HUMOCARD *4444: popolnenie 1000000.00 UZS; ASAKABANK OMONAT>Tas; 26-11-24 15:40;  Dostupno: 100000.00 UZS")
        assertEquals(Kind.OWN_TRANSFER, toCard.kind)
    }

    @Test
    fun codesAndStrangersAreIgnored() {
        assertIs<ParseResult.Ignored>(Parsers.parse(Sms("Payme", "Payme: kod 123456. Hech kimga bermang", now)))
        assertIs<ParseResult.Ignored>(Parsers.parse(Sms("Beeline", "Balans 1000 sum", now)))
        assertIs<ParseResult.Unparsed>(Parsers.parse(Sms("HUMO", "Yangi aksiya! HUMO bilan cashback", now)))
    }

    @Test
    fun onlyBankSmsWithoutCodesAreStored() {
        assertTrue(Parsers.shouldStore(Sms("HUMO", "HUMOCARD *1111: oplata 5000.00 UZS; CODE CAFE; 26-11-19 09:03; Dostupno: 100000.00 UZS", now)))
        assertTrue(Parsers.shouldStore(Sms("HUMO", "Yangi aksiya! HUMO bilan cashback", now)))
        kotlin.test.assertFalse(Parsers.shouldStore(Sms("Payme", "Payme: kod 123456. Hech kimga bermang", now)))
        kotlin.test.assertFalse(Parsers.shouldStore(Sms("Beeline", "Balans 1000 sum", now)))
    }

    @Test
    fun duplicatesWithinMinutesCollapse() {
        val body = "HUMOCARD *1111: oplata 39000.00 UZS; YandexGO Taxi UB OPL; 26-11-23 22:16; Dostupno: 100000.00 UZS"
        val ledger = Ledger(listOf(Sms("HUMO", body, now), Sms("HUMO", body, now.plusSeconds(30)), Sms("humo", "$body ", now.plusHours(2))))
        assertEquals(1, ledger.transactions.size)
    }

    /** Runs over a local export of real SMS (sender<TAB>body per line) when TIYIN_SAMPLES points to one. */
    @Test
    fun realSamplesAllParse() {
        val path = System.getenv("TIYIN_SAMPLES") ?: return
        val sms = File(path).readLines().filter { it.isNotBlank() }.map {
            val (sender, body) = it.split("\t", limit = 2)
            Sms(sender, body, now)
        }
        val failed = sms.filter { Parsers.parse(it) is ParseResult.Unparsed }
        assertTrue(failed.isEmpty(), "unparsed:\n" + failed.joinToString("\n") { "${it.sender}: ${it.body}" })
    }
}
