package uz.tiyin

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

val monthNames = listOf("Январь", "Февраль", "Март", "Апрель", "Май", "Июнь", "Июль", "Август", "Сентябрь", "Октябрь", "Ноябрь", "Декабрь")
val monthShort = listOf("янв", "фев", "мар", "апр", "май", "июн", "июл", "авг", "сен", "окт", "ноя", "дек")
private val monthGenitive = listOf("января", "февраля", "марта", "апреля", "мая", "июня", "июля", "августа", "сентября", "октября", "ноября", "декабря")
private val monthPrepositional = listOf("январе", "феврале", "марте", "апреле", "мае", "июне", "июле", "августе", "сентябре", "октябре", "ноябре", "декабре")
private val weekdays = listOf("понедельник", "вторник", "среда", "четверг", "пятница", "суббота", "воскресенье")

fun inMonth(ym: YearMonth) = "в " + monthPrepositional[ym.monthValue - 1]

fun dayTitle(day: LocalDate, today: LocalDate): String {
    val date = "${day.dayOfMonth} ${monthGenitive[day.monthValue - 1]}"
    return when (day) {
        today -> "Сегодня, $date"
        today.minusDays(1) -> "Вчера, $date"
        else -> "${weekdays[day.dayOfWeek.value - 1].replaceFirstChar { it.uppercase() }}, $date"
    }
}

fun groupDigits(value: Long): String = "%,d".format(Locale.ROOT, value).replace(',', ' ')

/** The number alone for som, with the symbol for anything else: lists already say they are in som. */
fun amount(m: Money): String {
    val whole = groupDigits(m.minor / 100)
    val cents = "%02d".format(m.minor % 100)
    return when (m.currency) {
        "UZS" -> whole
        "USD" -> if (cents == "00") "$$whole" else "$$whole.$cents"
        else -> "$whole.$cents ${m.currency}"
    }
}

fun money(m: Money): String = if (m.currency == "UZS") "${amount(m)} сум" else amount(m)

fun millions(minor: Long): String = "%.1f".format(Locale.ROOT, minor / 100 / 1_000_000.0).replace('.', ',')

/** Short som figure for tight spaces: 6,5 млн · 640 тыс. */
fun shortSom(minor: Long): String {
    val som = minor / 100
    return when {
        som >= 1_000_000 -> "%.1f млн".format(Locale.ROOT, som / 1_000_000.0).replace('.', ',')
        som >= 1_000 -> "${som / 1_000} тыс"
        else -> "$som"
    }
}
