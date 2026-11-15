package com.hesabyar.app.ui
import androidx.compose.ui.Alignment
import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.provider.Telephony
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// دیتابیس پیامک‌های واردشده؛ در بکاپ هم ذخیره می‌شود (خط BACKUP_DBS را ببین)
const val SMS_DB_NAME = "hesabyar_sms.db"
private const val SMS_PREFS = "hesabyar_sms"
private const val SMS_SENDERS_KEY = "senders"
private const val SMS_SCAN_LIMIT = 1000

data class SmsMessage(val sender: String, val body: String, val dateMillis: Long)

data class ImportedSms(
    val id: Long,
    val sender: String,
    val body: String,
    val dateMillis: Long,
    val amount: Long,
    val done: Boolean
)

private fun hasSmsPermission(ctx: Context): Boolean =
    ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

// ---------- سرشماره‌ها ----------

private fun loadSenders(ctx: Context): List<String> {
    val raw = ctx.getSharedPreferences(SMS_PREFS, Context.MODE_PRIVATE)
        .getString(SMS_SENDERS_KEY, null) ?: return emptyList()
    val arr = try {
        JSONArray(raw)
    } catch (e: Exception) {
        return emptyList()
    }
    return (0 until arr.length()).map { arr.getString(it) }
}

private fun saveSenders(ctx: Context, list: List<String>) {
    val arr = JSONArray()
    list.forEach { arr.put(it) }
    ctx.getSharedPreferences(SMS_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putString(SMS_SENDERS_KEY, arr.toString())
        .apply()
}

// مقایسه شماره: آخرین ۱۰ رقم، تا +98 و 0 جلوی شماره مشکلی نسازد
private fun digitsTail(s: String): String {
    val d = asciiDigits(s).filter { it.isDigit() }
    return if (d.length >= 7) d.takeLast(10) else ""
}

private fun sameSender(a: String, b: String): Boolean {
    val da = digitsTail(a)
    val db = digitsTail(b)
    if (da.isNotEmpty() || db.isNotEmpty()) return da == db
    return a.trim().equals(b.trim(), ignoreCase = true)
}

// ---------- خواندن پیامک‌ها ----------

private fun readInboxFor(ctx: Context, senders: List<String>): List<SmsMessage> {
    if (senders.isEmpty() || !hasSmsPermission(ctx)) return emptyList()
    val out = ArrayList<SmsMessage>()
    val cursor = ctx.contentResolver.query(
        Telephony.Sms.Inbox.CONTENT_URI,
        arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
        null,
        null,
        "${Telephony.Sms.DATE} DESC LIMIT $SMS_SCAN_LIMIT"
    ) ?: return out
    cursor.use { c ->
        val iAddress = c.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
        val iBody = c.getColumnIndexOrThrow(Telephony.Sms.BODY)
        val iDate = c.getColumnIndexOrThrow(Telephony.Sms.DATE)
        while (c.moveToNext()) {
            val address = c.getString(iAddress) ?: continue
            if (senders.none { sameSender(it, address) }) continue
            out.add(SmsMessage(address, c.getString(iBody) ?: "", c.getLong(iDate)))
        }
    }
    return out
}

// ---------- دیتابیس پیامک‌های واردشده ----------

private fun openSmsDb(ctx: Context): SQLiteDatabase {
    val file = ctx.getDatabasePath(SMS_DB_NAME)
    file.parentFile?.mkdirs()
    val db = SQLiteDatabase.openOrCreateDatabase(file, null)
    db.execSQL(
        "CREATE TABLE IF NOT EXISTS sms_imported (" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
            "msg_key TEXT UNIQUE, sender TEXT, body TEXT, " +
            "date_millis INTEGER, amount INTEGER, done INTEGER DEFAULT 0)"
    )
    return db
}

private fun importSms(ctx: Context, m: SmsMessage) {
    val db = openSmsDb(ctx)
    try {
        val cv = ContentValues()
        cv.put("msg_key", m.sender + "|" + m.dateMillis)
        cv.put("sender", m.sender)
        cv.put("body", m.body)
        cv.put("date_millis", m.dateMillis)
        cv.put("amount", guessAmount(m.body))
        cv.put("done", 0)
        db.insertWithOnConflict("sms_imported", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
    } finally {
        db.close()
    }
}

private fun loadImported(ctx: Context): List<ImportedSms> {
    val db = openSmsDb(ctx)
    val out = ArrayList<ImportedSms>()
    try {
        db.rawQuery(
            "SELECT id, sender, body, date_millis, amount, done FROM sms_imported ORDER BY date_millis DESC",
            null
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    ImportedSms(
                        id = c.getLong(0),
                        sender = c.getString(1) ?: "",
                        body = c.getString(2) ?: "",
                        dateMillis = c.getLong(3),
                        amount = c.getLong(4),
                        done = c.getInt(5) == 1
                    )
                )
            }
        }
    } finally {
        db.close()
    }
    return out
}

private fun markDone(ctx: Context, id: Long) {
    val db = openSmsDb(ctx)
    try {
        val cv = ContentValues()
        cv.put("done", 1)
        db.update("sms_imported", cv, "id = ?", arrayOf(id.toString()))
    } finally {
        db.close()
    }
}

private fun deleteImported(ctx: Context, id: Long) {
    val db = openSmsDb(ctx)
    try {
        db.delete("sms_imported", "id = ?", arrayOf(id.toString()))
    } finally {
        db.close()
    }
}

// ---------- پیشنهاد مبلغ و نوع از متن پیامک ----------

// بزرگ‌ترین عدد پیامک را پیشنهاد می‌دهد؛ کاربر می‌تواند تغییرش دهد
private fun guessAmount(body: String): Long {
    val nums = Regex("\\d[\\d,٬]*").findAll(asciiDigits(body)).mapNotNull { m ->
        m.value.filter { it.isDigit() }.toLongOrNull()
    }
    return nums.filter { it < 1_000_000_000_000L }.maxOrNull() ?: 0L
}

private fun guessIncome(body: String): Boolean {
    val t = body.lowercase()
    val income = listOf("واریز", "دریافت", "افزایش", "حقوق", "credit", "deposit", "received")
    val expense = listOf("برداشت", "خرید", "پرداخت", "کاهش", "debit", "withdraw", "paid", "purchase")
    val inc = income.any { t.contains(it) }
    val exp = expense.any { t.contains(it) }
    return inc && !exp
}

private fun smsDateText(millis: Long): String =
    SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.US).format(Date(millis))

// ---------- زیرتب پیامک‌ها در حسابداری ----------

@Composable
fun SmsTab(vm: MainViewModel) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    // همه متن‌ها اینجا ساخته می‌شوند
    val tIntro = tr(
        "برنامه فقط پیامک‌های سرشماره‌هایی را می‌خواند که خودتان اضافه کرده‌اید. هیچ پیامکی بدون اضافه کردن شما وارد برنامه نمی‌شود.",
        "The app reads only messages from the sender numbers you add. No message enters the app unless you add it.",
        "يقرأ التطبيق فقط الرسائل من الأرقام التي تضيفها. لا تدخل أي رسالة التطبيق إلا إذا أضفتها."
    )
    val tAskBtn = tr("اجازه دسترسی به پیامک‌ها", "Allow SMS access", "السماح بالوصول إلى الرسائل")
    val tAskTitle = tr("دسترسی به پیامک‌ها", "SMS access", "الوصول إلى الرسائل")
    val tAskBody = tr(
        "اگر اجازه بدهید، برنامه فقط پیامک‌های سرشماره‌هایی را که وارد می‌کنید می‌خواند. پیامک‌ها روی همین گوشی می‌مانند و جای دیگری فرستاده نمی‌شوند.",
        "If you allow it, the app reads only messages from the sender numbers you enter. Messages stay on this phone.",
        "إذا سمحت، سيقرأ التطبيق فقط الرسائل من الأرقام التي تدخلها. تبقى الرسائل على هذا الهاتف."
    )
    val tAllow = tr("اجازه می‌دهم", "Allow", "أسمح")
    val tCancel = tr("انصراف", "Cancel", "إلغاء")
    val tSendersTitle = tr("سرشماره‌های مجاز", "Allowed sender numbers", "أرقام المرسل المسموح بها")
    val tSenderLabel = tr("سرشماره یا نام فرستنده", "Sender number or name", "رقم المرسل أو اسمه")
    val tAddSender = tr("افزودن", "Add", "إضافة")
    val tNoSenders = tr("هنوز سرشماره‌ای اضافه نشده است.", "No sender numbers yet.", "لم تُضف أي أرقام بعد.")
    val tRemove = tr("حذف", "Delete", "حذف")
    val tScan = tr("نمایش پیامک‌های این سرشماره‌ها", "Show messages from these senders", "عرض الرسائل من هؤلاء المرسلين")
    val tLoading = tr("در حال خواندن پیامک‌ها...", "Reading messages...", "جارٍ قراءة الرسائل...")
    val tFoundTitle = tr("پیامک‌های پیدا شده", "Messages found", "الرسائل الموجودة")
    val tNothing = tr("پیامکی از این سرشماره‌ها پیدا نشد.", "No messages found from these senders.", "لم يُعثر على رسائل من هؤلاء المرسلين.")
    val tAddToApp = tr("افزودن به برنامه", "Add to app", "إضافة إلى التطبيق")
    val tAdded = tr("افزوده شد ✓", "Added ✓", "أُضيفت ✓")
    val tImportedTitle = tr("پیامک‌های افزوده‌شده", "Added messages", "الرسائل المضافة")
    val tImportedNone = tr("هنوز پیامکی به برنامه اضافه نشده است.", "No messages added yet.", "لم تُضف أي رسائل بعد.")
    val tToTx = tr("ثبت به عنوان تراکنش", "Record as transaction", "تسجيل كمعاملة")
    val tDeleteMsg = tr("حذف از برنامه", "Remove from app", "إزالة من التطبيق")
    val tDone = tr("ثبت شد ✓", "Recorded ✓", "سُجّلت ✓")
    val tPermNeeded = tr(
        "برای خواندن پیامک‌ها، اول اجازه دسترسی را بدهید.",
        "Allow SMS access first to read messages.",
        "اسمح بالوصول إلى الرسائل أولاً لقراءة الرسائل."
    )
    val tNeedSender = tr("اول یک سرشماره اضافه کنید.", "Add a sender number first.", "أضف رقم مرسل أولاً.")
    val tSavedTx = tr("تراکنش ثبت شد ✓", "Transaction recorded ✓", "تم تسجيل المعاملة ✓")
    val tNoPerm = tr("اجازه پیامک داده نشد.", "SMS permission was not granted.", "لم يُمنح إذن الرسائل.")

    var granted by remember { mutableStateOf(hasSmsPermission(ctx)) }
    var askDialog by remember { mutableStateOf(false) }
    var senders by remember { mutableStateOf(loadSenders(ctx)) }
    var newSender by remember { mutableStateOf("") }
    var found by remember { mutableStateOf<List<SmsMessage>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var imported by remember { mutableStateOf(loadImported(ctx)) }
    var editing by remember { mutableStateOf<ImportedSms?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { ok ->
        granted = ok
        message = if (ok) null else tNoPerm
    }

    fun scan() {
        if (!granted) {
            message = tPermNeeded
            return
        }
        if (senders.isEmpty()) {
            message = tNeedSender
            return
        }
        scope.launch {
            loading = true
            found = withContext(Dispatchers.IO) { readInboxFor(ctx, senders) }
            loading = false
            message = null
        }
    }

    fun importNow(m: SmsMessage) {
        scope.launch {
            withContext(Dispatchers.IO) { importSms(ctx, m) }
            imported = withContext(Dispatchers.IO) { loadImported(ctx) }
        }
    }

    fun removeImported(id: Long) {
        scope.launch {
            withContext(Dispatchers.IO) { deleteImported(ctx, id) }
            imported = withContext(Dispatchers.IO) { loadImported(ctx) }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // توضیح و دکمه اجازه
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(tIntro, style = MaterialTheme.typography.bodySmall)
                if (!granted) {
                    Button(onClick = { askDialog = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(tAskBtn)
                    }
                }
            }
        }

        // سرشماره‌های مجاز
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(tSendersTitle, style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = newSender,
                    onValueChange = { newSender = it },
                    singleLine = true,
                    label = { Text(tSenderLabel) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = {
                        val value = asciiDigits(newSender.trim()).ifBlank { newSender.trim() }
                        if (value.isNotBlank() && senders.none { sameSender(it, value) }) {
                            val list = senders + value
                            saveSenders(ctx, list)
                            senders = list
                        }
                        newSender = ""
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(tAddSender)
                }

                if (senders.isEmpty()) {
                    Text(tNoSenders, style = MaterialTheme.typography.bodySmall)
                }
                senders.forEach { s ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(s, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = {
                            val list = senders.filter { it != s }
                            saveSenders(ctx, list)
                            senders = list
                        }) {
                            Text(tRemove)
                        }
                    }
                }

                OutlinedButton(onClick = { scan() }, modifier = Modifier.fillMaxWidth()) {
                    Text(tScan)
                }
            }
        }

        message?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        if (loading) {
            Text(tLoading, style = MaterialTheme.typography.bodySmall)
        }

        // پیامک‌های پیدا شده
        if (found.isNotEmpty()) {
            Text(tFoundTitle, style = MaterialTheme.typography.titleMedium)
            found.forEach { m ->
                val already = imported.any { it.sender == m.sender && it.dateMillis == m.dateMillis }
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            m.sender + "  ·  " + smsDateText(m.dateMillis),
                            style = MaterialTheme.typography.labelMedium
                        )
                        Text(m.body, style = MaterialTheme.typography.bodyMedium)
                        if (already) {
                            Text(tAdded, style = MaterialTheme.typography.labelLarge)
                        } else {
                            Button(onClick = { importNow(m) }) { Text(tAddToApp) }
                        }
                    }
                }
            }
        } else if (!loading && found.isEmpty() && message == null && senders.isNotEmpty()) {
            // اگر قبلاً جست‌وجو شده و چیزی نبوده، پیام نشان داده شود
        }

        // پیامک‌های افزوده‌شده به برنامه
        Text(tImportedTitle, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        if (imported.isEmpty()) {
            Text(tImportedNone, style = MaterialTheme.typography.bodySmall)
        }
        imported.forEach { item ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        item.sender + "  ·  " + smsDateText(item.dateMillis),
                        style = MaterialTheme.typography.labelMedium
                    )
                    Text(item.body, style = MaterialTheme.typography.bodyMedium)
                    if (item.amount > 0) {
                        Text(formatMoney(item.amount), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (item.done) {
                            Text(tDone, style = MaterialTheme.typography.labelLarge)
                        } else {
                            Button(onClick = { editing = item }) { Text(tToTx) }
                        }
                        TextButton(onClick = { removeImported(item.id) }) { Text(tDeleteMsg) }
                    }
                }
            }
        }
    }

    if (askDialog) {
        AlertDialog(
            onDismissRequest = { askDialog = false },
            title = { Text(tAskTitle) },
            text = { Text(tAskBody) },
            confirmButton = {
                TextButton(onClick = {
                    askDialog = false
                    permLauncher.launch(Manifest.permission.READ_SMS)
                }) {
                    Text(tAllow)
                }
            },
            dismissButton = {
                TextButton(onClick = { askDialog = false }) { Text(tCancel) }
            }
        )
    }

    editing?.let { item ->
        SmsTransactionDialog(
            item = item,
            onDismiss = { editing = null },
            onSave = { title, amount, isIncome ->
                vm.addTransaction(title, amount, isIncome, if (isIncome) "other_income" else "other_expense")
                val id = item.id
                scope.launch {
                    withContext(Dispatchers.IO) { markDone(ctx, id) }
                    imported = withContext(Dispatchers.IO) { loadImported(ctx) }
                }
                editing = null
                message = tSavedTx
            }
        )
    }
}

@Composable
private fun SmsTransactionDialog(
    item: ImportedSms,
    onDismiss: () -> Unit,
    onSave: (String, Long, Boolean) -> Unit
) {
    val tTitle = tr("ثبت تراکنش از پیامک", "Record transaction from SMS", "تسجيل معاملة من الرسالة")
    val tName = tr("شرح", "Description", "الوصف")
    val tIncome = tr("درآمد", "Income", "دخل")
    val tExpense = tr("هزینه", "Expense", "مصروف")
    val tAmount = tr("مبلغ", "Amount", "المبلغ")
    val tSave = tr("ثبت", "Save", "حفظ")
    val tCancel = tr("انصراف", "Cancel", "إلغاء")
    val tHint = tr(
        "مبلغ و نوع پیشنهادی است؛ اگر درست نبود، تغییرش بده.",
        "The amount and type are suggestions. Change them if needed.",
        "المبلغ والنوع اقتراح؛ غيّرهما إن لزم الأمر."
    )

    var title by remember { mutableStateOf(item.body.take(40)) }
    var amountText by remember { mutableStateOf(if (item.amount > 0) formatMoney(item.amount) else "") }
    var isIncome by remember { mutableStateOf(guessIncome(item.body)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tTitle) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tHint, style = MaterialTheme.typography.bodySmall)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilterChip(
                        selected = !isIncome,
                        onClick = { isIncome = false },
                        label = { Text(tExpense) },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = isIncome,
                        onClick = { isIncome = true },
                        label = { Text(tIncome) },
                        modifier = Modifier.weight(1f)
                    )
                }
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    singleLine = true,
                    label = { Text(tName) },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { raw ->
                        val d = asciiDigits(raw).filter { it.isDigit() }.take(15)
                        amountText = if (d.isEmpty()) "" else formatMoney(d.toLong())
                    },
                    singleLine = true,
                    label = { Text(tAmount) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            val amount = asciiDigits(amountText).filter { it.isDigit() }.toLongOrNull() ?: 0L
            TextButton(
                onClick = { onSave(title.trim().ifBlank { item.body.take(40) }, amount, isIncome) },
                enabled = amount > 0
            ) {
                Text(tSave)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(tCancel) }
        }
    )
}
