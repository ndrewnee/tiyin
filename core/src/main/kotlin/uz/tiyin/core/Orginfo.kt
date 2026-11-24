package uz.tiyin.core

import java.io.IOException
import java.net.URLEncoder

/**
 * Finds the company behind a card-terminal name in the Uzbek company register (orginfo.uz)
 * and maps its activity code (OKED) to a spending category. Only the merchant name is sent.
 */
class Orginfo(private val fetch: (url: String) -> String) {

    sealed interface Result {
        data class Found(val orgName: String, val oked: String, val category: String) : Result
        data object NotFound : Result
    }

    /**
     * Throws [IOException] when the site cannot be reached or its pages no longer parse, so the caller
     * retries later instead of remembering every merchant as unknown.
     */
    fun lookup(merchant: String): Result {
        val wanted = MerchantNames.normalize(merchant)
        if (wanted.length < 3) return Result.NotFound
        val q = URLEncoder.encode(wanted, "UTF-8")
        val ids = searchResultIds(fetch("$BASE/ru/search/all/?q=$q")).take(MAX_CANDIDATES)
        var parsed = 0
        for (id in ids) {
            val org = parseOrganization(fetch("$BASE/ru/organization/$id/")) ?: continue
            parsed++
            if (!MerchantNames.matches(wanted, org.shortName)) continue
            val category = Oked.category(org.oked) ?: continue
            return Result.Found(org.shortName, org.oked, category)
        }
        if (ids.isNotEmpty() && parsed == 0) throw IOException("orginfo.uz organization page layout not recognised")
        return Result.NotFound
    }

    data class Organization(val shortName: String, val oked: String)

    companion object {
        const val BASE = "https://orginfo.uz"
        private const val MAX_CANDIDATES = 4
        private val orgLink = Regex("href=\"/ru/organization/([0-9a-f]{8,})/\"")

        fun searchResultIds(html: String): List<String> =
            orgLink.findAll(html).map { it.groupValues[1] }.distinct().toList()

        fun parseOrganization(html: String): Organization? {
            val lines = textLines(html)
            val name = valueAfter(lines, "Краткое название организации") ?: return null
            val okedLine = valueAfter(lines, "ОКЭД") ?: return null
            val code = Regex("^(\\d{4,5})").find(okedLine)?.groupValues?.get(1) ?: return null
            return Organization(name, code)
        }

        private fun valueAfter(lines: List<String>, label: String): String? {
            val i = lines.indexOfFirst { it == label }
            return if (i >= 0) lines.getOrNull(i + 1) else null
        }

        private fun textLines(html: String): List<String> {
            val noScripts = html.replace(Regex("(?s)<(script|style)\\b.*?</\\1>"), "")
            return noScripts.replace(Regex("<[^>]+>"), "\n")
                .replace("&quot;", "\"").replace("&amp;", "&").replace("&#39;", "'").replace("&nbsp;", " ")
                .lines().map { it.trim() }.filter { it.isNotEmpty() }
        }
    }
}

object MerchantNames {
    // Java's \b only knows ASCII word characters, so Cyrillic "ООО" needs explicit letter boundaries.
    private val legalForms = Regex(
        "(?<![\\p{L}\\p{N}])(OOO|ООО|MCHJ|LLC|IP|ИП|XK|ЧП|ЧК|AJ|АО|AO|АЖ|ATB|QK|OAJ|ОАО|ЗАО|СП|YATT|XUSUSIY KORXONA|MAS ULIYATI CHEKLANGAN JAMIYATI)(?![\\p{L}\\p{N}])",
    )

    /** "OOO LARGE BRAND" and ООО "LARGE BRAND" both become "LARGE BRAND". */
    fun normalize(name: String): String =
        name.uppercase()
            .replace(Regex("[\"'`‘’«»“”]"), " ")
            .replace(Regex("\\s+"), " ")
            .replace(legalForms, " ")
            .replace(Regex("[^\\p{L}\\p{N}&+ -]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    /** Terminal names are cut at 20 characters, so a long name may be a prefix of the registered one. */
    fun matches(wantedNormalized: String, registered: String): Boolean {
        val reg = normalize(registered)
        return reg == wantedNormalized || (wantedNormalized.length >= 12 && reg.startsWith(wantedNormalized))
    }
}

object Oked {
    private val exact = mapOf(
        "47730" to "Здоровье",
        "47740" to "Здоровье",
        "47710" to "Одежда",
        "47720" to "Одежда",
        "47760" to "Животные",
        "47300" to "Авто",
        "47910" to "Маркетплейсы",
        "47410" to "Техника",
        "47420" to "Техника",
        "47430" to "Техника",
        "47540" to "Техника",
        "47510" to "Дом",
        "47520" to "Дом",
        "47530" to "Дом",
        "47590" to "Дом",
        "49320" to "Такси",
        "96020" to "Красота",
        "75000" to "Животные",
    )

    private val byDivision = mapOf(
        "47" to "Покупки",
        "56" to "Кафе и рестораны",
        "10" to "Продукты и доставка",
        "86" to "Здоровье",
        "87" to "Здоровье",
        "49" to "Транспорт",
        "51" to "Путешествия",
        "55" to "Путешествия",
        "79" to "Путешествия",
        "85" to "Образование",
        "93" to "Спорт и развлечения",
        "90" to "Спорт и развлечения",
        "59" to "Спорт и развлечения",
        "96" to "Услуги",
        "95" to "Услуги",
        "45" to "Авто",
        "61" to "Связь",
        "35" to "Коммунальные",
        "36" to "Коммунальные",
        "68" to "Жильё",
        "46" to "Покупки",
    )

    fun category(code: String): String? {
        val c = code.padEnd(5, '0')
        exact[c]?.let { return it }
        if (c.startsWith("471") || c.startsWith("472")) return "Продукты и доставка"
        return byDivision[c.take(2)]
    }
}
