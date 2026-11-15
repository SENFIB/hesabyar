package com.hesabyar.app.ui

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.hesabyar.app.R
import com.hesabyar.app.data.TransactionEntity

// ================= تب حسابداری با زیرتب‌ها =================

@Composable
fun AccountingScreen(vm: MainViewModel, regionType: CalendarType) {
    var tab by rememberSaveable { mutableStateOf(0) }
    val titles = listOf(
        tr("تراکنش‌ها", "Transactions", "المعاملات"),
        tr("گزارش‌ها", "Reports", "التقارير"),
        tr("چک و سفته", "Cheques & notes", "الشيكات والسندات")
    )

    Column(modifier = Modifier.fillMaxSize()) {
        ScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) {
            titles.forEachIndexed { i, title ->
                Tab(
                    selected = tab == i,
                    onClick = { tab = i },
                    text = { Text(title) }
                )
            }
        }
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                0 -> LedgerScreen(vm, regionType)
                1 -> ReportsTab(vm, regionType)
                else -> ChecksTab(regionType)
            }
        }
    }
}

// ================= گزارش‌ها =================

@Composable
private fun reportPeriodLabel(p: BackupPeriod): String = when (p) {
    BackupPeriod.DAILY -> tr("روزانه", "Daily", "يومي")
    BackupPeriod.WEEKLY -> tr("هفتگی", "Weekly", "أسبوعي")
    BackupPeriod.MONTHLY -> tr("ماهانه", "Monthly", "شهري")
    BackupPeriod.YEARLY -> tr("سالانه", "Yearly", "سنوي")
    BackupPeriod.ALL -> tr("همه", "All", "الكل")
    BackupPeriod.CUSTOM -> tr("از تا", "Custom", "مخصص")
}

@Composable
fun ReportsTab(vm: MainViewModel, regionType: CalendarType) {
    val all by vm.transactions.collectAsState()

    var period by remember { mutableStateOf(BackupPeriod.MONTHLY) }
    var typeFilter by rememberSaveable { mutableStateOf(0) } // 0 همه، 1 درآمد، 2 هزینه
    var sortByAmount by rememberSaveable { mutableStateOf(false) }
    var fromText by rememberSaveable { mutableStateOf("") }
    var toText by rememberSaveable { mutableStateOf("") }

    val fromMs = parseBackupDay(fromText)
    val toMs = parseBackupDay(toText)
    val customInvalid = period == BackupPeriod.CUSTOM && (fromMs == null || toMs == null)
    val range: BackupRange? = if (customInvalid) null else backupRangeFor(period, fromMs, toMs)

    val inPeriod: List<TransactionEntity> = if (customInvalid) {
        emptyList<TransactionEntity>()
    } else {
        all.filter { t -> range == null || t.dateMillis in range.from..range.to }
    }

    val income = inPeriod.filter { it.isIncome }.sumOf { it.amount }
    val expense = inPeriod.filter { !it.isIncome }.sumOf { it.amount }
    val shown = inPeriod.filter { typeFilter == 0 || (typeFilter == 1) == it.isIncome }
    val groups = shown.groupBy { it.category }
        .map { (key, list) -> key to list.sumOf { it.amount } }
        .sortedByDescending { it.second }
    val maxGroup = (groups.maxOfOrNull { it.second } ?: 1L).coerceAtLeast(1L)
    val details = if (sortByAmount) {
        shown.sortedByDescending { it.amount }
    } else {
        shown.sortedByDescending { it.dateMillis }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(tr("بازه زمانی", "Period", "الفترة"), style = MaterialTheme.typography.labelLarge)
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                BackupPeriod.entries.chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { p ->
                            FilterChip(
                                selected = period == p,
                                onClick = { period = p },
                                label = { Text(reportPeriodLabel(p)) }
                            )
                        }
                    }
                }
            }
        }

        if (period == BackupPeriod.CUSTOM) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        value = fromText,
                        onValueChange = { fromText = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(tr("از تاریخ (مثلاً 2025/01/01)", "From (e.g. 2025/01/01)", "من (مثال 2025/01/01)")) }
                    )
                    OutlinedTextField(
                        value = toText,
                        onValueChange = { toText = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(tr("تا تاریخ (مثلاً 2025/01/31)", "To (e.g. 2025/01/31)", "إلى (مثال 2025/01/31)")) }
                    )
                }
            }
            if (customInvalid) {
                item {
                    Text(
                        tr(
                            "تاریخ‌ها را به شکل 2025/01/31 بنویسید.",
                            "Write dates like 2025/01/31.",
                            "اكتب التواريخ مثل 2025/01/31."
                        ),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = typeFilter == 0,
                        onClick = { typeFilter = 0 },
                        label = { Text(tr("همه", "All", "الكل")) }
                    )
                    FilterChip(
                        selected = typeFilter == 1,
                        onClick = { typeFilter = 1 },
                        label = { Text(stringResource(R.string.income)) }
                    )
                    FilterChip(
                        selected = typeFilter == 2,
                        onClick = { typeFilter = 2 },
                        label = { Text(stringResource(R.string.expense)) }
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = !sortByAmount,
                        onClick = { sortByAmount = false },
                        label = { Text(tr("مرتب‌سازی بر اساس تاریخ", "Sort by date", "ترتيب حسب التاريخ")) }
                    )
                    FilterChip(
                        selected = sortByAmount,
                        onClick = { sortByAmount = true },
                        label = { Text(tr("مرتب‌سازی بر اساس مبلغ", "Sort by amount", "ترتيب حسب المبلغ")) }
                    )
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SummaryBox(
                    title = tr("مجموع درآمد", "Total income", "إجمالي الدخل"),
                    value = income,
                    color = Color(0xFF2E7D32),
                    modifier = Modifier.weight(1f)
                )
                SummaryBox(
                    title = tr("مجموع هزینه", "Total expense", "إجمالي المصروف"),
                    value = expense,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        tr("مانده (درآمد منهای هزینه)", "Balance (income minus expense)", "الرصيد (الدخل ناقص المصروف)"),
                        style = MaterialTheme.typography.labelLarge
                    )
                    Text(
                        "${formatMoney(income - expense)} ${stringResource(R.string.currency)}",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        item {
            Text(tr("بر اساس دسته‌بندی", "By category", "حسب الفئة"), style = MaterialTheme.typography.titleMedium)
        }

        items(groups) { (key, sum) ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(categoryName(key))
                    Text(formatMoney(sum), fontWeight = FontWeight.Bold)
                }
                SimpleBar(fraction = sum.toFloat() / maxGroup.toFloat())
            }
        }

        item {
            Text(tr("فهرست تراکنش‌ها", "Transactions", "المعاملات"), style = MaterialTheme.typography.titleMedium)
        }

        if (details.isEmpty()) {
            item {
                Text(tr("موردی در این بازه نیست.", "No items in this period.", "لا توجد عناصر في هذه الفترة."))
            }
        }

        items(details, key = { it.id }) { t ->
            TransactionRow(t = t, type = regionType, onDelete = { vm.deleteTransaction(t) })
        }
    }
}

@Composable
private fun SummaryBox(title: String, value: Long, color: Color, modifier: Modifier) {
    Card(modifier = modifier) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge)
            Text(
                formatMoney(value),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = color
            )
        }
    }
}

// نمودار میله‌ای ساده بدون کتابخانه، برای مصرف کم منابع
@Composable
private fun SimpleBar(fraction: Float) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.primary)
        )
    }
}

// ================= چک و سفته =================

data class Cheque(
    val id: Long,
    val kind: Int,        // 0 چک، 1 سفته
    val direction: Int,   // 0 دریافتنی، 1 پرداختنی
    val person: String,
    val amount: Long,
    val dueMillis: Long,
    val remindDays: Int,
    val level: Int,       // 1، 2 یا 3
    val done: Boolean
)

class ChequeDb(ctx: Context) : SQLiteOpenHelper(ctx, "hesabyar_cheques.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE cheques (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "kind INTEGER NOT NULL, " +
                "direction INTEGER NOT NULL, " +
                "person TEXT NOT NULL, " +
                "amount INTEGER NOT NULL, " +
                "dueMillis INTEGER NOT NULL, " +
                "remindDays INTEGER NOT NULL, " +
                "level INTEGER NOT NULL, " +
                "done INTEGER NOT NULL)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
}

// دسترسی به جدول چک‌ها. این جدول جدا از دیتابیس تراکنش‌هاست و روی همان گوشی ذخیره می‌شود.
object ChequeStore {
    private var helper: ChequeDb? = null

    private fun db(ctx: Context): SQLiteDatabase {
        val h = helper ?: ChequeDb(ctx.applicationContext).also { helper = it }
        return h.writableDatabase
    }

    fun all(ctx: Context): List<Cheque> {
        val out = ArrayList<Cheque>()
        db(ctx).rawQuery(
            "SELECT id, kind, direction, person, amount, dueMillis, remindDays, level, done " +
                "FROM cheques ORDER BY dueMillis",
            null
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    Cheque(
                        id = c.getLong(0),
                        kind = c.getInt(1),
                        direction = c.getInt(2),
                        person = c.getString(3) ?: "",
                        amount = c.getLong(4),
                        dueMillis = c.getLong(5),
                        remindDays = c.getInt(6),
                        level = c.getInt(7),
                        done = c.getInt(8) != 0
                    )
                )
            }
        }
        return out
    }

    fun add(ctx: Context, ch: Cheque) {
        val cv = ContentValues().apply {
            put("kind", ch.kind)
            put("direction", ch.direction)
            put("person", ch.person)
            put("amount", ch.amount)
            put("dueMillis", ch.dueMillis)
            put("remindDays", ch.remindDays)
            put("level", ch.level)
            put("done", if (ch.done) 1 else 0)
        }
        db(ctx).insert("cheques", null, cv)
    }

    fun setDone(ctx: Context, id: Long, done: Boolean) {
        val cv = ContentValues().apply { put("done", if (done) 1 else 0) }
        db(ctx).update("cheques", cv, "id = ?", arrayOf(id.toString()))
    }

    fun delete(ctx: Context, id: Long) {
        db(ctx).delete("cheques", "id = ?", arrayOf(id.toString()))
    }
}

private const val DAY_MS = 24L * 60L * 60L * 1000L
private val REMIND_OPTIONS = listOf(0, 1, 3, 7, 14, 30)

@Composable
private fun remindLabel(days: Int): String = when (days) {
    0 -> tr("همان روز", "Same day", "في نفس اليوم")
    1 -> tr("۱ روز قبل", "1 day before", "قبل يوم")
    3 -> tr("۳ روز قبل", "3 days before", "قبل 3 أيام")
    7 -> tr("۱ هفته قبل", "1 week before", "قبل أسبوع")
    14 -> tr("۲ هفته قبل", "2 weeks before", "قبل أسبوعين")
    else -> tr("۱ ماه قبل", "1 month before", "قبل شهر")
}

@Composable
private fun levelText(level: Int): String = when (level) {
    1 -> tr("درجه ۱: صفحه کامل هشدار", "Level 1: full-screen alert", "المستوى 1: تنبيه بملء الشاشة")
    2 -> tr("درجه ۲: اعلان روی صفحه", "Level 2: on-screen notice", "المستوى 2: إشعار على الشاشة")
    else -> tr(
        "درجه ۳: فقط مرکز اعلان‌ها با صدای ملایم",
        "Level 3: notification center only, soft sound",
        "المستوى 3: مركز الإشعارات فقط بصوت خفيف"
    )
}

@Composable
fun ChecksTab(regionType: CalendarType) {
    val ctx = LocalContext.current
    var version by remember { mutableStateOf(0) }
    val cheques = remember(version) { ChequeStore.all(ctx) }
    val now = System.currentTimeMillis()

    var kind by rememberSaveable { mutableStateOf(0) }
    var direction by rememberSaveable { mutableStateOf(0) }
    var person by rememberSaveable { mutableStateOf("") }
    var amountText by rememberSaveable { mutableStateOf("") }
    var dueText by rememberSaveable { mutableStateOf("") }
    var remindDays by rememberSaveable { mutableStateOf(1) }
    var level by rememberSaveable { mutableStateOf(2) }
    var errText by remember { mutableStateOf<String?>(null) }

    val alerts = cheques.filter { !it.done && now >= it.dueMillis - it.remindDays * DAY_MS }
    val hasLevel1 = alerts.any { it.level == 1 }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(tr("ثبت چک یا سفته", "Add cheque or note", "إضافة شيك أو سند"), style = MaterialTheme.typography.titleMedium)

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = kind == 0,
                            onClick = { kind = 0 },
                            label = { Text(tr("چک", "Cheque", "شيك")) }
                        )
                        FilterChip(
                            selected = kind == 1,
                            onClick = { kind = 1 },
                            label = { Text(tr("سفته", "Promissory note", "سند")) }
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = direction == 0,
                            onClick = { direction = 0 },
                            label = { Text(tr("دریافتنی", "Receivable", "مستحق لنا")) }
                        )
                        FilterChip(
                            selected = direction == 1,
                            onClick = { direction = 1 },
                            label = { Text(tr("پرداختنی", "Payable", "مستحق علينا")) }
                        )
                    }

                    OutlinedTextField(
                        value = person,
                        onValueChange = { person = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(tr("طرف حساب", "Person / party", "الطرف")) }
                    )

                    OutlinedTextField(
                        value = amountText,
                        onValueChange = { amountText = digitsOnly(it) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        visualTransformation = ThousandsVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("${stringResource(R.string.amount)} (${stringResource(R.string.currency)})") }
                    )

                    OutlinedTextField(
                        value = dueText,
                        onValueChange = { dueText = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(tr("سررسید (مثلاً 2025/01/31)", "Due date (e.g. 2025/01/31)", "الاستحقاق (مثال 2025/01/31)")) }
                    )

                    Text(tr("هشدار قبل از سررسید", "Remind before due date", "تنبيه قبل الاستحقاق"), style = MaterialTheme.typography.labelLarge)
                    REMIND_OPTIONS.chunked(3).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            row.forEach { d ->
                                FilterChip(
                                    selected = remindDays == d,
                                    onClick = { remindDays = d },
                                    label = { Text(remindLabel(d)) }
                                )
                            }
                        }
                    }

                    Text(tr("درجه اعلان", "Notification level", "درجة الإشعار"), style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(1, 2, 3).forEach { lv ->
                            FilterChip(
                                selected = level == lv,
                                onClick = { level = lv },
                                label = { Text(lv.toString()) }
                            )
                        }
                    }
                    Text(levelText(level), style = MaterialTheme.typography.bodySmall)

                    errText?.let { Text(it, color = MaterialTheme.colorScheme.error) }

                    Button(
                        onClick = {
                            val amount = amountText.toLongOrNull()
                            val due = parseBackupDay(dueText)
                            when {
                                person.isBlank() -> errText = tr("نام طرف حساب را وارد کنید", "Enter the party name", "أدخل اسم الطرف")
                                amount == null || amount <= 0L -> errText = tr("مبلغ معتبر وارد کنید", "Enter a valid amount", "أدخل مبلغاً صحيحاً")
                                due == null -> errText = tr("تاریخ را به شکل 2025/01/31 بنویسید", "Write the date like 2025/01/31", "اكتب التاريخ مثل 2025/01/31")
                                else -> {
                                    ChequeStore.add(
                                        ctx,
                                        Cheque(
                                            id = 0L,
                                            kind = kind,
                                            direction = direction,
                                            person = person.trim(),
                                            amount = amount,
                                            dueMillis = due,
                                            remindDays = remindDays,
                                            level = level,
                                            done = false
                                        )
                                    )
                                    person = ""
                                    amountText = ""
                                    dueText = ""
                                    errText = null
                                    version++
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.add))
                    }
                }
            }
        }

        if (alerts.isNotEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (hasLevel1) {
                            MaterialTheme.colorScheme.errorContainer
                        } else {
                            MaterialTheme.colorScheme.secondaryContainer
                        }
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            tr("سررسیدهای نزدیک", "Upcoming due dates", "الاستحقاقات القريبة"),
                            style = MaterialTheme.typography.titleSmall
                        )
                    }
                }
            }
            items(alerts, key = { "alert_${it.id}" }) { c ->
                AlertLine(c = c, regionType = regionType)
            }
        }

        item {
            Text(tr("همه چک‌ها و سفته‌ها", "All cheques and notes", "كل الشيكات والسندات"), style = MaterialTheme.typography.titleMedium)
        }

        if (cheques.isEmpty()) {
            item {
                Text(tr("هنوز چکی ثبت نشده است.", "No entries yet.", "لا توجد عناصر بعد."))
            }
        }

        items(cheques, key = { it.id }) { c ->
            ChequeRow(
                c = c,
                regionType = regionType,
                onToggle = {
                    ChequeStore.setDone(ctx, c.id, !c.done)
                    version++
                },
                onDelete = {
                    ChequeStore.delete(ctx, c.id)
                    version++
                }
            )
        }
    }
}

@Composable
private fun AlertLine(c: Cheque, regionType: CalendarType) {
    val dueText = formatWith(c.dueMillis, "yyyy/MM/dd", regionType.locale, deviceTimeZone())
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                "⚠ ${c.person} • ${formatMoney(c.amount)} ${stringResource(R.string.currency)}",
                style = MaterialTheme.typography.titleSmall,
                color = if (c.level == 1) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
            )
            Text(
                "${tr("سررسید", "Due", "الاستحقاق")}: $dueText • ${levelText(c.level)}",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun ChequeRow(
    c: Cheque,
    regionType: CalendarType,
    onToggle: () -> Unit,
    onDelete: () -> Unit
) {
    val kindText = if (c.kind == 0) tr("چک", "Cheque", "شيك") else tr("سفته", "Promissory note", "سند")
    val dirText = if (c.direction == 0) {
        tr("دریافتنی", "Receivable", "مستحق لنا")
    } else {
        tr("پرداختنی", "Payable", "مستحق علينا")
    }
    val dueText = formatWith(c.dueMillis, "yyyy/MM/dd", regionType.locale, deviceTimeZone())

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text("$kindText • $dirText", style = MaterialTheme.typography.labelLarge)
            Text(
                c.person,
                style = MaterialTheme.typography.titleMedium,
                textDecoration = if (c.done) androidx.compose.ui.text.style.TextDecoration.LineThrough else null
            )
            Text(
                "${formatMoney(c.amount)} ${stringResource(R.string.currency)} • ${tr("سررسید", "Due", "الاستحقاق")}: $dueText",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                "${remindLabel(c.remindDays)} • ${levelText(c.level)}",
                style = MaterialTheme.typography.bodySmall
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onToggle) {
                    Text(
                        if (c.done) tr("برگشت به فعال", "Mark active", "إعادة التفعيل")
                        else tr("انجام شد", "Mark done", "تم")
                    )
                }
                TextButton(onClick = onDelete) {
                    Text(stringResource(R.string.delete))
                }
            }
        }
    }
}
