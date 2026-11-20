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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hesabyar.app.R
import com.hesabyar.app.data.TransactionEntity

// ================= ردیف چیپ‌های هم‌اندازه =================

@Composable
private fun <T> EqualChipRow(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    columns: Int = 3
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        options.chunked(columns).forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                rowItems.forEach { (value, label) ->
                    FilterChip(
                        selected = selected == value,
                        onClick = { onSelect(value) },
                        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        modifier = Modifier.weight(1f)
                    )
                }
                repeat(columns - rowItems.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

// ================= تاریخ بر اساس تقویم زبان =================

// نمونه تاریخ امروز با تقویم زبان انتخابی (مثلاً 1404/01/31 یا 2025/01/31)
fun todayExample(type: CalendarType): String =
    formatWith(System.currentTimeMillis(), "yyyy/MM/dd", type.locale, type.timeZone)

// تبدیل تاریخ نوشته‌شده به زمان، طبق تقویم زبان انتخابی.
// خروجی: ابتدای همان روز در منطقه زمانی زبان. اگر تاریخ معتبر نباشد null می‌دهد.
fun parseDayIn(text: String, type: CalendarType): Long? {
    val clean = text.trim()
    if (clean.length < 8) return null
    val parts = asciiDigits(clean).split('/', '-', '.').map { it.trim() }
    if (parts.size != 3) return null
    val y = parts[0].toIntOrNull() ?: return null
    val m = parts[1].toIntOrNull() ?: return null
    val d = parts[2].toIntOrNull() ?: return null
    if (m !in 1..12 || d !in 1..31) return null

    val target = listOf(y, m, d)

    fun dayOf(millis: Long): List<Int> =
        asciiDigits(formatWith(millis, "yyyy/MM/dd", type.locale, type.timeZone))
            .split('/')
            .map { it.trim().toIntOrNull() ?: -1 }

    // سال میلادی تقریبی برای شروع جست‌وجو
    val approxGreg = when (type) {
        CalendarType.PERSIAN -> y + 621
        CalendarType.HIJRI -> (y * 0.970229 + 621.5).toInt()
        else -> y
    }
    val start = java.util.GregorianCalendar(java.util.TimeZone.getTimeZone("UTC")).apply {
        clear()
        set(approxGreg, 0, 1)
    }.timeInMillis - 30L * DAY_MS

    // جست‌وجو روز به روز تا روزی که تاریخش برابر ورودی کاربر باشد
    var t = start
    var hit: Long? = null
    for (i in 0 until 900) {
        if (dayOf(t) == target) {
            hit = t
            break
        }
        t += DAY_MS
    }
    val day = hit ?: return null

    // پیدا کردن اولین لحظه همان روز (جست‌وجوی دودویی تا دقیقه)
    var lo = day - DAY_MS
    var hi = day
    while (hi - lo > 60_000L) {
        val mid = lo + (hi - lo) / 2
        if (dayOf(mid) == target) hi = mid else lo = mid
    }
    return hi
}

// ================= تب حسابداری با زیرتب‌ها =================

@Composable
fun AccountingScreen(vm: MainViewModel, regionType: CalendarType) {
    var tab by rememberSaveable { mutableStateOf(0) }
    var showImageSearch by rememberSaveable { mutableStateOf(false) }

    val titles = listOf(
        tr("تراکنش‌ها", "Transactions", "المعاملات"),
        tr("گزارش‌ها", "Reports", "التقارير"),
        tr("چک و سفته", "Cheques & notes", "الشيكات والسندات"),
        tr("پیامک‌ها", "SMS", "الرسائل"),
        tr("کالاها", "Inventory", "المخزون"),
        tr("حقوق و دستمزد", "Salary & wages", "الرواتب والأجور")
    )
    val searchByPhotoText = tr("جست‌وجو با عکس", "Search by photo", "بحث بالصورة")

    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            EqualChipRow(
                options = titles.mapIndexed { i, t -> i to t },
                selected = tab,
                onSelect = { tab = it },
                columns = 3
            )
        }
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                0 -> LedgerScreen(vm, regionType)
                1 -> ReportsTab(vm, regionType)
                2 -> ChecksTab(regionType)
                3 -> SmsTab(vm, regionType)
                4 -> InventoryScreen(vm, regionType)
                else -> SalaryScreen(vm, regionType)
            }
            if (tab == 4) {
                ExtendedFloatingActionButton(
                    onClick = { showImageSearch = true },
                    icon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    text = { Text(searchByPhotoText) },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(16.dp)
                )
            }
        }
    }
    if (showImageSearch) {
        ImageSearchDialog(onDismiss = { showImageSearch = false })
    }
}

// ================= گزارش‌ها =================

@Composable
fun ReportsTab(vm: MainViewModel, regionType: CalendarType) {
    val all by vm.transactions.collectAsState()

    var period by remember { mutableStateOf(BackupPeriod.MONTHLY) }
    var typeFilter by rememberSaveable { mutableStateOf(0) } // 0 همه، 1 درآمد، 2 هزینه
    var sortByAmount by rememberSaveable { mutableStateOf(false) }
    var fromText by rememberSaveable { mutableStateOf("") }
    var toText by rememberSaveable { mutableStateOf("") }

    // گزینه‌ها و متن‌ها فقط اینجا ساخته می‌شوند
    val exampleDate = remember(regionType) { todayExample(regionType) }
    val tPeriodLabel = tr("بازه زمانی", "Period", "الفترة")
    val periodOptions = listOf(
        BackupPeriod.DAILY to tr("روزانه", "Daily", "يومي"),
        BackupPeriod.WEEKLY to tr("هفتگی", "Weekly", "أسبوعي"),
        BackupPeriod.MONTHLY to tr("ماهانه", "Monthly", "شهري"),
        BackupPeriod.YEARLY to tr("سالانه", "Yearly", "سنوي"),
        BackupPeriod.ALL to tr("همه", "All", "الكل"),
        BackupPeriod.CUSTOM to tr("از تا", "Custom", "مخصص")
    )
    val typeOptions = listOf(
        0 to tr("همه", "All", "الكل"),
        1 to stringResource(R.string.income),
        2 to stringResource(R.string.expense)
    )
    val sortOptions = listOf(
        false to tr("بر اساس تاریخ", "By date", "حسب التاريخ"),
        true to tr("بر اساس مبلغ", "By amount", "حسب المبلغ")
    )
    val tFromLabel = tr("از تاریخ", "From", "من") + " (" + tr("مثلاً", "e.g.", "مثال") + " " + exampleDate + ")"
    val tToLabel = tr("تا تاریخ", "To", "إلى") + " (" + tr("مثلاً", "e.g.", "مثال") + " " + exampleDate + ")"
    val tDateHelp = tr("تاریخ‌ها را مثل ", "Write dates like ", "اكتب التواريخ مثل ") +
        exampleDate + tr(" بنویسید.", ".", ".")
    val tIncomeTotal = tr("مجموع درآمد", "Total income", "إجمالي الدخل")
    val tExpenseTotal = tr("مجموع هزینه", "Total expense", "إجمالي المصروف")
    val tBalance = tr("مانده (درآمد منهای هزینه)", "Balance (income minus expense)", "الرصيد (الدخل ناقص المصروف)")
    val tByCategory = tr("بر اساس دسته‌بندی", "By category", "حسب الفئة")
    val tListHeader = tr("فهرست تراکنش‌ها", "Transactions", "المعاملات")
    val tNone = tr("موردی در این بازه نیست.", "No items in this period.", "لا توجد عناصر في هذه الفترة.")
    val currency = stringResource(R.string.currency)

    val fromMs = remember(fromText, regionType) { parseDayIn(fromText, regionType) }
    val toMs = remember(toText, regionType) { parseDayIn(toText, regionType) }
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
    val balanceText = formatMoney(income - expense) + " " + currency

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(tPeriodLabel, style = MaterialTheme.typography.labelLarge)
        }
        item {
            EqualChipRow(
                options = periodOptions,
                selected = period,
                onSelect = { period = it },
                columns = 3
            )
        }
        if (period == BackupPeriod.CUSTOM) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        value = fromText,
                        onValueChange = { fromText = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(tFromLabel) }
                    )
                    OutlinedTextField(
                        value = toText,
                        onValueChange = { toText = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(tToLabel) }
                    )
                }
            }
            if (customInvalid) {
                item {
                    Text(tDateHelp, color = MaterialTheme.colorScheme.error)
                }
            }
        }
        item {
            EqualChipRow(
                options = typeOptions,
                selected = typeFilter,
                onSelect = { typeFilter = it },
                columns = 3
            )
        }
        item {
            EqualChipRow(
                options = sortOptions,
                selected = sortByAmount,
                onSelect = { sortByAmount = it },
                columns = 2
            )
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SummaryBox(
                    title = tIncomeTotal,
                    value = income,
                    color = Color(0xFF2E7D32),
                    modifier = Modifier.weight(1f)
                )
                SummaryBox(
                    title = tExpenseTotal,
                    value = expense,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(tBalance, style = MaterialTheme.typography.labelLarge)
                    Text(
                        balanceText,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
        item {
            Text(tByCategory, style = MaterialTheme.typography.titleMedium)
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
            Text(tListHeader, style = MaterialTheme.typography.titleMedium)
        }
        if (details.isEmpty()) {
            item {
                Text(tNone)
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

    // نمونه تاریخ با تقویم زبان انتخابی (فارسی شمسی، عربی قمری، انگلیسی میلادی)
    val exampleDate = remember(regionType) { todayExample(regionType) }

    // همه متن‌ها و گزینه‌ها داخل بدنه Compose ساخته می‌شوند
    val kindOptions = listOf(
        0 to tr("چک", "Cheque", "شيك"),
        1 to tr("سفته", "Promissory note", "سند")
    )
    val directionOptions = listOf(
        0 to tr("دریافتنی", "Receivable", "مستحق لنا"),
        1 to tr("پرداختنی", "Payable", "مستحق علينا")
    )
    val remindOptions = REMIND_OPTIONS.map { d -> d to remindLabel(d) }
    val levelOptions = listOf(
        1 to tr("درجه ۱", "Level 1", "المستوى 1"),
        2 to tr("درجه ۲", "Level 2", "المستوى 2"),
        3 to tr("درجه ۳", "Level 3", "المستوى 3")
    )
    val tAddTitle = tr("ثبت چک یا سفته", "Add cheque or note", "إضافة شيك أو سند")
    val tPartyLabel = tr("طرف حساب", "Person / party", "الطرف")
    val tAmountLabel = stringResource(R.string.amount) + " (" + stringResource(R.string.currency) + ")"
    val tDueLabel = tr("سررسید", "Due date", "الاستحقاق") + " (" + tr("مثلاً", "e.g.", "مثال") + " " + exampleDate + ")"
    val tRemindTitle = tr("هشدار قبل از سررسید", "Remind before due date", "تنبيه قبل الاستحقاق")
    val tLevelTitle = tr("درجه اعلان", "Notification level", "درجة الإشعار")
    val tAddBtn = stringResource(R.string.add)
    val tUpcoming = tr("سررسیدهای نزدیک", "Upcoming due dates", "الاستحقاقات القريبة")
    val tAllTitle = tr("همه چک‌ها و سفته‌ها", "All cheques and notes", "كل الشيكات والسندات")
    val tNoEntries = tr("هنوز چکی ثبت نشده است.", "No entries yet.", "لا توجد عناصر بعد.")
    val tDueWord = tr("سررسید", "Due", "الاستحقاق")

    // متن خطاها هم اینجا ساخته می‌شوند، نه داخل onClick
    val errNameText = tr("نام طرف حساب را وارد کنید", "Enter the party name", "أدخل اسم الطرف")
    val errAmountText = tr("مبلغ معتبر وارد کنید", "Enter a valid amount", "أدخل مبلغاً صحيحاً")
    val errDateText = tr("تاریخ را مثل ", "Write the date like ", "اكتب التاريخ مثل ") +
        exampleDate + tr(" بنویسید", "", "")

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
                    Text(tAddTitle, style = MaterialTheme.typography.titleMedium)
                    EqualChipRow(
                        options = kindOptions,
                        selected = kind,
                        onSelect = { kind = it },
                        columns = 2
                    )
                    EqualChipRow(
                        options = directionOptions,
                        selected = direction,
                        onSelect = { direction = it },
                        columns = 2
                    )
                    OutlinedTextField(
                        value = person,
                        onValueChange = { person = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(tPartyLabel) }
                    )
                    OutlinedTextField(
                        value = amountText,
                        onValueChange = { amountText = digitsOnly(it) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        visualTransformation = ThousandsVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(tAmountLabel) }
                    )
                    OutlinedTextField(
                        value = dueText,
                        onValueChange = { dueText = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(tDueLabel) }
                    )
                    Text(tRemindTitle, style = MaterialTheme.typography.labelLarge)
                    EqualChipRow(
                        options = remindOptions,
                        selected = remindDays,
                        onSelect = { remindDays = it },
                        columns = 3
                    )
                    Text(tLevelTitle, style = MaterialTheme.typography.labelLarge)
                    EqualChipRow(
                        options = levelOptions,
                        selected = level,
                        onSelect = { level = it },
                        columns = 3
                    )
                    Text(levelText(level), style = MaterialTheme.typography.bodySmall)
                    errText?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Button(
                        onClick = {
                            val amount = amountText.toLongOrNull()
                            val due = parseDayIn(dueText, regionType)
                            when {
                                person.isBlank() -> errText = errNameText
                                amount == null || amount <= 0L -> errText = errAmountText
                                due == null -> errText = errDateText
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
                        Text(tAddBtn)
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
                        Text(tUpcoming, style = MaterialTheme.typography.titleSmall)
                    }
                }
            }
            items(alerts, key = { "alert_${it.id}" }) { c ->
                AlertLine(c = c, regionType = regionType, dueWord = tDueWord)
            }
        }
        item {
            Text(tAllTitle, style = MaterialTheme.typography.titleMedium)
        }
        if (cheques.isEmpty()) {
            item {
                Text(tNoEntries)
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
private fun AlertLine(c: Cheque, regionType: CalendarType, dueWord: String) {
    val dueText = formatWith(c.dueMillis, "yyyy/MM/dd", regionType.locale, regionType.timeZone)
    val currency = stringResource(R.string.currency)
    val header = "⚠ " + c.person + " • " + formatMoney(c.amount) + " " + currency
    val detail = dueWord + ": " + dueText + " • " + levelText(c.level)
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                header,
                style = MaterialTheme.typography.titleSmall,
                color = if (c.level == 1) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
            )
            Text(detail, style = MaterialTheme.typography.bodySmall)
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
    val tDueWord = tr("سررسید", "Due", "الاستحقاق")
    val tMarkActive = tr("برگشت به فعال", "Mark active", "إعادة التفعيل")
    val tMarkDone = tr("انجام شد", "Mark done", "تم")
    val currency = stringResource(R.string.currency)
    val dueText = formatWith(c.dueMillis, "yyyy/MM/dd", regionType.locale, regionType.timeZone)
    val remindText = remindLabel(c.remindDays)
    val levelDesc = levelText(c.level)

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(kindText + " • " + dirText, style = MaterialTheme.typography.labelLarge)
            Text(
                c.person,
                style = MaterialTheme.typography.titleMedium,
                textDecoration = if (c.done) androidx.compose.ui.text.style.TextDecoration.LineThrough else null
            )
            Text(
                formatMoney(c.amount) + " " + currency + " • " + tDueWord + ": " + dueText,
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                remindText + " • " + levelDesc,
                style = MaterialTheme.typography.bodySmall
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onToggle) {
                    Text(if (c.done) tMarkActive else tMarkDone)
                }
                TextButton(onClick = onDelete) {
                    Text(stringResource(R.string.delete))
                }
            }
        }
    }
}
