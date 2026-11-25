package uz.tiyin

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.io.IOException
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uz.tiyin.core.Ledger
import uz.tiyin.core.PaymeExport
import uz.tiyin.core.Txn

class MainActivity : ComponentActivity() {
    /** A file shared or opened with the app; cleared once imported so recreation does not import it again. */
    private val incoming = mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        // The default light navigation scrim draws a white strip under the gesture bar on HyperOS.
        enableEdgeToEdge(navigationBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        if (savedInstanceState == null) {
            Language.restore(this)
            incoming.value = sharedFile(intent)
        }
        setContent {
            TiyinTheme {
                Box(Modifier.fillMaxSize().background(LocalPalette.current.bg)) {
                    App(incoming.value) { incoming.value = null }
                }
            }
        }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(Language.wrap(newBase))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        sharedFile(intent)?.let { incoming.value = it }
    }

    private fun sharedFile(intent: Intent?): Uri? = when (intent?.action) {
        Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
        else -> null
    }
}

private const val XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

/** Blocking; returns the message to show. */
private fun importPayme(context: Context, uri: Uri): String {
    val res = context.resources
    val parsed = try {
        context.contentResolver.openInputStream(uri)?.use(PaymeExport::read) ?: return res.getString(R.string.import_cannot_open)
    } catch (e: PaymeExport.NotPaymeExport) {
        Log.w("Tiyin", "not a Payme export: ${e.message}")
        return res.getString(R.string.import_not_payme)
    } catch (e: IOException) {
        Log.w("Tiyin", "cannot read $uri", e)
        return res.getString(R.string.import_cannot_open)
    } catch (e: SecurityException) {
        Log.w("Tiyin", "no access to $uri", e)
        return res.getString(R.string.import_cannot_open)
    }
    if (parsed.skipped > 0) Log.w("Tiyin", "Payme export: ${parsed.skipped} rows skipped")
    val skipped = if (parsed.skipped > 0) "\n" + res.getString(R.string.import_skipped, parsed.skipped) else ""
    if (parsed.ops.isEmpty()) return res.getString(R.string.import_empty) + skipped
    val added = SmsStore.get(context).savePayme(parsed.ops, System.currentTimeMillis())
    val main = if (added == 0) res.getString(R.string.import_already) else res.getQuantityString(R.plurals.import_added, added, added)
    return main + skipped
}

private fun saveBackup(context: Context, uri: Uri): String {
    val res = context.resources
    return try {
        val counts = context.contentResolver.openOutputStream(uri)?.use { Backup.write(SmsStore.get(context), it) }
            ?: return res.getString(R.string.backup_failed)
        res.getString(R.string.backup_saved, counts.sms, counts.payme, counts.marks)
    } catch (e: IOException) {
        Log.w("Tiyin", "cannot write backup to $uri", e)
        res.getString(R.string.backup_failed)
    } catch (e: SecurityException) {
        Log.w("Tiyin", "no access to $uri", e)
        res.getString(R.string.backup_failed)
    }
}

private fun loadBackup(context: Context, uri: Uri): String {
    val res = context.resources
    return try {
        val counts = context.contentResolver.openInputStream(uri)?.use { Backup.read(SmsStore.get(context), it) }
            ?: return res.getString(R.string.import_cannot_open)
        res.getString(R.string.backup_loaded, counts.sms, counts.payme, counts.marks)
    } catch (e: Backup.NotBackup) {
        Log.w("Tiyin", "not a backup: ${e.message}")
        res.getString(R.string.backup_bad)
    } catch (e: IOException) {
        Log.w("Tiyin", "cannot read $uri", e)
        res.getString(R.string.import_cannot_open)
    } catch (e: SecurityException) {
        Log.w("Tiyin", "no access to $uri", e)
        res.getString(R.string.import_cannot_open)
    }
}

private val smsPermissions = arrayOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS)

@Composable
private fun App(incoming: Uri?, onImported: () -> Unit) {
    val context = LocalContext.current
    fun granted() = smsPermissions.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

    var hasPermission by remember { mutableStateOf(granted()) }
    var ledger by remember { mutableStateOf<Ledger?>(null) }
    var builtRevision by remember { mutableStateOf(-1L) }
    var reload by remember { mutableIntStateOf(0) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        hasPermission = granted()
    }
    var picked by remember { mutableStateOf<Uri?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { picked = it }
    val toImport = picked ?: incoming
    val scope = rememberCoroutineScope()
    fun inBackground(uri: Uri?, work: (Context, Uri) -> String) {
        uri ?: return
        scope.launch {
            val message = withContext(Dispatchers.IO) { work(context, uri) }
            reload++
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }
    val backupSaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { inBackground(it, ::saveBackup) }
    val backupLoader = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { inBackground(it, ::loadBackup) }
    LaunchedEffect(toImport) {
        val uri = toImport ?: return@LaunchedEffect
        val message = withContext(Dispatchers.IO) { importPayme(context, uri) }
        reload++
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        // Last: clearing the uri changes this effect's key and cancels it.
        picked = null
        onImported()
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
            store.importInbox(context)
            val revision = store.revision
            if (known != null && revision == builtRevision) null else revision to store.ledger()
        }
        if (fresh != null) {
            builtRevision = fresh.first
            ledger = fresh.second
        }
        val first = ledger ?: return@LaunchedEffect
        if (first.unknownMerchants.isEmpty()) return@LaunchedEffect
        val outcome = withContext(Dispatchers.IO) { MerchantResolver.resolve(store, first.unknownMerchants) }
        if (outcome.failed) LookupWorker.enqueue(context)
        if (outcome.found > 0) ledger = withContext(Dispatchers.IO) { store.ledger() }
    }

    when {
        !hasPermission -> Onboarding { launcher.launch(smsPermissions) }
        ledger == null -> Box(Modifier.fillMaxSize())
        else -> Ledgers(
            ledger!!,
            onImport = { picker.launch(arrayOf(XLSX, "application/octet-stream")) },
            onChanged = { reload++ },
            onSaveBackup = { backupSaver.launch("tiyin-${LocalDate.now()}.json") },
            onLoadBackup = { backupLoader.launch(arrayOf("application/json", "application/octet-stream", "text/plain")) },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Ledgers(ledger: Ledger, onImport: () -> Unit, onChanged: () -> Unit, onSaveBackup: () -> Unit, onLoadBackup: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<Txn?>(null) }
    val today = LocalDate.now()
    val current = Period.Month(YearMonth.from(today))
    var period by remember { mutableStateOf<Period>(current) }
    var picking by remember { mutableStateOf(false) }
    BackHandler(enabled = period != current) { period = current }

    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
        when (val shown = period) {
            is Period.Month -> MonthScreen(
                ledger, shown.ym, today, onPick = { picking = true }, onMonth = { period = Period.Month(it) }, onImport = onImport,
                onTxn = { editing = it },
            )
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
            PeriodPicker(
                ledger, today, period,
                onImport = { picking = false; onImport() },
                onSaveBackup = { picking = false; onSaveBackup() },
                onLoadBackup = { picking = false; onLoadBackup() },
            ) { picked -> period = picked; picking = false }
        }
    }
    editing?.let { txn ->
        val p = LocalPalette.current
        ModalBottomSheet(
            onDismissRequest = { editing = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = p.bg, scrimColor = p.sheetScrim, shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        ) {
            MarkSheet(ledger, txn, today) { change ->
                scope.launch {
                    withContext(Dispatchers.IO) { SmsStore.get(context).saveMark(change.target, change.mark, change.cleared, System.currentTimeMillis()) }
                    editing = null
                    onChanged()
                }
            }
        }
    }
}

@Composable
private fun PeriodPicker(
    ledger: Ledger, today: LocalDate, selected: Period,
    onImport: () -> Unit, onSaveBackup: () -> Unit, onLoadBackup: () -> Unit, onPick: (Period) -> Unit,
) {
    val p = LocalPalette.current
    val res = resources()
    val firstYear = ledger.transactions.minOfOrNull { it.at.year } ?: today.year
    LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding()) {
        item(key = "import") {
            Text(
                stringResource(R.string.import_payme),
                Modifier.fillMaxWidth().padding(bottom = 16.dp).clip(RoundedCornerShape(4.dp)).background(p.track)
                    .clickable(role = Role.Button, onClick = onImport).padding(horizontal = 14.dp, vertical = 14.dp),
                fontFamily = Golos, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = p.ink,
            )
        }
        item(key = "language") { LanguageRow() }
        item(key = "backup") {
            Row(Modifier.fillMaxWidth().padding(bottom = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(R.string.backup_save to onSaveBackup, R.string.backup_load to onLoadBackup).forEach { (label, action) ->
                    Text(
                        stringResource(label),
                        Modifier.weight(1f).clip(RoundedCornerShape(4.dp)).clickable(role = Role.Button, onClick = action)
                            .padding(vertical = 10.dp),
                        textAlign = TextAlign.Center, fontFamily = Golos, fontSize = 14.sp, color = p.ink, textDecoration = TextDecoration.Underline,
                    )
                }
            }
        }
        items((today.year downTo firstYear).toList(), key = { it }) { year ->
            val months = remember(ledger, year, p) { monthSummaries(ledger, year, p) }
            val total = months.sumOf { it.som }
            Column(Modifier.padding(bottom = 28.dp)) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(6.dp))
                        .clickable(role = Role.Button, onClickLabel = res.getString(R.string.open_item, res.getString(R.string.year_n, year))) { onPick(Period.Year(year)) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("$year", fontFamily = Jost, fontSize = 30.sp, fontWeight = FontWeight.Medium, color = if (selected == Period.Year(year)) p.income else p.ink)
                    Spacer(Modifier.weight(1f))
                    Text(if (total > 0) res.getString(R.string.year_total, res.shortSom(total), res.getString(R.string.som)) else res.getString(R.string.no_spending), fontFamily = Golos, fontSize = 14.sp, color = p.muted)
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
private fun LanguageRow() {
    val p = LocalPalette.current
    val context = LocalContext.current
    val shown = Language.shown(LocalConfiguration.current)
    val label = stringResource(R.string.language)
    Row(
        Modifier.fillMaxWidth().padding(bottom = 20.dp).semantics { contentDescription = label }.selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Language.options.forEach { (tag, name) ->
            val on = tag == shown
            Text(
                name,
                Modifier.weight(1f).clip(RoundedCornerShape(4.dp)).background(if (on) p.ink else p.track)
                    .selectable(selected = on, role = Role.RadioButton) { if (!on) context.activity()?.let { Language.set(it, tag) } }
                    .padding(vertical = 12.dp),
                textAlign = TextAlign.Center, fontFamily = Golos, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                color = if (on) p.bg else p.ink,
            )
        }
    }
}

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

@Composable
private fun MonthCell(m: MonthSummary, isSelected: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val p = LocalPalette.current
    val res = resources()
    val ink = when { isSelected -> p.bg; enabled -> p.ink; else -> p.muted }
    val description = res.monthName(m.ym) + ", " +
        if (m.som > 0) "${res.shortSom(m.som)} ${res.getString(R.string.som)}" else res.getString(R.string.no_spending)
    Column(
        modifier.clip(RoundedCornerShape(6.dp)).background(if (isSelected) p.ink else p.bg)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description }
            .padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (m.som > 0) ClothSwatch(m.threads, p.track, Modifier.fillMaxWidth().height(26.dp))
        else Box(Modifier.fillMaxWidth().height(26.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(p.faint))
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Text(res.monthShort(m.ym), fontFamily = Golos, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = ink)
            Spacer(Modifier.weight(1f))
            if (m.som > 0) Text(res.millions(m.som), style = TextStyle(fontFamily = Jost, fontFeatureSettings = "tnum"), fontSize = 13.sp, color = if (isSelected) p.bg else p.muted)
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
            Text(stringResource(R.string.onboarding_title), fontFamily = Jost, fontSize = 34.sp, lineHeight = 38.sp, fontWeight = FontWeight.Medium, color = p.ink)
            Text(
                stringResource(R.string.onboarding_body),
                Modifier.padding(top = 16.dp), fontFamily = Golos, fontSize = 16.sp, lineHeight = 24.sp, color = p.muted,
            )
            Text(
                stringResource(R.string.onboarding_privacy),
                Modifier.padding(top = 12.dp), fontFamily = Golos, fontSize = 16.sp, lineHeight = 24.sp, color = p.muted,
            )
            Spacer(Modifier.weight(1f))
            Box(
                Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(4.dp)).background(p.ink)
                    .clickable(role = Role.Button, onClick = onAllow),
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(R.string.allow_sms), fontFamily = Golos, fontSize = 17.sp, fontWeight = FontWeight.Medium, color = p.bg)
            }
        }
    }
}
