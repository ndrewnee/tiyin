package uz.tiyin

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import android.content.res.Resources
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import uz.tiyin.core.Categories
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import uz.tiyin.core.Kind
import uz.tiyin.core.Ledger
import uz.tiyin.core.Money
import uz.tiyin.core.Txn
import java.time.LocalDate
import java.time.YearMonth

private val numeric = TextStyle(fontFamily = Jost, fontFeatureSettings = "tnum")

/** Resources that recompose when the app language changes. */
@Composable
@ReadOnlyComposable
fun resources(): Resources {
    LocalConfiguration.current
    return LocalContext.current.resources
}

private fun chevron(points: List<Pair<Float, Float>>) = ImageVector.Builder(
    defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f,
).path(stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
    moveTo(points[0].first, points[0].second)
    points.drop(1).forEach { (x, y) -> lineTo(x, y) }
}.build()

private val ChevronLeft = chevron(listOf(15f to 5f, 8f to 12f, 15f to 19f))
private val ChevronRight = chevron(listOf(9f to 5f, 16f to 12f, 9f to 19f))
private val ChevronDown = chevron(listOf(7f to 10f, 12f to 15f, 17f to 10f))

@Composable
private fun PeriodHeader(title: String, prevLabel: String, nextLabel: String, hasNext: Boolean, onTitle: () -> Unit, onPrev: () -> Unit, onNext: () -> Unit) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClickLabel = stringResource(R.string.pick_period), onClick = onTitle)
                .heightIn(min = 48.dp).padding(end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, Modifier.semantics { heading() }, fontFamily = Jost, fontSize = 34.sp, fontWeight = FontWeight.Medium, color = p.ink)
            Icon(ChevronDown, null, Modifier.size(24.dp), tint = p.ink)
        }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onPrev) { Icon(ChevronLeft, prevLabel, tint = p.ink) }
        IconButton(onClick = onNext, enabled = hasNext, colors = IconButtonDefaults.iconButtonColors(disabledContentColor = p.faint)) {
            Icon(ChevronRight, nextLabel, tint = if (hasNext) p.ink else p.faint)
        }
    }
}

@Composable
private fun BigTotal(som: Long, caption: String, otherCurrencies: String) {
    val p = LocalPalette.current
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(groupDigits(som / 100), Modifier.alignByBaseline(), style = numeric, fontSize = 52.sp, lineHeight = 52.sp, fontWeight = FontWeight.Medium, color = p.ink)
        Text(stringResource(R.string.som), Modifier.alignByBaseline(), fontFamily = Golos, fontSize = 18.sp, color = p.muted)
    }
    if (otherCurrencies.isNotEmpty()) {
        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("+ $otherCurrencies", Modifier.alignByBaseline(), style = numeric, fontSize = 32.sp, lineHeight = 36.sp, fontWeight = FontWeight.Medium, color = p.ink)
            Text(stringResource(R.string.foreign_card), Modifier.alignByBaseline(), fontFamily = Golos, fontSize = 15.sp, color = p.muted)
        }
    }
    Text(caption, Modifier.padding(top = 8.dp), fontFamily = Golos, fontSize = 15.sp, lineHeight = 22.sp, color = p.muted)
}

/** A prompt where the import helps: a month whose spending hides behind Payme, or a month without SMS. */
@Composable
private fun PaymePrompt(text: String, onImport: () -> Unit) {
    val p = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().padding(top = 20.dp).clip(RoundedCornerShape(4.dp)).background(p.track)
            .clickable(role = Role.Button, onClick = onImport).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(4.dp).height(40.dp).clip(RoundedCornerShape(2.dp)).background(p.dye(Categories.VIA_PAYME)))
        Column(Modifier.padding(start = 12.dp)) {
            Text(text, fontFamily = Golos, fontSize = 14.sp, lineHeight = 20.sp, color = p.muted)
            Text(
                stringResource(R.string.import_payme), Modifier.padding(top = 4.dp),
                fontFamily = Golos, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = p.ink, textDecoration = TextDecoration.Underline,
            )
        }
    }
}

/** [onImport] makes the "paid through Payme" row open the export picker, since only an export can name those shops. */
private fun LazyListScope.categoryRows(rows: List<Ledger.CategoryTotal>, onImport: (() -> Unit)? = null) {
    items(rows, key = { "cat-${it.category}-${it.total.currency}" }) { c ->
        val p = LocalPalette.current
        val importable = onImport != null && c.category == Categories.VIA_PAYME
        val tap = if (importable) Modifier.clickable(onClickLabel = stringResource(R.string.import_payme), role = Role.Button, onClick = onImport!!) else Modifier
        Row(Modifier.fillMaxWidth().heightIn(min = 42.dp).then(tap), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(14.dp, 22.dp).background(p.dye(c.category)))
            Text(
                resources().categoryLabel(c.category), Modifier.weight(1f).padding(start = 12.dp, end = 6.dp),
                fontFamily = Golos, fontSize = 16.sp, color = p.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
                textDecoration = if (importable) TextDecoration.Underline else null,
            )
            Text("${c.count}", Modifier.padding(end = 12.dp), fontFamily = Golos, fontSize = 14.sp, color = p.muted)
            Text(amount(c.total), style = numeric, fontSize = 18.sp, fontWeight = FontWeight.Medium, color = p.ink)
        }
    }
}

private fun threadsOf(rows: List<Ledger.CategoryTotal>, p: Palette) = rows.map { Strand(it.total.minor.toFloat(), p.dye(it.category)) }

private fun sumOf(totals: Map<String, List<Ledger.CategoryTotal>>, currency: String) =
    totals[currency]?.sumOf { it.total.minor } ?: 0L

private fun otherCurrencies(totals: Map<String, List<Ledger.CategoryTotal>>, and: String): String =
    totals.filterKeys { it != "UZS" }.map { (cur, cats) -> amount(Money(cats.sumOf { it.total.minor }, cur)) }.joinToString(" $and ")

@Composable
fun MonthScreen(
    ledger: Ledger, ym: YearMonth, today: LocalDate,
    onPick: () -> Unit, onMonth: (YearMonth) -> Unit, onImport: () -> Unit, onTxn: (Txn) -> Unit,
) {
    val p = LocalPalette.current
    val res = resources()
    val d = remember(ledger, ym) { MonthData(ledger, ym) }
    val spend = d.spend
    val sent = d.sent
    val came = d.came
    val uzs = spend["UZS"].orEmpty()
    val others = otherCurrencies(spend, res.getString(R.string.and))
    val isCurrent = ym == YearMonth.from(today)
    var showUnparsed by remember { mutableStateOf(false) }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        item {
            PeriodHeader(
                title = res.monthName(ym) + if (ym.year != today.year) " ${ym.year}" else "",
                prevLabel = res.monthName(ym.minusMonths(1)),
                nextLabel = res.monthName(ym.plusMonths(1)),
                hasNext = !isCurrent, onTitle = onPick,
                onPrev = { onMonth(ym.minusMonths(1)) }, onNext = { onMonth(ym.plusMonths(1)) },
            )
            if (uzs.isEmpty()) {
                Box(Modifier.fillMaxWidth().height(132.dp).background(p.track))
            } else {
                IkatCloth(
                    threadsOf(uzs, p),
                    Modifier.fillMaxWidth().height(132.dp).clip(RoundedCornerShape(3.dp))
                        .semantics { contentDescription = res.getString(R.string.cloth_desc, res.inMonth(ym)) },
                )
            }
            Spacer(Modifier.height(24.dp))
            val empty = uzs.isEmpty() && others.isEmpty()
            val caption = res.getString(if (empty) R.string.nothing_spent_in else R.string.spent_in, res.inMonth(ym))
            BigTotal(sumOf(spend, "UZS"), caption, others)
            Row(Modifier.fillMaxWidth().padding(top = 20.dp)) {
                Figure(stringResource(R.string.sent_to_people), groupDigits(sumOf(sent, "UZS") / 100), p.ink, Modifier.weight(1f))
                val inc = sumOf(came, "UZS")
                Figure(stringResource(R.string.came_in), if (inc > 0) "+" + groupDigits(inc / 100) else "0", if (inc > 0) p.income else p.ink, Modifier.weight(1f))
            }
            if (d.days.isEmpty()) PaymePrompt(res.getString(R.string.no_sms_month), onImport)
            Spacer(Modifier.height(24.dp))
        }
        categoryRows(spend.values.flatten(), onImport)
        d.days.forEach { (day, list) ->
            item(key = "day-$day") {
                Text(
                    res.dayTitle(day, today), Modifier.padding(top = 32.dp, bottom = 6.dp).semantics { heading() },
                    fontFamily = Golos, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = p.ink,
                )
            }
            items(list, key = { "t-${it.key}" }) { TxnRow(it) { onTxn(it) } }
        }
        if (ledger.unparsed.isNotEmpty()) {
            item {
                Text(
                    stringResource(R.string.unparsed, ledger.unparsed.size),
                    Modifier.padding(top = 28.dp).clickable { showUnparsed = !showUnparsed }.padding(vertical = 8.dp),
                    fontFamily = Golos, fontSize = 14.sp, color = p.muted,
                )
            }
            if (showUnparsed) items(ledger.unparsed) { s ->
                Text("${s.sender}: ${s.body}", Modifier.padding(bottom = 8.dp), fontFamily = Golos, fontSize = 13.sp, color = p.muted)
            }
        }
        item { Spacer(Modifier.height(40.dp)) }
    }
}

@Composable
private fun Figure(label: String, value: String, color: Color, modifier: Modifier) {
    val p = LocalPalette.current
    Column(modifier) {
        Text(label, fontFamily = Golos, fontSize = 13.sp, color = p.muted)
        Text(value, style = numeric, fontSize = 20.sp, fontWeight = FontWeight.Medium, color = color)
    }
}

@Composable
private fun TxnRow(t: Txn, onClick: () -> Unit) {
    val p = LocalPalette.current
    val thread = when (t.kind) {
        Kind.PURCHASE -> p.dye(t.category)
        Kind.TRANSFER_OUT -> p.ink
        Kind.INCOME -> p.income
        Kind.OWN_TRANSFER -> p.faint
    }
    val res = resources()
    val meta = when (t.kind) {
        Kind.PURCHASE -> "${res.categoryLabel(t.category)}, ${t.bank}"
        Kind.TRANSFER_OUT -> res.getString(R.string.sent_meta, t.bank)
        Kind.INCOME -> res.getString(R.string.came_meta, t.bank)
        Kind.OWN_TRANSFER -> res.getString(R.string.cat_own)
    } + if (t.markedBy != null) ", " + res.getString(R.string.mark_manual) else ""
    val own = t.kind == Kind.OWN_TRANSFER
    val value = (if (t.kind == Kind.INCOME) "+" else "") + amount(t.amount)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 58.dp).clickable(onClickLabel = stringResource(R.string.edit), onClick = onClick).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(4.dp).height(42.dp).clip(RoundedCornerShape(2.dp)).background(thread))
        Text(
            "%02d:%02d".format(t.at.hour, t.at.minute), Modifier.padding(start = 12.dp).width(46.dp),
            style = numeric, fontSize = 15.sp, color = p.muted,
        )
        Column(Modifier.weight(1f).padding(end = 10.dp)) {
            Text(t.merchant, fontFamily = Golos, fontSize = 16.sp, color = if (own) p.muted else p.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(meta, fontFamily = Golos, fontSize = 13.sp, color = p.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(
            value, style = numeric, fontSize = 18.sp, fontWeight = FontWeight.Medium,
            color = when (t.kind) { Kind.INCOME -> p.income; Kind.OWN_TRANSFER -> p.muted; else -> p.ink },
        )
    }
}

/** Everything the month screen derives from the ledger, computed once per ledger and month rather than per frame. */
private class MonthData(ledger: Ledger, ym: YearMonth) {
    private val txns = Period.Month(ym).let { ledger.between(it.from, it.to) }
    val spend = Ledger.totals(txns, setOf(Kind.PURCHASE))
    val sent = Ledger.totals(txns, setOf(Kind.TRANSFER_OUT))
    val came = Ledger.totals(txns, setOf(Kind.INCOME))
    val days = txns.groupBy { it.at.toLocalDate() }
}

class MonthSummary(val ym: YearMonth, val som: Long, val threads: List<Strand>)

fun monthSummaries(ledger: Ledger, year: Int, p: Palette): List<MonthSummary> = (1..12).map { m ->
    val period = Period.Month(YearMonth.of(year, m))
    val uzs = Ledger.totals(ledger.between(period.from, period.to), setOf(Kind.PURCHASE))["UZS"].orEmpty()
    MonthSummary(period.ym, uzs.sumOf { it.total.minor }, threadsOf(uzs, p))
}

@Composable
fun YearScreen(ledger: Ledger, year: Int, today: LocalDate, onPick: () -> Unit, onYear: (Int) -> Unit, onMonth: (YearMonth) -> Unit) {
    val p = LocalPalette.current
    val res = resources()
    val spend = remember(ledger, year) { Period.Year(year).let { Ledger.totals(ledger.between(it.from, it.to), setOf(Kind.PURCHASE)) } }
    val months = remember(ledger, year, p) { monthSummaries(ledger, year, p) }
    val withData = months.filter { it.som > 0 }
    val max = months.maxOf { it.som }.coerceAtLeast(1)
    val total = sumOf(spend, "UZS")
    val others = otherCurrencies(spend, res.getString(R.string.and))

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        item {
            PeriodHeader(
                title = "$year", prevLabel = res.getString(R.string.year_n, year - 1), nextLabel = res.getString(R.string.year_n, year + 1),
                hasNext = year < today.year, onTitle = onPick,
                onPrev = { onYear(year - 1) }, onNext = { onYear(year + 1) },
            )
            val caption = if (withData.size >= 2) res.getString(R.string.spent_year_avg, year, res.shortSom(total / withData.size))
            else res.getString(R.string.spent_year, year)
            BigTotal(total, caption, others)
            Spacer(Modifier.height(28.dp))
        }
        items(months, key = { "m-${it.ym}" }) { m ->
            val has = m.som > 0
            Row(
                Modifier.fillMaxWidth().heightIn(min = 34.dp).clickable(enabled = has, onClickLabel = res.getString(R.string.open_item, res.monthName(m.ym))) { onMonth(m.ym) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(res.monthShort(m.ym), Modifier.width(40.dp), fontFamily = Golos, fontSize = 14.sp, color = if (has) p.ink else p.muted)
                Box(Modifier.weight(1f).height(22.dp), contentAlignment = Alignment.CenterStart) {
                    if (has) ClothSwatch(m.threads, p.track, Modifier.fillMaxWidth(m.som.toFloat() / max).fillMaxHeight())
                }
                Text(if (has) res.millions(m.som) else "", Modifier.width(52.dp), style = numeric, fontSize = 15.sp, color = p.ink, textAlign = TextAlign.End)
            }
        }
        item {
            Text(stringResource(R.string.year_footnote), Modifier.padding(start = 40.dp, top = 8.dp, bottom = 28.dp), fontFamily = Golos, fontSize = 13.sp, color = p.muted)
        }
        categoryRows(spend.values.flatten())
        item { Spacer(Modifier.height(40.dp)) }
    }
}
