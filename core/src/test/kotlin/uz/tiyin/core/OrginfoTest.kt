package uz.tiyin.core

import java.io.File
import java.io.IOException
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OrginfoTest {
    private fun orgPage(shortName: String, oked: String, okedName: String) = """
        <html><head><script>var x = "<b>ОКЭД</b>";</script></head><body>
        <div><span>Краткое название организации</span><p>$shortName</p></div>
        <div><span>ОКЭД</span><p>$oked - <br>$okedName</p></div>
        </body></html>
    """.trimIndent()

    private fun searchPage(vararg ids: String) =
        ids.joinToString("") { "<a href=\"/ru/organization/$it/\">org</a>" }

    @Test
    fun normalizesTerminalAndRegisteredNames() {
        assertEquals("LARGE BRAND", MerchantNames.normalize("OOO LARGE BRAND"))
        assertEquals("LARGE BRAND", MerchantNames.normalize("ООО \"LARGE BRAND\""))
        assertEquals("MOMSSENSE", MerchantNames.normalize("MOMSSENSE MCHJ"))
        assertEquals("ANGLESEY FOOD", MerchantNames.normalize("IP OOO ANGLESEY FOOD"))
    }

    @Test
    fun picksTheExactCompanyAmongCandidates() {
        val pages = mapOf(
            "${Orginfo.BASE}/ru/organization/aaaaaaaaaaaa/" to orgPage("ООО &quot;LARGO GRAND CAR-SERVICE&quot;", "45201", "Ремонт автомобилей"),
            "${Orginfo.BASE}/ru/organization/bbbbbbbbbbbb/" to orgPage("ООО &quot;LARGE BRAND&quot;", "47780", "Розничная торговля прочими товарами"),
        )
        val orginfo = Orginfo { url ->
            if ("/search/" in url) {
                assertTrue("q=LARGE+BRAND" in url, url)
                searchPage("aaaaaaaaaaaa", "bbbbbbbbbbbb")
            } else pages.getValue(url)
        }
        assertEquals(Orginfo.Result.Found("ООО \"LARGE BRAND\"", "47780", "Покупки"), orginfo.lookup("OOO LARGE BRAND"))
    }

    @Test
    fun truncatedTerminalNameMatchesByPrefix() {
        val orginfo = Orginfo { url ->
            if ("/search/" in url) searchPage("cccccccccccc")
            else orgPage("АО &quot;UZBEKISTAN AIRWAYS SERVICE&quot;", "51100", "Пассажирский воздушный транспорт")
        }
        val r = orginfo.lookup("UZBEKISTAN AIRWAYS S")
        assertEquals("Путешествия", (r as Orginfo.Result.Found).category)
    }

    @Test
    fun noExactMatchIsNotFound() {
        val orginfo = Orginfo { url ->
            if ("/search/" in url) searchPage("dddddddddddd") else orgPage("ООО &quot;BRAND HOUSE&quot;", "47780", "x")
        }
        assertEquals(Orginfo.Result.NotFound, orginfo.lookup("OOO LARGE BRAND"))
    }

    @Test
    fun networkErrorsPropagate() {
        val orginfo = Orginfo { throw IOException("offline") }
        assertFailsWith<IOException> { orginfo.lookup("OOO LARGE BRAND") }
    }

    @Test
    fun changedPageLayoutIsAnErrorNotAMiss() {
        val orginfo = Orginfo { url -> if ("/search/" in url) searchPage("eeeeeeeeeeee") else "<html>new design</html>" }
        assertFailsWith<IOException> { orginfo.lookup("OOO LARGE BRAND") }
        assertEquals(Orginfo.Result.NotFound, Orginfo { "<html>no results</html>" }.lookup("OOO LARGE BRAND"))
    }

    @Test
    fun okedCodesMapToCategories() {
        assertEquals("Кафе и рестораны", Oked.category("56100"))
        assertEquals("Здоровье", Oked.category("86100"))
        assertEquals("Здоровье", Oked.category("47730"))
        assertEquals("Продукты и доставка", Oked.category("47110"))
        assertEquals("Покупки", Oked.category("47780"))
        assertEquals(null, Oked.category("01110"))
    }

    @Test
    fun lookedUpCategoryReplacesOther() {
        val now = LocalDateTime.of(2026, 11, 24, 12, 0)
        val sms = Sms("HUMO", "HUMOCARD *1111: oplata 173000.00 UZS; OOO MEGACAPITAL>Tash; 26-11-23 21:06; Dostupno: 1.00 UZS", now)
        assertEquals(setOf("OOO MEGACAPITAL"), Ledger(listOf(sms)).unknownMerchants)
        val ledger = Ledger(listOf(sms), mapOf("OOO MEGACAPITAL" to "Кафе и рестораны"))
        assertEquals("Кафе и рестораны", ledger.transactions.single().category)
        assertTrue(ledger.unknownMerchants.isEmpty())
    }

    /** Parses real pages saved from orginfo.uz when TIYIN_ORGINFO_DIR points to them. */
    @Test
    fun realPagesParse() {
        val dir = System.getenv("TIYIN_ORGINFO_DIR")?.let(::File) ?: return
        val megacapital = Orginfo.parseOrganization(File(dir, "o1.html").readText())
        assertEquals(Orginfo.Organization("ООО \"MEGACAPITAL\"", "56100"), megacapital)
        assertEquals(setOf("6dde9e20144f", "c28924809b9f", "e55f954efe1c"), Orginfo.searchResultIds(File(dir, "s_LARGEBRAND.html").readText()).toSet())
    }
}
