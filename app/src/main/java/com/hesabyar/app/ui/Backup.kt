package com.hesabyar.app.ui

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private const val NOTICE_PREFS = "hesabyar_prefs"
private const val NOTICE_KEY = "storage_notice_seen"

// ================= مدل‌ها و بازه زمانی =================

enum class BackupPeriod { DAILY, WEEKLY, MONTHLY, YEARLY, ALL, CUSTOM }

data class BackupRange(val from: Long, val to: Long)

data class BackupTx(
    val id: Long,
    val title: String,
    val amount: Long,
    val isIncome: Boolean,
    val category: String,
    val dateMillis: Long
)

data class BackupProduct(
    val id: Long,
    val name: String,
    val code: String?,
    val price: Long,
    val stock: Int
)

private fun startOfDay(c: Calendar): Long {
    c.set(Calendar.HOUR_OF_DAY, 0)
    c.set(Calendar.MINUTE, 0)
    c.set(Calendar.SECOND, 0)
    c.set(Calendar.MILLISECOND, 0)
    return c.timeInMillis
}

// بازه‌ها بر اساس تاریخ میلادی محاسبه می‌شوند. ALL یعنی بدون محدودیت زمانی.
fun backupRangeFor(period: BackupPeriod, customFrom: Long?, customTo: Long?): BackupRange? {
    val now = System.currentTimeMillis()
    val cal = Calendar.getInstance()
    return when (period) {
        BackupPeriod.DAILY -> BackupRange(startOfDay(cal), now)
        BackupPeriod.WEEKLY -> {
            cal.add(Calendar.DAY_OF_YEAR, -6)
            BackupRange(startOfDay(cal), now)
        }
        BackupPeriod.MONTHLY -> {
            cal.set(Calendar.DAY_OF_MONTH, 1)
            BackupRange(startOfDay(cal), now)
        }
        BackupPeriod.YEARLY -> {
            cal.set(Calendar.MONTH, Calendar.JANUARY)
            cal.set(Calendar.DAY_OF_MONTH, 1)
            BackupRange(startOfDay(cal), now)
        }
        BackupPeriod.ALL -> null
        BackupPeriod.CUSTOM -> {
            val from = requireNotNull(customFrom) { "تاریخ شروع معتبر نیست" }
            val to = requireNotNull(customTo) { "تاریخ پایان معتبر نیست" }
            val end = Calendar.getInstance().apply {
                timeInMillis = to
                set(Calendar.HOUR_OF_DAY, 23)
                set(Calendar.MINUTE, 59)
                set(Calendar.SECOND, 59)
                set(Calendar.MILLISECOND, 999)
            }
            BackupRange(from, end.timeInMillis)
        }
    }
}

// تبدیل متن yyyy/MM/dd میلادی به میلی‌ثانیه
fun parseBackupDay(text: String): Long? = try {
    val f = SimpleDateFormat("yyyy/MM/dd", Locale.US)
    f.isLenient = false
    f.parse(asciiDigits(text.trim()))?.time
} catch (e: Exception) {
    null
}

// ================= خواندن و نوشتن دیتابیس =================
// از SQL مستقیم روی فایل دیتابیس Room استفاده می‌کنیم تا به DAOهای قبلی وابسته نباشیم.

private fun openDb(ctx: Context, write: Boolean): SQLiteDatabase {
    val path = ctx.getDatabasePath("hesabyar.db").absolutePath
    val flags = if (write) SQLiteDatabase.OPEN_READWRITE else SQLiteDatabase.OPEN_READONLY
    return SQLiteDatabase.openDatabase(path, null, flags)
}

fun readTxForBackup(ctx: Context, range: BackupRange?): List<BackupTx> {
    val db = openDb(ctx, false)
    try {
        val base = "SELECT id, title, amount, isIncome, category, dateMillis FROM transactions"
        val cursor = if (range == null) db.rawQuery("$base ORDER BY dateMillis", null) else db.rawQuery("$base WHERE dateMillis BETWEEN ? AND ? ORDER BY dateMillis", arrayOf(range.from.toString(), range.to.toString()))
        cursor.use { c ->
            val out = ArrayList<BackupTx>()
            while (c.moveToNext()) {
                out.add(
                    BackupTx(
                        id = c.getLong(0),
                        title = c.getString(1) ?: "",
                        amount = c.getLong(2),
                        isIncome = c.getInt(3) != 0,
                        category = c.getString(4) ?: "",
                        dateMillis = c.getLong(5)
                    )
                )
            }
            return out
        }
    } finally {
        db.close()
    }
}

fun readProductsForBackup(ctx: Context): List<BackupProduct> {
    val db = openDb(ctx, false)
    try {
        db.rawQuery("SELECT id, name, code, price, stock FROM products", null).use { c ->
            val out = ArrayList<BackupProduct>()
            while (c.moveToNext()) {
                out.add(
                    BackupProduct(
                        id = c.getLong(0),
                        name = c.getString(1) ?: "",
                        code = if (c.isNull(2)) null else c.getString(2),
                        price = c.getLong(3),
                        stock = c.getInt(4)
                    )
                )
            }
            return out
        }
    } finally {
        db.close()
    }
}

// ================= JSON =================

fun exportBackupJson(out: OutputStream, txs: List<BackupTx>, products: List<BackupProduct>, periodName: String) {
    val tArr = JSONArray()
    for (t in txs) {
        tArr.put(
            JSONObject()
                .put("id", t.id)
                .put("title", t.title)
                .put("amount", t.amount)
                .put("isIncome", t.isIncome)
                .put("category", t.category)
                .put("dateMillis", t.dateMillis)
        )
    }
    val pArr = JSONArray()
    for (p in products) {
        pArr.put(
            JSONObject()
                .put("id", p.id)
                .put("name", p.name)
                .put("code", p.code ?: JSONObject.NULL)
                .put("price", p.price)
                .put("stock", p.stock)
        )
    }
    val root = JSONObject()
        .put("app", "hesabyar")
        .put("format", 1)
        .put("period", periodName)
        .put("exportedAt", System.currentTimeMillis())
        .put("transactions", tArr)
        .put("products", pArr)
    out.bufferedWriter(Charsets.UTF_8).use { it.write(root.toString(2)) }
}

// بازیابی: رکوردها بر اساس شناسه اضافه یا جایگزین می‌شوند. اطلاعات دیگر پاک نمی‌شود.
fun importBackupJson(ctx: Context, input: InputStream): Int {
    val text = input.bufferedReader(Charsets.UTF_8).use { it.readText() }
    val root = JSONObject(text)
    val tArr = root.optJSONArray("transactions") ?: JSONArray()
    val pArr = root.optJSONArray("products") ?: JSONArray()
    var count = 0
    val db = openDb(ctx, true)
    try {
        db.beginTransaction()
        try {
            for (i in 0 until tArr.length()) {
                val o = tArr.getJSONObject(i)
                val cv = ContentValues()
                cv.put("id", o.getLong("id"))
                cv.put("title", o.getString("title"))
                cv.put("amount", o.getLong("amount"))
                cv.put("isIncome", if (o.getBoolean("isIncome")) 1 else 0)
                cv.put("category", o.getString("category"))
                cv.put("dateMillis", o.getLong("dateMillis"))
                db.insertWithOnConflict("transactions", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
                count++
            }
            for (i in 0 until pArr.length()) {
                val o = pArr.getJSONObject(i)
                val cv = ContentValues()
                cv.put("id", o.getLong("id"))
                cv.put("name", o.getString("name"))
                cv.put("code", if (o.isNull("code")) null else o.getString("code"))
                cv.put("price", o.getLong("price"))
                cv.put("stock", o.getInt("stock"))
                db.insertWithOnConflict("products", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
                count++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    } finally {
        db.close()
    }
    return count
}

// ================= Excel (xlsx) بدون کتابخانه اضافی =================

fun exportBackupXlsx(out: OutputStream, txs: List<BackupTx>, products: List<BackupProduct>) {
    val fmt = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.US)
    val tRows = ArrayList<List<Any?>>()
    tRows.add(listOf("تاریخ", "نوع", "عنوان", "دسته‌بندی", "مبلغ"))
    for (t in txs) {
        tRows.add(
            listOf(
                fmt.format(Date(t.dateMillis)),
                if (t.isIncome) "درآمد" else "هزینه",
                t.title,
                t.category.removePrefix("custom:"),
                t.amount
            )
        )
    }
    val pRows = ArrayList<List<Any?>>()
    pRows.add(listOf("نام کالا", "کد", "قیمت", "موجودی"))
    for (p in products) {
        pRows.add(listOf(p.name, p.code ?: "", p.price, p.stock))
    }
    writeXlsx(out, listOf("تراکنش‌ها" to tRows, "محصولات" to pRows))
}

private fun writeXlsx(out: OutputStream, sheets: List<Pair<String, List<List<Any?>>>>) {
    ZipOutputStream(out).use { zip ->
        fun entry(name: String, content: String) {
            zip.putNextEntry(ZipEntry(name))
            zip.write(content.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }

        val header = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"

        val types = StringBuilder(header)
        types.append("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">")
        types.append("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>")
        types.append("<Default Extension=\"xml\" ContentType=\"application/xml\"/>")
        types.append("<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>")
        sheets.indices.forEach { i ->
            types.append("<Override PartName=\"/xl/worksheets/sheet${i + 1}.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>")
        }
        types.append("</Types>")
        entry("[Content_Types].xml", types.toString())

        entry(
            "_rels/.rels",
            header +
                "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>" +
                "</Relationships>"
        )

        val wb = StringBuilder(header)
        wb.append("<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets>")
        sheets.forEachIndexed { i, pair ->
            wb.append("<sheet name=\"${xmlEscape(pair.first)}\" sheetId=\"${i + 1}\" r:id=\"rId${i + 1}\"/>")
        }
        wb.append("</sheets></workbook>")
        entry("xl/workbook.xml", wb.toString())

        val rels = StringBuilder(header)
        rels.append("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">")
        sheets.indices.forEach { i ->
            rels.append("<Relationship Id=\"rId${i + 1}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet${i + 1}.xml\"/>")
        }
        rels.append("</Relationships>")
        entry("xl/_rels/workbook.xml.rels", rels.toString())

        sheets.forEachIndexed { i, pair ->
            entry("xl/worksheets/sheet${i + 1}.xml", sheetXml(pair.second))
        }
    }
}

private fun sheetXml(rows: List<List<Any?>>): String {
    val sb = StringBuilder()
    sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
    sb.append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>")
    rows.forEachIndexed { r, row ->
        sb.append("<row r=\"${r + 1}\">")
        row.forEachIndexed { c, v -> sb.append(cellXml("${colName(c)}${r + 1}", v)) }
        sb.append("</row>")
    }
    sb.append("</sheetData></worksheet>")
    return sb.toString()
}

private fun cellXml(ref: String, v: Any?): String = when (v) {
    null -> ""
    is Number -> "<c r=\"$ref\"><v>$v</v></c>"
    else -> "<c r=\"$ref\" t=\"inlineStr\"><is><t xml:space=\"preserve\">${xmlEscape(v.toString())}</t></is></c>"
}

private fun colName(index: Int): String {
    var n = index
    val sb = StringBuilder()
    do {
        sb.insert(0, 'A' + (n % 26))
        n = n / 26 - 1
    } while (n >= 0)
    return sb.toString()
}

private fun xmlEscape(s: String): String =
    s.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

// ================= رابط کاربری بکاپ =================

@Composable
private fun periodLabel(p: BackupPeriod): String = when (p) {
    BackupPeriod.DAILY -> tr("روزانه", "Daily", "يومي")
    BackupPeriod.WEEKLY -> tr("هفتگی", "Weekly", "أسبوعي")
    BackupPeriod.MONTHLY -> tr("ماهانه", "Monthly", "شهري")
    BackupPeriod.YEARLY -> tr("سالانه", "Yearly", "سنوي")
    BackupPeriod.ALL -> tr("همه", "All", "الكل")
    BackupPeriod.CUSTOM -> tr("از تا", "Custom", "مخصص")
}

private fun backupFileName(period: BackupPeriod, ext: String): String {
    val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
    return "hesabyar_${period.name.lowercase(Locale.US)}_$stamp.$ext"
}

@Composable
fun BackupSection(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    val msgOk = tr("انجام شد ✓", "Done ✓", "تم ✓")
    val msgErr = tr("خطا: ", "Error: ", "خطأ: ")
    val msgRestored = tr(
        "بازیابی انجام شد. برای دیدن تغییرات، برنامه را ببندید و دوباره باز کنید.",
        "Restored. Close and reopen the app to see the changes.",
        "تمت الاستعادة. أغلق التطبيق وأعد فتحه لرؤية التغييرات."
    )

    var period by remember { mutableStateOf(BackupPeriod.MONTHLY) }
    var fromText by remember { mutableStateOf("") }
    var toText by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var pendingRestore by remember { mutableStateOf<Uri?>(null) }

    val jsonSaver = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            val p = period
            val from = parseBackupDay(fromText)
            val to = parseBackupDay(toText)
            scope.launch {
                status = withContext(Dispatchers.IO) {
                    try {
                        val range = backupRangeFor(p, from, to)
                        val txs = readTxForBackup(ctx, range)
                        val products = readProductsForBackup(ctx)
                        val os = ctx.contentResolver.openOutputStream(uri) ?: error("file")
                        os.use { exportBackupJson(it, txs, products, p.name) }
                        msgOk
                    } catch (e: Exception) {
                        msgErr + (e.message ?: "")
                    }
                }
            }
        }
    }

    val xlsxSaver = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    ) { uri ->
        if (uri != null) {
            val p = period
            val from = parseBackupDay(fromText)
            val to = parseBackupDay(toText)
            scope.launch {
                status = withContext(Dispatchers.IO) {
                    try {
                        val range = backupRangeFor(p, from, to)
                        val txs = readTxForBackup(ctx, range)
                        val products = readProductsForBackup(ctx)
                        val os = ctx.contentResolver.openOutputStream(uri) ?: error("file")
                        os.use { exportBackupXlsx(it, txs, products) }
                        msgOk
                    } catch (e: Exception) {
                        msgErr + (e.message ?: "")
                    }
                }
            }
        }
    }

    val restorePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) pendingRestore = uri
    }

    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                tr("پشتیبان‌گیری و بازیابی", "Backup & restore", "النسخ الاحتياطي والاستعادة"),
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                tr(
                    "اطلاعات شما فقط روی همین گوشی ذخیره می‌شود. بازه‌ها بر اساس تاریخ میلادی حساب می‌شوند.",
                    "Your data is stored only on this phone. Periods use Gregorian dates.",
                    "تُحفظ بياناتك على هذا الهاتف فقط. الفترات تعتمد على التاريخ الميلادي."
                ),
                style = MaterialTheme.typography.bodySmall
            )

            Text(tr("بازه زمانی", "Period", "الفترة"), style = MaterialTheme.typography.labelLarge)
            BackupPeriod.entries.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { p ->
                        FilterChip(
                            selected = period == p,
                            onClick = { period = p },
                            label = { Text(periodLabel(p)) }
                        )
                    }
                }
            }

            if (period == BackupPeriod.CUSTOM) {
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

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { jsonSaver.launch(backupFileName(period, "json")) }) {
                    Text(tr("ذخیره فایل JSON", "Save JSON", "حفظ JSON"))
                }
                Button(onClick = { xlsxSaver.launch(backupFileName(period, "xlsx")) }) {
                    Text(tr("ذخیره اکسل", "Save Excel", "حفظ Excel"))
                }
            }
            Button(onClick = { restorePicker.launch(arrayOf("*/*")) }) {
                Text(tr("بازیابی از فایل JSON", "Restore from JSON", "استعادة من JSON"))
            }

            status?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
    }

    pendingRestore?.let { uri ->
        AlertDialog(
            onDismissRequest = { pendingRestore = null },
            title = { Text(tr("بازیابی بکاپ", "Restore backup", "استعادة النسخة")) },
            text = {
                Text(
                    tr(
                        "اطلاعات این فایل به اطلاعات فعلی اضافه یا جایگزین می‌شود.",
                        "Data from this file will be added to or replace current data.",
                        "ستُضاف بيانات هذا الملف إلى البيانات الحالية أو تستبدلها."
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val u = uri
                    pendingRestore = null
                    scope.launch {
                        status = withContext(Dispatchers.IO) {
                            try {
                                val stream = ctx.contentResolver.openInputStream(u) ?: error("file")
                                val n = stream.use { importBackupJson(ctx, it) }
                                "$msgRestored ($n)"
                            } catch (e: Exception) {
                                msgErr + (e.message ?: "")
                            }
                        }
                    }
                }) {
                    Text(tr("بازیابی", "Restore", "استعادة"))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingRestore = null }) {
                    Text(tr("انصراف", "Cancel", "إلغاء"))
                }
            }
        )
    }
}

// ================= هشدار اول اجرا =================

@Composable
fun FirstRunNotice() {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences(NOTICE_PREFS, Context.MODE_PRIVATE) }
    var show by remember { mutableStateOf(!prefs.getBoolean(NOTICE_KEY, false)) }

    if (show) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text(tr("قبل از شروع", "Before you start", "قبل البدء")) },
            text = {
                Text(
                    tr(
                        "اطلاعات مالی شما فقط روی همین گوشی ذخیره می‌شوند و به هیچ سرور یا ابری فرستاده نمی‌شوند.\n\nاگر برنامه حذف شود، گوشی عوض شود یا گوشی خراب شود، این اطلاعات از بین می‌رود. پیشنهاد می‌کنیم از بخش تنظیمات، بکاپ JSON یا اکسل بگیرید و فایل را در جای امنی نگه دارید.",
                        "Your financial data is stored only on this phone and is never sent to a server or cloud.\n\nIf the app is uninstalled, the phone is changed or damaged, this data will be lost. Please make a backup (JSON or Excel) from Settings and keep the file somewhere safe.",
                        "بياناتك المالية محفوظة على هذا الهاتف فقط ولا تُرسل إلى أي خادم أو سحابة.\n\nإذا حُذف التطبيق أو تغيّر الهاتف أو تعطّل، ستُفقد هذه البيانات. يُنصح بأخذ نسخة احتياطية (JSON أو Excel) من الإعدادات وحفظ الملف في مكان آمن."
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    prefs.edit().putBoolean(NOTICE_KEY, true).apply()
                    show = false
                }) {
                    Text(tr("متوجه شدم", "I understand", "فهمت"))
                }
            }
        )
    }
}
