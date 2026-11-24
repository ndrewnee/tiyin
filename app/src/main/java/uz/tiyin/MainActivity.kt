package uz.tiyin

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uz.tiyin.core.Kind
import uz.tiyin.core.Ledger
import uz.tiyin.core.Money
import uz.tiyin.core.Txn
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) { App() }
            }
        }
    }
}

private val smsPermissions = arrayOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS)

private enum class Period(val title: String) { MONTH("Этот месяц"), DAYS30("30 дней"), ALL("Всё") }

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
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) reload++ }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(hasPermission, reload) {
        if (!hasPermission) return@LaunchedEffect
        val store = SmsStore.get(context)
        val first = withContext(Dispatchers.IO) {
            store.importInbox(context)
            Ledger(store.all(), store.lookedUpCategories())
        }
        ledger = first
        if (first.unknownMerchants.isEmpty()) return@LaunchedEffect
        val outcome = withContext(Dispatchers.IO) { MerchantResolver.resolve(store, first.unknownMerchants) }
        if (outcome.failed) LookupWorker.enqueue(context)
        if (outcome.found > 0) ledger = withContext(Dispatchers.IO) { Ledger(store.all(), store.lookedUpCategories()) }
    }

    Column(Modifier.safeDrawingPadding().padding(horizontal = 16.dp)) {
        when {
            !hasPermission -> Column(Modifier.padding(top = 48.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Tiyin", style = MaterialTheme.typography.headlineMedium)
                Text("Приложение читает только SMS от банков (HUMO, UZCARD, Kapitalbank, Asakabank) и хранит их на телефоне. Коды подтверждения не сохраняются.")
                Button(onClick = { launcher.launch(smsPermissions) }) { Text("Разрешить доступ к SMS") }
            }
            ledger == null -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 48.dp))
            else -> LedgerScreen(ledger!!)
        }
    }
}

@Composable
private fun LedgerScreen(ledger: Ledger) {
    var period by remember { mutableStateOf(Period.MONTH) }
    var showUnparsed by remember { mutableStateOf(false) }
    val now = LocalDateTime.now()
    val from = when (period) {
        Period.MONTH -> LocalDate.now().withDayOfMonth(1).atStartOfDay()
        Period.DAYS30 -> now.minusDays(30)
        Period.ALL -> LocalDateTime.MIN
    }
    val txns = ledger.between(from, now.plusDays(1))
    val spend = Ledger.totals(txns, setOf(Kind.PURCHASE))
    val transfers = Ledger.totals(txns, setOf(Kind.TRANSFER_OUT))
    val income = Ledger.totals(txns, setOf(Kind.INCOME))

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(Modifier.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Period.entries.forEach { p -> FilterChip(selected = p == period, onClick = { period = p }, label = { Text(p.title) }) }
            }
            Text("Потрачено", style = MaterialTheme.typography.labelLarge)
            if (spend.isEmpty()) Text("0", style = MaterialTheme.typography.headlineLarge)
            spend.forEach { (cur, cats) ->
                Text(fmt(Money(cats.sumOf { it.total.minor }, cur)), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "Переводы: ${sumLine(transfers)} · Поступления: ${sumLine(income)}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
        }
        spend.forEach { (cur, cats) ->
            val max = cats.maxOf { it.total.minor }.coerceAtLeast(1)
            items(cats, key = { "cat-$cur-${it.category}" }) { c ->
                Column(Modifier.padding(vertical = 6.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${c.category} · ${c.count}")
                        Text(fmt(c.total), fontWeight = FontWeight.Medium)
                    }
                    LinearProgressIndicator(
                        progress = { c.total.minor.toFloat() / max },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    )
                }
            }
        }
        item {
            Spacer(Modifier.height(20.dp))
            Text("Операции", style = MaterialTheme.typography.titleMedium)
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
        }
        items(txns, key = { "t-${it.sms.receivedAt}-${it.sms.body.hashCode()}" }) { TxnRow(it) }
        if (ledger.unparsed.isNotEmpty()) {
            item {
                Text(
                    "Не разобрано: ${ledger.unparsed.size}",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(vertical = 16.dp).clickable { showUnparsed = !showUnparsed },
                )
            }
            if (showUnparsed) items(ledger.unparsed) { s ->
                Text("${s.sender}: ${s.body}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 8.dp))
            }
        }
        item { Spacer(Modifier.height(32.dp)) }
    }
}

private val rowTime = DateTimeFormatter.ofPattern("dd.MM HH:mm")

@Composable
private fun TxnRow(t: Txn) {
    val muted = t.kind == Kind.OWN_TRANSFER
    val sign = when (t.kind) {
        Kind.PURCHASE, Kind.TRANSFER_OUT -> "−"
        Kind.INCOME -> "+"
        Kind.OWN_TRANSFER -> "⇄ "
    }
    val color = if (muted) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Column(Modifier.weight(1f)) {
            Text(t.merchant, color = color, maxLines = 1)
            Text(
                "${t.at.format(rowTime)} · ${t.category} · ${t.bank}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(sign + fmt(t.amount), color = color, modifier = Modifier.padding(start = 8.dp))
    }
}

private fun sumLine(totals: Map<String, List<Ledger.CategoryTotal>>): String =
    if (totals.isEmpty()) "0" else totals.map { (cur, cats) -> fmt(Money(cats.sumOf { it.total.minor }, cur)) }.joinToString(" + ")

private fun fmt(m: Money): String {
    val whole = "%,d".format(m.minor / 100).replace(',', ' ')
    val cents = "%02d".format(m.minor % 100)
    return when (m.currency) {
        "UZS" -> "$whole сум"
        "USD" -> "$$whole.$cents"
        else -> "$whole.$cents ${m.currency}"
    }
}
