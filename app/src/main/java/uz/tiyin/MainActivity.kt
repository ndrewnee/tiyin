package uz.tiyin

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uz.tiyin.core.Ledger
import java.time.LocalDate
import java.time.YearMonth

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // The default light navigation scrim draws a white strip under the gesture bar on HyperOS.
        enableEdgeToEdge(navigationBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        setContent {
            TiyinTheme {
                Box(Modifier.fillMaxSize().background(LocalPalette.current.bg)) { App() }
            }
        }
    }
}

private val smsPermissions = arrayOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS)

@Composable
private fun App() {
    val context = LocalContext.current
    fun granted() = smsPermissions.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

    var hasPermission by remember { mutableStateOf(granted()) }
    var ledger by remember { mutableStateOf<Ledger?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        hasPermission = granted()
    }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) reload++ }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(hasPermission, reload) {
        if (!hasPermission) return@LaunchedEffect
        val store = SmsStore.get(context)
        val known = ledger
        val fresh = withContext(Dispatchers.IO) {
            val added = store.importInbox(context)
            if (known != null && added == 0) null else Ledger(store.all(), store.lookedUpCategories())
        }
        if (fresh != null) ledger = fresh
        val first = ledger ?: return@LaunchedEffect
        if (first.unknownMerchants.isEmpty()) return@LaunchedEffect
        val outcome = withContext(Dispatchers.IO) { MerchantResolver.resolve(store, first.unknownMerchants) }
        if (outcome.failed) LookupWorker.enqueue(context)
        if (outcome.found > 0) ledger = withContext(Dispatchers.IO) { Ledger(store.all(), store.lookedUpCategories()) }
    }

    when {
        !hasPermission -> Onboarding { launcher.launch(smsPermissions) }
        ledger == null -> Box(Modifier.fillMaxSize())
        else -> Ledgers(ledger!!)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Ledgers(ledger: Ledger) {
    val today = LocalDate.now()
    val current = Period.Month(YearMonth.from(today))
    var period by remember { mutableStateOf<Period>(current) }
    var picking by remember { mutableStateOf(false) }
    BackHandler(enabled = period != current) { period = current }

    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
        when (val shown = period) {
            is Period.Month -> MonthScreen(ledger, shown.ym, today, onPick = { picking = true }, onMonth = { period = Period.Month(it) })
            is Period.Year -> YearScreen(
                ledger, shown.year, today, onPick = { picking = true },
                onYear = { period = Period.Year(it) }, onMonth = { period = Period.Month(it) },
            )
        }
    }
    if (picking) {
        val p = LocalPalette.current
        ModalBottomSheet(
            onDismissRequest = { picking = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = p.bg, scrimColor = p.sheetScrim, shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        ) {
            PeriodPicker(ledger, today, period) { picked -> period = picked; picking = false }
        }
    }
}

@Composable
private fun PeriodPicker(ledger: Ledger, today: LocalDate, selected: Period, onPick: (Period) -> Unit) {
    val p = LocalPalette.current
    val firstYear = ledger.transactions.minOfOrNull { it.at.year } ?: today.year
    LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding()) {
        items((today.year downTo firstYear).toList(), key = { it }) { year ->
            val months = remember(ledger, year, p) { monthSummaries(ledger, year, p) }
            val total = months.sumOf { it.som }
            Column(Modifier.padding(bottom = 28.dp)) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(6.dp))
                        .clickable(role = Role.Button, onClickLabel = "Открыть $year год") { onPick(Period.Year(year)) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("$year", fontFamily = Jost, fontSize = 30.sp, fontWeight = FontWeight.Medium, color = if (selected == Period.Year(year)) p.income else p.ink)
                    Spacer(Modifier.weight(1f))
                    Text(if (total > 0) "${shortSom(total)} сум за год" else "трат нет", fontFamily = Golos, fontSize = 14.sp, color = p.muted)
                }
                Spacer(Modifier.height(10.dp))
                months.chunked(4).forEach { row ->
                    Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { m -> MonthCell(m, isSelected = selected == Period.Month(m.ym), enabled = m.som > 0 && !m.ym.isAfter(YearMonth.from(today)), Modifier.weight(1f)) { onPick(Period.Month(m.ym)) } }
                    }
                }
            }
        }
    }
}

@Composable
private fun MonthCell(m: MonthSummary, isSelected: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val p = LocalPalette.current
    val ink = when { isSelected -> p.bg; enabled -> p.ink; else -> p.muted }
    Column(
        modifier.clip(RoundedCornerShape(6.dp)).background(if (isSelected) p.ink else p.bg)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = monthNames[m.ym.monthValue - 1] + if (m.som > 0) ", ${shortSom(m.som)} сум" else ", трат нет" }
            .padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (m.som > 0) ClothSwatch(m.threads, p.track, Modifier.fillMaxWidth().height(26.dp))
        else Box(Modifier.fillMaxWidth().height(26.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(p.faint))
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Text(monthShort[m.ym.monthValue - 1], fontFamily = Golos, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = ink)
            Spacer(Modifier.weight(1f))
            if (m.som > 0) Text(millions(m.som), style = TextStyle(fontFamily = Jost, fontFeatureSettings = "tnum"), fontSize = 13.sp, color = if (isSelected) p.bg else p.muted)
        }
    }
}

@Composable
private fun Onboarding(onAllow: () -> Unit) {
    val p = LocalPalette.current
    val dyes = listOf("Продукты и доставка", "Кафе и рестораны", "", "Покупки", "Здоровье", "Продукты и доставка", "Такси")
    val weights = listOf(3f, 2f, 1f, 2.4f, 1.6f, 1f, 2f)
    Column(Modifier.fillMaxSize()) {
        IkatCloth(
            dyes.zip(weights).map { (cat, w) -> Strand(w, if (cat.isEmpty()) p.bg else p.dye(cat)) },
            Modifier.fillMaxWidth().height(300.dp),
            steps = 20, featherFraction = 0.03f,
        )
        Column(Modifier.weight(1f).fillMaxWidth().navigationBarsPadding().padding(start = 24.dp, end = 24.dp, top = 28.dp, bottom = 24.dp)) {
            Text("Tiyin ткёт ваши траты из банковских SMS", fontFamily = Jost, fontSize = 34.sp, lineHeight = 38.sp, fontWeight = FontWeight.Medium, color = p.ink)
            Text(
                "Каждая покупка становится ниткой своего цвета, месяц — полотном. Вводить ничего не нужно, категории проставляются сами.",
                Modifier.padding(top = 16.dp), fontFamily = Golos, fontSize = 16.sp, lineHeight = 24.sp, color = p.muted,
            )
            Text(
                "Читаются только SMS от HUMO, UZCARD, Kapitalbank и Asakabank. Всё остаётся на телефоне, коды подтверждения не сохраняются.",
                Modifier.padding(top = 12.dp), fontFamily = Golos, fontSize = 16.sp, lineHeight = 24.sp, color = p.muted,
            )
            Spacer(Modifier.weight(1f))
            Box(
                Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(4.dp)).background(p.ink)
                    .clickable(role = Role.Button, onClick = onAllow),
                contentAlignment = Alignment.Center,
            ) {
                Text("Разрешить чтение SMS", fontFamily = Golos, fontSize = 17.sp, fontWeight = FontWeight.Medium, color = p.bg)
            }
        }
    }
}
