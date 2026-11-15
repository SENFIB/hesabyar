package com.hesabyar.app.ui

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

private const val NOTICE_PREFS = "hesabyar_prefs"
private const val NOTICE_KEY = "storage_notice_seen"

private const val BACKUP_APP = "hesabyar"
private const val BACKUP_FORMAT = 2
private const val IMAGE_DIR = "inv_images"
private const val IMAGE_ENTRY_PREFIX = "images/"
private const val DB_MAIN = "hesabyar.db"
private const val DB_CHEQUES = "hesabyar_cheques.db"
private const val DB_INVENTORY = "hesabyar_inventory.db"
private const val REASON_NOT_BACKUP = "not_backup"
private const val REASON_NEWER = "newer"

private val BACKUP_DBS = listOf(DB_MAIN, DB_CHEQUES, DB_INVENTORY)
private val BACKUP_PREFS = listOf("hesabyar_prefs", "hesabyar_theme", "hesabyar_weather")
private val SKIP_PREF_KEYS = setOf("storage_notice_seen", "geo_cache", "geo_cache_key")

// ستون‌های تاریخ که بازه زمانی روی آن‌ها اعمال می‌شود
private val DATE_COLUMNS = mapOf("transactions" to "dateMillis", "sales" to "date_millis")

// جدول‌هایی که در فایل اکسل شیت جدا می‌گیرند
private val EXCEL_SHEETS = mapOf(
    "transactions" to "تراکنش‌ها",
    "products" to "محصولات",
    "cheques" to "چک و سفته",
    "items" to "انبار",
    "sales" to "فروش"
)

private val COLUMN_LABELS = mapOf(
    "id" to "شناسه",
    "title" to "عنوان",
    "amount" to "مبلغ",
    "isIncome" to "نوع",
    "category" to "دسته‌بندی",
    "dateMillis" to "تاریخ",
    "name" to "نام",
    "code" to "کد",
    "price" to "قیمت",
    "stock" to "موجودی",
    "kind" to "نوع سند",
    "direction" to "جهت",
    "person" to "طرف حساب",
    "dueMillis" to "سررسید",
    "remindDays" to "یادآوری (روز)",
    "level" to "درجه اعلان",
    "done" to "انجام شد",
    "description" to "توضیحات",
    "threshold" to "حد هشدار",
    "views" to "تعداد بازدید",
    "item_id" to "شناسه کالا",
    "item_name" to "نام کالا",
    "qty" to "تعداد",
    "unit_price" to "قیمت واحد",
    "total" to "جمع",
    "date_millis" to "تاریخ"
)

// ================= مدل‌ها و بازه زمانی =================

enum class BackupPeriod { DAILY, WEEKLY, MONTHLY, YEARLY, ALL, CUSTOM }

data class BackupRange(val from: Long, val to: Long)

private enum class ExportKind { FULL, JSON, EXCEL }

private class BackupBundle(
    val header: JSONObject,
    val full: JSONObject,
    val images: Map<String, File>
)

private class BackupFileException(val reason: String) : Exception(reason)

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

// ================= ساخت بکاپ =================

private fun dbFile(ctx: Context, name: String): File = ctx.getDatabasePath(name)

private fun openDbFile(ctx: Context, name: String, write: Boolean): SQLiteDatabase {
    val file = dbFile(ctx, name)
    file.parentFile?.mkdirs()
    return if (write) {
        SQLiteDatabase.openOrCreateDatabase(file, null)
    } else {
        SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
    }
}

private fun tableNames(db: SQLiteDatabase): List<String> {
    val out = ArrayList<String>()
    db.rawQuery(
        "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'android_%' AND name NOT LIKE 'room_%'",
        null
    ).use { c ->
        while (c.moveToNext()) out.add(c.getString(0))
    }
    return out
}

private fun tableSql(db: SQLiteDatabase, table: String): String? =
    db.rawQuery("SELECT sql FROM sqlite_master WHERE type='table' AND name=?", arrayOf(table)).use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    }

private fun imageFileOf(ctx: Context, path: String): File {
    val f = File(path)
    return if (f.isAbsolute) f else File(ctx.filesDir, path)
}

private fun dumpTable(
    ctx: Context,
    db: SQLiteDatabase,
    table: String,
    range: BackupRange?,
    images: MutableMap<String, File>
): JSONObject {
    val dateColumn = DATE_COLUMNS[table]
    val args: Array<String>? = if (range != null && dateColumn != null) {
        arrayOf(range.from.toString(), range.to.toString())
    } else {
        null
    }
    val sql = if (args != null) {
        "SELECT * FROM \"$table\" WHERE \"$dateColumn\" BETWEEN ? AND ?"
    } else {
        "SELECT * FROM \"$table\""
    }

    val columns = JSONArray()
    val rows = JSONArray()
    db.rawQuery(sql, args).use { c ->
        val names = c.columnNames
        names.forEach { columns.put(it) }
        val imageIndex = names.indexOf("image_path")
        while (c.moveToNext()) {
            val row = JSONObject()
            for (i in names.indices) {
                var value: Any = when (c.getType(i)) {
                    Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                    Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
                    Cursor.FIELD_TYPE_STRING -> c.getString(i)
                    else -> JSONObject.NULL
                }
                // عکس کالا: فایل را همراه بکاپ می‌بریم و مسیر را به نام داخل فایل تبدیل می‌کنیم
                if (i == imageIndex && value is String && value.isNotBlank()) {
                    val file = imageFileOf(ctx, value)
                    value = if (file.exists()) {
                        val entry = IMAGE_ENTRY_PREFIX + file.name
                        images[entry] = file
                        entry
                    } else {
                        JSONObject.NULL
                    }
                }
                row.put(names[i], value)
            }
            rows.put(row)
        }
    }

    return JSONObject()
        .put("name", table)
        .put("sql", tableSql(db, table) ?: JSONObject.NULL)
        .put("columns", columns)
        .put("rows", rows)
}

private fun encodePref(value: Any?): JSONObject? {
    val o = JSONObject()
    when (value) {
        is String -> o.put("t", "s").put("v", value)
        is Boolean -> o.put("t", "b").put("v", value)
        is Int -> o.put("t", "i").put("v", value)
        is Long -> o.put("t", "l").put("v", value)
        is Float -> o.put("t", "f").put("v", value.toDouble())
        is Set<*> -> {
            val arr = JSONArray()
            value.filterIsInstance<String>().forEach { arr.put(it) }
            o.put("t", "set").put("v", arr)
        }
        else -> return null
    }
    return o
}

private fun buildBundle(ctx: Context, range: BackupRange?, periodName: String): BackupBundle {
    val images = LinkedHashMap<String, File>()
    val databases = JSONObject()
    val counts = JSONObject()

    for (dbName in BACKUP_DBS) {
        if (!dbFile(ctx, dbName).exists()) continue
        val db = openDbFile(ctx, dbName, false)
        val tables = JSONArray()
        var version = 0
        try {
            version = db.version
            for (table in tableNames(db)) {
                val dump = dumpTable(ctx, db, table, range, images)
                tables.put(dump)
                counts.put(table, dump.getJSONArray("rows").length())
            }
        } finally {
            db.close()
        }
        databases.put(dbName, JSONObject().put("version", version).put("tables", tables))
    }

    val prefs = JSONObject()
    for (name in BACKUP_PREFS) {
        val sp = ctx.getSharedPreferences(name, Context.MODE_PRIVATE)
        val obj = JSONObject()
        for ((key, value) in sp.all) {
            if (key in SKIP_PREF_KEYS) continue
            val enc = encodePref(value) ?: continue
            obj.put(key, enc)
        }
        prefs.put(name, obj)
    }

    val header = JSONObject()
        .put("app", BACKUP_APP)
        .put("format", BACKUP_FORMAT)
        .put("period", periodName)
        .put("createdAt", System.currentTimeMillis())
        .put("counts", counts)
        .put("images", images.size)

    val full = JSONObject(header.toString())
        .put("databases", databases)
        .put("prefs", prefs)

    return BackupBundle(header, full, images)
}

private fun countRecords(counts: JSONObject): Int {
    var sum = 0
    val keys = counts.keys()
    while (keys.hasNext()) sum += counts.optInt(keys.next())
    return sum
}

// ================= نوشتن فایل‌ها =================

private fun putText(zip: ZipOutputStream, name: String, text: String) {
    zip.putNextEntry(ZipEntry(name))
    zip.write(text.toByteArray(Charsets.UTF_8))
    zip.closeEntry()
}

private fun writeZipBackup(out: OutputStream, b: BackupBundle) {
    ZipOutputStream(out).use { zip ->
        putText(zip, "manifest.json", b.header.toString(2))
        putText(zip, "data.json", b.full.toString())
        for ((name, file) in b.images) {
            zip.putNextEntry(ZipEntry(name))
            file.inputStream().use { input -> input.copyTo(zip) }
            zip.closeEntry()
        }
    }
}

private fun writeJsonBackup(out: OutputStream, b: BackupBundle) {
    out.bufferedWriter(Charsets.UTF_8).use { it.write(b.full.toString(2)) }
}

private fun runExport(ctx: Context, uri: Uri, kind: ExportKind, period: BackupPeriod, from: Long?, to: Long?): Int {
    val range = backupRangeFor(period, from, to)
    val bundle = buildBundle(ctx, range, period.name)
    val os = ctx.contentResolver.openOutputStream(uri) ?: error("file")
    os.use {
        when (kind) {
            ExportKind.FULL -> writeZipBackup(it, bundle)
            ExportKind.JSON -> writeJsonBackup(it, bundle)
            ExportKind.EXCEL -> writeExcelBackup(it, bundle)
        }
    }
    return countRecords(bundle.header.getJSONObject("counts"))
}

// ================= خروجی اکسل =================

private fun isDateColumn(c: String) = c == "dateMillis" || c == "dueMillis" || c == "date_millis"

private fun xlsxCell(column: String, value: Any?): Any {
    if (value == null || value === JSONObject.NULL) return ""
    return when {
        column == "isIncome" -> if (value.toString() == "1") "درآمد" else "هزینه"
        column == "category" -> value.toString().removePrefix("custom:")
        isDateColumn(column) && value is Number ->
            SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.US).format(Date(value.toLong()))
        else -> value
    }
}

private fun writeExcelBackup(out: OutputStream, b: BackupBundle) {
    val dbs = b.full.getJSONObject("databases")
    val sheets = ArrayList<Pair<String, List<List<Any?>>>>()
    for (dbName in BACKUP_DBS) {
        val tables = dbs.optJSONObject(dbName)?.optJSONArray("tables") ?: continue
        for (i in 0 until tables.length()) {
            val t = tables.getJSONObject(i)
            val sheetName = EXCEL_SHEETS[t.getString("name")] ?: continue
            val cols = t.getJSONArray("columns")
            val names = ArrayList<String>()
            for (c in 0 until cols.length()) {
                val col = cols.getString(c)
                if (col != "image_path") names.add(col)
            }
            val rows = ArrayList<List<Any?>>()
            rows.add(names.map { COLUMN_LABELS[it] ?: it })
            val data = t.getJSONArray("rows")
            for (r in 0 until data.length()) {
                val row = data.getJSONObject(r)
                rows.add(names.map { col -> xlsxCell(col, row.opt(col)) })
            }
            sheets.add(sheetName to rows)
        }
    }
    writeXlsx(out, sheets)
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

// ================= بازیابی =================

// فایل را از روی محتوایش تشخیص می‌دهد: ZIP (حسابیار کامل) یا JSON (قدیمی یا JSON جدید)
fun restoreBackup(ctx: Context, input: InputStream): Int {
    val bis = BufferedInputStream(input)
    bis.mark(4)
    val first = bis.read()
    val second = bis.read()
    bis.reset()
    return if (first == 0x50 && second == 0x4B) restoreZip(ctx, bis) else restoreJson(ctx, bis)
}

private fun restoreZip(ctx: Context, input: InputStream): Int {
    var manifestText: String? = null
    var dataText: String? = null
    ZipInputStream(input).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: break
            when {
                entry.name == "manifest.json" -> manifestText = zip.readBytes().toString(Charsets.UTF_8)
                entry.name == "data.json" -> dataText = zip.readBytes().toString(Charsets.UTF_8)
                entry.name.startsWith(IMAGE_ENTRY_PREFIX) -> saveImage(ctx, entry.name, zip)
            }
            zip.closeEntry()
        }
    }
    val header = JSONObject(manifestText ?: throw BackupFileException(REASON_NOT_BACKUP))
    if (header.optString("app") != BACKUP_APP) throw BackupFileException(REASON_NOT_BACKUP)
    if (header.optInt("format", 0) > BACKUP_FORMAT) throw BackupFileException(REASON_NEWER)
    val data = JSONObject(dataText ?: throw BackupFileException(REASON_NOT_BACKUP))
    return applyData(ctx, data)
}

private fun restoreJson(ctx: Context, input: InputStream): Int {
    val text = input.bufferedReader(Charsets.UTF_8).use { it.readText() }
    val root = try {
        JSONObject(text)
    } catch (e: Exception) {
        throw BackupFileException(REASON_NOT_BACKUP)
    }
    if (root.has("app") && root.optString("app") != BACKUP_APP) throw BackupFileException(REASON_NOT_BACKUP)
    if (root.optInt("format", 0) > BACKUP_FORMAT) throw BackupFileException(REASON_NEWER)
    return when {
        root.has("databases") -> applyData(ctx, root)
        root.has("transactions") -> importLegacy(ctx, root)
        else -> throw BackupFileException(REASON_NOT_BACKUP)
    }
}

private fun saveImage(ctx: Context, entryName: String, zip: ZipInputStream) {
    val name = entryName.substringAfterLast('/')
    if (name.isBlank() || name == "." || name == "..") return
    val dir = File(ctx.filesDir, IMAGE_DIR)
    dir.mkdirs()
    File(dir, name).outputStream().use { zip.copyTo(it) }
}

// مسیر عکس را برای این گوشی درست می‌کند؛ اگر عکسی نباشد، خالی می‌گذارد
private fun imagePathFor(ctx: Context, value: String): String? {
    if (value.startsWith(IMAGE_ENTRY_PREFIX)) {
        val f = File(File(ctx.filesDir, IMAGE_DIR), value.substringAfterLast('/'))
        return if (f.exists()) f.absolutePath else null
    }
    return if (File(value).exists()) value else null
}

private fun putValue(ctx: Context, cv: ContentValues, key: String, value: Any?) {
    when (value) {
        null -> cv.putNull(key)
        JSONObject.NULL -> cv.putNull(key)
        is Int -> cv.put(key, value.toLong())
        is Long -> cv.put(key, value)
        is Double -> cv.put(key, value)
        is String -> {
            if (key == "image_path") cv.put(key, imagePathFor(ctx, value)) else cv.put(key, value)
        }
        else -> cv.putNull(key)
    }
}

private fun tableExists(db: SQLiteDatabase, table: String): Boolean =
    db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", arrayOf(table))
        .use { it.moveToFirst() }

private fun restoreTable(ctx: Context, db: SQLiteDatabase, t: JSONObject): Int {
    val table = t.getString("name")
    if (!tableExists(db, table)) {
        // اگر جدول در گوشی جدید وجود ندارد، از همان تعریف ذخیره‌شده در بکاپ ساخته می‌شود
        val sql = t.optString("sql", "")
        if (sql.isBlank()) return 0
        db.execSQL(sql.replaceFirst("CREATE TABLE", "CREATE TABLE IF NOT EXISTS", ignoreCase = true))
    }
    val rows = t.optJSONArray("rows") ?: return 0
    var count = 0
    for (i in 0 until rows.length()) {
        val row = rows.getJSONObject(i)
        val cv = ContentValues()
        val keys = row.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            putValue(ctx, cv, key, row.opt(key))
        }
        val id = db.insertWithOnConflict(table, null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        if (id == -1L) throw IllegalStateException("insert failed: $table")
        count++
    }
    return count
}

private fun applyData(ctx: Context, data: JSONObject): Int {
    var total = 0
    val dbs = data.optJSONObject("databases") ?: JSONObject()
    for (dbName in BACKUP_DBS) {
        val obj = dbs.optJSONObject(dbName) ?: continue
        val tables = obj.optJSONArray("tables") ?: continue
        val version = obj.optInt("version", 0)
        val db = openDbFile(ctx, dbName, true)
        try {
            // دیتابیس تازه نسخه‌اش صفر است؛ نسخه بکاپ را می‌گذاریم تا برنامه دوباره جدول‌ها را نسازد
            if (db.version == 0 && version > 0) db.version = version
            db.beginTransaction()
            try {
                for (i in 0 until tables.length()) {
                    total += restoreTable(ctx, db, tables.getJSONObject(i))
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        } finally {
            db.close()
        }
    }
    restorePrefs(ctx, data.optJSONObject("prefs"))
    return total
}

private fun restorePrefs(ctx: Context, prefs: JSONObject?) {
    if (prefs == null) return
    val fileNames = prefs.keys()
    while (fileNames.hasNext()) {
        val fileName = fileNames.next()
        if (fileName !in BACKUP_PREFS) continue
        val obj = prefs.optJSONObject(fileName) ?: continue
        val editor = ctx.getSharedPreferences(fileName, Context.MODE_PRIVATE).edit()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key in SKIP_PREF_KEYS) continue
            val item = obj.optJSONObject(key) ?: continue
            when (item.optString("t")) {
                "s" -> editor.putString(key, item.optString("v"))
                "b" -> editor.putBoolean(key, item.optBoolean("v"))
                "i" -> editor.putInt(key, item.optInt("v"))
                "l" -> editor.putLong(key, item.optLong("v"))
                "f" -> editor.putFloat(key, item.optDouble("v").toFloat())
                "set" -> {
                    val arr = item.optJSONArray("v") ?: JSONArray()
                    val set = HashSet<String>()
                    for (i in 0 until arr.length()) set.add(arr.getString(i))
                    editor.putStringSet(key, set)
                }
            }
        }
        editor.apply()
    }
}

// فرمت قدیمی (نسخه قبلی برنامه): فقط تراکنش‌ها و محصولات
private fun importLegacy(ctx: Context, root: JSONObject): Int {
    val tArr = root.optJSONArray("transactions") ?: JSONArray()
    val pArr = root.optJSONArray("products") ?: JSONArray()
    var count = 0
    val db = openDbFile(ctx, DB_MAIN, true)
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
    val msgBadDates = tr(
        "تاریخ‌ها را مثل 2025/01/31 وارد کنید.",
        "Enter dates like 2025/01/31.",
        "أدخل التواريخ بالشكل 2025/01/31."
    )
    val msgNotBackup = tr(
        "این فایل، پشتیبان حسابیار نیست.",
        "This file is not a Hesabyar backup.",
        "هذا الملف ليس نسخة احتياطية من حسابيار."
    )
    val msgNewer = tr(
        "این پشتیبان با نسخه جدیدتر برنامه ساخته شده. برنامه را به‌روز کنید.",
        "This backup was made by a newer version. Please update the app.",
        "أُنشئت هذه النسخة بإصدار أحدث من التطبيق. يُرجى تحديثه."
    )
    val msgRestored = tr(
        "بازیابی انجام شد. برای دیدن همه تغییرات، برنامه را ببندید و دوباره باز کنید.",
        "Restored. Close and reopen the app to see all changes.",
        "تمت الاستعادة. أغلق التطبيق وأعد فتحه لرؤية كل التغييرات."
    )
    val msgPeriodNote = tr(
        "بازه زمانی فقط روی تراکنش‌ها و فروش‌ها اعمال می‌شود. چک‌ها، کالاها، عکس‌ها و تنظیمات همیشه کامل ذخیره می‌شوند.",
        "The period applies only to transactions and sales. Cheques, items, photos and settings are always saved in full.",
        "تنطبق الفترة على المعاملات والمبيعات فقط. تُحفظ الشيكات والمنتجات والصور والإعدادات دائماً بالكامل."
    )

    var period by remember { mutableStateOf(BackupPeriod.ALL) }
    var fromText by remember { mutableStateOf("") }
    var toText by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var pendingRestore by remember { mutableStateOf<Uri?>(null) }

    fun customRangeOk(): Boolean =
        period != BackupPeriod.CUSTOM ||
            (parseBackupDay(fromText) != null && parseBackupDay(toText) != null)

    fun doExport(uri: Uri, kind: ExportKind) {
        val p = period
        val from = parseBackupDay(fromText)
        val to = parseBackupDay(toText)
        scope.launch {
            busy = true
            status = withContext(Dispatchers.IO) {
                try {
                    val n = runExport(ctx, uri, kind, p, from, to)
                    "$msgOk ($n)"
                } catch (e: Exception) {
                    msgErr + (e.message ?: "")
                }
            }
            busy = false
        }
    }

    fun doRestore(uri: Uri) {
        scope.launch {
            busy = true
            status = withContext(Dispatchers.IO) {
                try {
                    val stream = ctx.contentResolver.openInputStream(uri) ?: error("file")
                    val n = stream.use { restoreBackup(ctx, it) }
                    "$msgRestored ($n)"
                } catch (e: BackupFileException) {
                    if (e.reason == REASON_NEWER) msgNewer else msgNotBackup
                } catch (e: Exception) {
                    msgErr + (e.message ?: "")
                }
            }
            busy = false
        }
    }

    val fullSaver = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        if (uri != null) doExport(uri, ExportKind.FULL)
    }

    val jsonSaver = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) doExport(uri, ExportKind.JSON)
    }

    val xlsxSaver = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    ) { uri ->
        if (uri != null) doExport(uri, ExportKind.EXCEL)
    }

    val restorePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) pendingRestore = uri
    }

    val titleText = tr("پشتیبان‌گیری و بازیابی", "Backup & restore", "النسخ الاحتياطي والاستعادة")
    val introText = tr(
        "اطلاعات شما فقط روی همین گوشی ذخیره می‌شود. برای انتقال به گوشی جدید یا بازیابی بعد از حذف برنامه، «پشتیبان کامل» را بگیرید.",
        "Your data is stored only on this phone. To move to a new phone or recover after reinstalling, create a Full backup.",
        "تُحفظ بياناتك على هذا الهاتف فقط. لنقلها إلى هاتف جديد أو استعادتها بعد إعادة تثبيت التطبيق، أنشئ نسخة كاملة."
    )
    val periodTitle = tr("بازه زمانی", "Period", "الفترة")
    val createTitle = tr("ساخت پشتیبان", "Create backup", "إنشاء نسخة احتياطية")
    val btnFull = tr("پشتیبان کامل", "Full backup", "نسخة كاملة")
    val btnJson = tr("خروجی JSON", "JSON export", "تصدير JSON")
    val btnExcel = tr("خروجی اکسل", "Excel export", "تصدير Excel")
    val hintFull = tr(
        "کامل: همه اطلاعات، عکس کالاها و تنظیمات. برای بازیابی در گوشی جدید.",
        "Full: all data, item photos and settings. Use it to restore on a new phone.",
        "كاملة: كل البيانات وصور المنتجات والإعدادات. للاستعادة على هاتف جديد."
    )
    val hintJson = tr(
        "فقط اطلاعات، بدون عکس. قابل بازیابی در همین برنامه.",
        "Data only, no photos. Can be restored in this app.",
        "البيانات فقط بدون صور. يمكن استعادتها في هذا التطبيق."
    )
    val hintExcel = tr(
        "برای باز کردن در اکسل. بازیابی از این فایل ممکن نیست.",
        "To open in Excel. Cannot be restored from this file.",
        "لفتحها في Excel. لا يمكن الاستعادة من هذا الملف."
    )
    val restoreTitle = tr("بازیابی", "Restore", "الاستعادة")
    val restoreHint = tr(
        "فایل پشتیبان (.hesabyar یا .json) را انتخاب کنید. اطلاعات فایل به اطلاعات فعلی اضافه می‌شود و رکوردهای با شناسه یکسان به‌روز می‌شوند.",
        "Choose a backup file (.hesabyar or .json). Its data is added to current data; records with the same ID are updated.",
        "اختر ملف النسخة (.hesabyar أو .json). تُضاف بياناته إلى البيانات الحالية، وتُحدَّث السجلات ذات المعرف نفسه."
    )
    val btnRestore = tr("بازیابی از فایل", "Restore from file", "استعادة من ملف")
    val dialogTitle = tr("بازیابی بکاپ", "Restore backup", "استعادة النسخة")
    val dialogText = tr(
        "اطلاعات این فایل به اطلاعات فعلی اضافه می‌شود. رکوردهای با شناسه یکسان جایگزین می‌شوند.",
        "Data from this file will be added. Records with the same ID will be replaced.",
        "ستُضاف بيانات هذا الملف. ستُستبدل السجلات ذات المعرف نفسه."
    )
    val btnConfirm = tr("بازیابی", "Restore", "استعادة")
    val btnCancel = tr("انصراف", "Cancel", "إلغاء")

    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(titleText, style = MaterialTheme.typography.titleMedium)
            Text(introText, style = MaterialTheme.typography.bodySmall)

            Text(periodTitle, style = MaterialTheme.typography.labelLarge)
            BackupPeriod.entries.chunked(3).forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    row.forEach { p ->
                        FilterChip(
                            selected = period == p,
                            onClick = { period = p },
                            label = { Text(periodLabel(p)) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
            Text(msgPeriodNote, style = MaterialTheme.typography.bodySmall)

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

            Text(createTitle, style = MaterialTheme.typography.labelLarge)

            Button(
                onClick = {
                    if (!customRangeOk()) {
                        status = msgBadDates
                    } else {
                        fullSaver.launch(backupFileName(period, "hesabyar"))
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(btnFull)
            }
            Text(hintFull, style = MaterialTheme.typography.bodySmall)

            OutlinedButton(
                onClick = {
                    if (!customRangeOk()) {
                        status = msgBadDates
                    } else {
                        jsonSaver.launch(backupFileName(period, "json"))
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(btnJson)
            }
            Text(hintJson, style = MaterialTheme.typography.bodySmall)

            OutlinedButton(
                onClick = {
                    if (!customRangeOk()) {
                        status = msgBadDates
                    } else {
                        xlsxSaver.launch(backupFileName(period, "xlsx"))
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(btnExcel)
            }
            Text(hintExcel, style = MaterialTheme.typography.bodySmall)

            Spacer(modifier = Modifier.height(4.dp))

            Text(restoreTitle, style = MaterialTheme.typography.labelLarge)
            Text(restoreHint, style = MaterialTheme.typography.bodySmall)
            Button(
                onClick = { restorePicker.launch(arrayOf("*/*")) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(btnRestore)
            }

            if (busy) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            status?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
    }

    pendingRestore?.let { uri ->
        AlertDialog(
            onDismissRequest = { pendingRestore = null },
            title = { Text(dialogTitle) },
            text = { Text(dialogText) },
            confirmButton = {
                TextButton(onClick = {
                    val u = uri
                    pendingRestore = null
                    doRestore(u)
                }) {
                    Text(btnConfirm)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingRestore = null }) {
                    Text(btnCancel)
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
                        "اطلاعات مالی شما فقط روی همین گوشی ذخیره می‌شوند و به هیچ سرور یا ابری فرستاده نمی‌شوند.\n\nاگر برنامه حذف شود، گوشی عوض شود یا گوشی خراب شود، این اطلاعات از بین می‌رود. پیشنهاد می‌کنیم از بخش تنظیمات، «پشتیبان کامل» بگیرید و فایل را در جای امنی نگه دارید.",
                        "Your financial data is stored only on this phone and is never sent to a server or cloud.\n\nIf the app is uninstalled, the phone is changed or damaged, this data will be lost. Please create a Full backup from Settings and keep the file somewhere safe.",
                        "بياناتك المالية محفوظة على هذا الهاتف فقط ولا تُرسل إلى أي خادم أو سحابة.\n\nإذا حُذف التطبيق أو تغيّر الهاتف أو تعطّل، ستُفقد هذه البيانات. يُنصح بأخذ «نسخة كاملة» من الإعدادات وحفظ الملف في مكان آمن."
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
