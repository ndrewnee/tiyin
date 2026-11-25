package uz.tiyin

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import uz.tiyin.core.Categories
import uz.tiyin.core.Kind
import uz.tiyin.core.Ledger
import uz.tiyin.core.Mark
import uz.tiyin.core.MarkScope
import uz.tiyin.core.Marks
import uz.tiyin.core.Txn
import java.time.LocalDate

/** A change to store: put [mark] on [target] (or only clear, when null) and drop the marks on [cleared]. */
class MarkChange(val target: String, val mark: Mark?, val cleared: List<String>)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MarkSheet(ledger: Ledger, txn: Txn, today: LocalDate, onDone: (MarkChange) -> Unit) {
    val p = LocalPalette.current
    val res = resources()
    val merchantTarget = Marks.merchantTarget(txn)
    val sameName = remember(ledger, txn) { ledger.transactions.count { Marks.merchantTarget(it) == merchantTarget } }
    val kinds = listOf(Kind.PURCHASE, Kind.TRANSFER_OUT, Kind.OWN_TRANSFER, Kind.INCOME).filter { Mark(it).fits(txn.outgoing) }
    val categories = listOf(txn.category).filter { txn.kind == Kind.PURCHASE && it !in Categories.spending } + Categories.spending

    var kind by remember { mutableStateOf(txn.kind) }
    var category by remember { mutableStateOf(if (txn.kind == Kind.PURCHASE) txn.category else Categories.OTHER) }
    var all by remember { mutableStateOf(txn.markedBy == MarkScope.MERCHANT) }

    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding()) {
        // The buttons stay below the scrolling part so saving never needs a scroll past the category list.
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            Text(txn.merchant, fontFamily = Jost, fontSize = 24.sp, fontWeight = FontWeight.Medium, color = p.ink, maxLines = 2)
            val sum = amount(txn.amount) + if (txn.amount.currency == "UZS") " " + res.getString(R.string.som) else ""
            Text(
                "$sum, ${res.dayTitle(txn.at.toLocalDate(), today)}, %02d:%02d, ${txn.bank}".format(txn.at.hour, txn.at.minute),
                Modifier.padding(top = 4.dp), fontFamily = Golos, fontSize = 14.sp, color = p.muted,
            )

            Label(stringResource(R.string.mark_what))
            Column(Modifier.selectableGroup()) {
                kinds.forEach { k ->
                    val on = kind == k
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(6.dp))
                            .selectable(selected = on, role = Role.RadioButton) { kind = k },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier.size(20.dp).clip(CircleShape).border(2.dp, if (on) p.ink else p.faint, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) { if (on) Box(Modifier.size(10.dp).clip(CircleShape).background(p.ink)) }
                        Text(kindLabel(k), Modifier.padding(start = 14.dp), fontFamily = Golos, fontSize = 16.sp, color = p.ink)
                    }
                }
            }

            if (kind == Kind.PURCHASE) {
                Label(stringResource(R.string.mark_category))
                FlowRow(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    categories.forEach { c ->
                        val on = category == c
                        Row(
                            Modifier.clip(RoundedCornerShape(4.dp)).background(if (on) p.ink else p.track)
                                .selectable(selected = on, role = Role.RadioButton) { category = c }
                                .padding(horizontal = 10.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(p.dye(c)))
                            Text(
                                res.categoryLabel(c), Modifier.padding(start = 8.dp),
                                fontFamily = Golos, fontSize = 14.sp, color = if (on) p.bg else p.ink,
                            )
                        }
                    }
                }
            }

            if (sameName > 1) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 24.dp).heightIn(min = 48.dp)
                        .toggleable(value = all, role = Role.Switch) { all = it },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.mark_all, txn.merchant, sameName), Modifier.weight(1f).padding(end = 12.dp),
                        fontFamily = Golos, fontSize = 15.sp, color = p.ink,
                    )
                    Switch(
                        checked = all, onCheckedChange = null,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = p.bg, checkedTrackColor = p.ink,
                            uncheckedThumbColor = p.muted, uncheckedTrackColor = p.track, uncheckedBorderColor = p.faint,
                        ),
                    )
                }
            }

        }
        Text(
            stringResource(R.string.mark_save),
            Modifier.fillMaxWidth().padding(top = 16.dp).clip(RoundedCornerShape(6.dp)).background(p.ink)
                .clickable(role = Role.Button) {
                    val mark = Mark(kind, category.takeIf { kind == Kind.PURCHASE })
                    onDone(
                        if (all) MarkChange(merchantTarget, mark, txn.ids)
                        else MarkChange(txn.key, mark, txn.ids - txn.key),
                    )
                }
                .padding(vertical = 15.dp),
            textAlign = TextAlign.Center, fontFamily = Golos, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = p.bg,
        )
        if (txn.markedBy != null) {
            Text(
                stringResource(R.string.mark_reset),
                Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(6.dp))
                    .clickable(role = Role.Button) {
                        onDone(
                            if (txn.markedBy == MarkScope.MERCHANT) MarkChange(merchantTarget, null, txn.ids)
                            else MarkChange(txn.key, null, txn.ids),
                        )
                    }
                    .padding(vertical = 14.dp),
                textAlign = TextAlign.Center, fontFamily = Golos, fontSize = 15.sp, color = p.ink, textDecoration = TextDecoration.Underline,
            )
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun Label(text: String) {
    Text(
        text, Modifier.padding(top = 24.dp, bottom = 8.dp).semantics { heading() },
        fontFamily = Golos, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = LocalPalette.current.muted,
    )
}

@Composable
private fun kindLabel(kind: Kind): String = stringResource(
    when (kind) {
        Kind.PURCHASE -> R.string.kind_purchase
        Kind.TRANSFER_OUT -> R.string.kind_sent
        Kind.OWN_TRANSFER -> R.string.cat_own
        Kind.INCOME -> R.string.kind_income
    },
)
