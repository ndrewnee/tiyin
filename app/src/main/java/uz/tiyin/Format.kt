package uz.tiyin

import android.content.res.Resources
import uz.tiyin.core.Categories
import uz.tiyin.core.Money
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.util.Locale

sealed interface Period {
    val from: LocalDateTime
    val to: LocalDateTime

    data class Month(val ym: YearMonth) : Period {
        override val from: LocalDateTime get() = ym.atDay(1).atStartOfDay()
        override val to: LocalDateTime get() = ym.plusMonths(1).atDay(1).atStartOfDay()
    }

    data class Year(val year: Int) : Period {
        override val from: LocalDateTime get() = LocalDate.of(year, 1, 1).atStartOfDay()
        override val to: LocalDateTime get() = LocalDate.of(year + 1, 1, 1).atStartOfDay()
    }
}

private fun Resources.month(array: Int, ym: YearMonth) = getStringArray(array)[ym.monthValue - 1]

fun Resources.monthName(ym: YearMonth): String = month(R.array.months, ym)
fun Resources.monthShort(ym: YearMonth): String = month(R.array.months_short, ym)
fun Resources.inMonth(ym: YearMonth): String = month(R.array.months_in, ym)

private val Resources.locale: Locale get() = configuration.locales[0]

fun Resources.dayTitle(day: LocalDate, today: LocalDate): String {
    val date = getString(R.string.day_date, day.dayOfMonth, getStringArray(R.array.months_day)[day.monthValue - 1])
    return when (day) {
        today -> getString(R.string.today, date)
        today.minusDays(1) -> getString(R.string.yesterday, date)
        else -> getString(R.string.weekday_date, getStringArray(R.array.weekdays)[day.dayOfWeek.value - 1], date)
    }
}

fun groupDigits(value: Long): String = "%,d".format(Locale.ROOT, value).replace(',', ' ')

/** The number alone for som, with the symbol for anything else: lists already say they are in som. */
fun amount(m: Money): String {
    val whole = groupDigits(m.minor / 100)
    val cents = "%02d".format(m.minor % 100)
    return when (m.currency) {
        "UZS" -> whole
        "USD" -> if (cents == "00") "\$$whole" else "\$$whole.$cents"
        else -> "$whole.$cents ${m.currency}"
    }
}

fun Resources.millions(minor: Long): String = "%.1f".format(locale, minor / 100 / 1_000_000.0)

/** Short som figure for tight spaces: 6,5 млн, 640 тыс. */
fun Resources.shortSom(minor: Long): String {
    val som = minor / 100
    return when {
        som >= 1_000_000 -> getString(R.string.short_millions, "%.1f".format(locale, som / 1_000_000.0))
        som >= 1_000 -> getString(R.string.short_thousands, som / 1_000)
        else -> "$som"
    }
}

private val categoryLabels = mapOf(
    "Такси" to R.string.cat_taxi,
    "Продукты и доставка" to R.string.cat_groceries,
    "Животные" to R.string.cat_pets,
    "Здоровье" to R.string.cat_health,
    "Путешествия" to R.string.cat_travel,
    "Маркетплейсы" to R.string.cat_marketplaces,
    "Подписки и сервисы" to R.string.cat_subscriptions,
    "Одежда" to R.string.cat_clothes,
    "Дом" to R.string.cat_home,
    "Наличные" to R.string.cat_cash,
    Categories.VIA_PAYME to R.string.cat_via_payme,
    Categories.OTHER to R.string.cat_other,
    Categories.TRANSFERS to R.string.cat_transfers,
    Categories.INCOME to R.string.cat_income,
    Categories.OWN to R.string.cat_own,
    "Кафе и рестораны" to R.string.cat_cafes,
    "Покупки" to R.string.cat_shopping,
    "Техника" to R.string.cat_electronics,
    "Красота" to R.string.cat_beauty,
    "Транспорт" to R.string.cat_transport,
    "Связь" to R.string.cat_phone,
    "Коммунальные" to R.string.cat_utilities,
    "Жильё" to R.string.cat_housing,
    "Спорт и развлечения" to R.string.cat_leisure,
    "Образование" to R.string.cat_education,
    "Авто" to R.string.cat_car,
    "Услуги" to R.string.cat_services,
)

/** Categories are stored under their Russian names; one only Payme knows is shown as Payme named it. */
fun Resources.categoryLabel(category: String): String = categoryLabels[category]?.let(::getString) ?: category
