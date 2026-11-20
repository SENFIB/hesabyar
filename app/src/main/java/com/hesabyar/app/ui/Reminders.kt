package com.hesabyar.app.ui

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Build
import android.widget.Toast
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
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.hesabyar.app.MainActivity
import java.util.concurrent.TimeUnit

const val REM_DB_NAME = "hesabyar_reminders.db"
private const val REM_DAY_MS = 86_400_000L
private const val REM_CH_1 = "rem_fullscreen"
private const val REM_CH_2 = "rem_onscreen"
private const val REM_CH_3 = "rem_center"

/** انتخاب متن بر اساس زبان برنامه */
internal fun remPick(lang: String, fa: String, en: String, ar: String): String =
    when (lang) {
        "fa" -> fa
        "ar" -> ar
        else -> en
    }

data class RemItem(
    val id: Long,
    val title: String,
    val note: String,
    val dueMillis: Long,
    val leadDays: Int,
    val level: Int,
    val done: Boolean,
    val lang: String
)

private class RemDbHelper(ctx: Context) : SQLiteOpenHelper(ctx, REM_DB_NAME, null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS reminders (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "title TEXT NOT NULL, note TEXT NOT NULL, " +
                "due_millis INTEGER NOT NULL, lead_days INTEGER NOT NULL, " +
                "level INTEGER NOT NULL, done INTEGER NOT NULL, lang TEXT NOT NULL)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
}

object RemStore {
    private var helper: RemDbHelper? = null

    @Synchronized
    private fun db(ctx: Context): SQLiteDatabase {
        if (helper == null) helper = RemDbHelper(ctx.applicationContext)
        return helper!!.writableDatabase
    }

    private fun read(c: Cursor): RemItem = RemItem(
        c.getLong(0), c.getString(1), c.getString(2), c.getLong(3),
        c.getInt(4), c.getInt(5), c.getInt(6) == 1, c.getString(7)
    )

    @Synchronized
    fun all(ctx: Context): List<RemItem> {
        val out = ArrayList<RemItem>()
        db(ctx).query("reminders", null, null, null, null, null, "due_millis ASC").use { c ->
            while (c.moveToNext()) out.add(read(c))
        }
        return out
    }

    @Synchronized
    fun get(ctx: Context, id: Long): RemItem? = all(ctx).firstOrNull { it.id == id }

    @Synchronized
    fun add(ctx: Context, r: RemItem): Long {
        val cv = ContentValues().apply {
            put("title", r.title)
            put("note", r.note)
            put("due_millis", r.dueMillis)
            put("lead_days", r.leadDays)
            put("level", r.level)
            put("done", if (r.done) 1 else 0)
            put("lang", r.lang)
        }
        return db(ctx).insert("reminders", null, cv)
    }

    @Synchronized
    fun setDone(ctx: Context, id: Long, done: Boolean) {
        val cv = ContentValues().apply { put("done", if (done) 1 else 0) }
        db(ctx).update("reminders", cv, "id = ?", arrayOf(id.toString()))
    }

    @Synchronized
    fun delete(ctx: Context, id: Long) {
        db(ctx).delete("reminders", "id = ?", arrayOf(id.toString()))
    }
}

/** نمایش اعلان در سه سطح */
object RemNotify {
    fun ensureChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(REM_CH_1, "یادآور تمام‌صفحه", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "اعلان فوری با صدا و نمایش تمام‌صفحه" }
        )
        nm.createNotificationChannel(
            NotificationChannel(REM_CH_2, "یادآور روی صفحه", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "بنر بالای صفحه با صدا" }
        )
        nm.createNotificationChannel(
            NotificationChannel(REM_CH_3, "یادآور آرام", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "فقط در مرکز اعلان با صدای ملایم" }
        )
    }

    fun canNotify(ctx: Context): Boolean {
        val enabled = NotificationManagerCompat.from(ctx).areNotificationsEnabled()
        if (Build.VERSION.SDK_INT < 33) return enabled
        return enabled &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    @Suppress("MissingPermission")
    fun show(ctx: Context, id: Long, title: String, text: String, level: Int) {
        if (!canNotify(ctx)) return
        ensureChannels(ctx)
        val open = PendingIntent.getActivity(
            ctx,
            id.toInt(),
            Intent(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val channel = when (level) {
            1 -> REM_CH_1
            2 -> REM_CH_2
            else -> REM_CH_3
        }
        val b = NotificationCompat.Builder(ctx, channel)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(if (level == 3) NotificationCompat.PRIORITY_DEFAULT else NotificationCompat.PRIORITY_HIGH)
        if (level == 1) b.setFullScreenIntent(open, true)
        NotificationManagerCompat.from(ctx).notify(id.toInt(), b.build())
    }
}

/** کارگر یک‌باره: در زمان موعد اعلان را نشان می‌دهد */
class ReminderWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        val id = inputData.getLong("id", 0L)
        val r = RemStore.get(applicationContext, id) ?: return Result.success()
        if (r.done) return Result.success()
        val t = CalendarType.forLanguage(r.lang)
        val due = formatWith(r.dueMillis, "yyyy/MM/dd HH:mm", t.locale, t.timeZone)
        val title = remPick(r.lang, "یادآور: ", "Reminder: ", "تذكير: ") + r.title
        val text = remPick(r.lang, "موعد: ", "Due: ", "الموعد: ") + due
        RemNotify.show(applicationContext, r.id, title, text, r.level)
        return Result.success()
    }
}

/** زمان‌بندی یک‌باره (بدون تکرار) با WorkManager */
object RemScheduler {
    private fun name(id: Long): String = "hesabyar_rem_" + id

    fun schedule(ctx: Context, r: RemItem) {
        val wm = WorkManager.getInstance(ctx)
        wm.cancelUniqueWork(name(r.id))
        if (r.done) return
        val fireAt = r.dueMillis - r.leadDays * REM_DAY_MS
        val waitMs = maxOf(0L, fireAt - System.currentTimeMillis())
        val req = OneTimeWorkRequestBuilder<ReminderWorker>()
            .setInitialDelay(waitMs, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf("id" to r.id))
            .build()
        wm.enqueueUniqueWork(name(r.id), ExistingWorkPolicy.REPLACE, req)
    }

    fun cancel(ctx: Context, id: Long) {
        WorkManager.getInstance(ctx).cancelUniqueWork(name(id))
    }
}

/** تبدیل متن تاریخ و ساعت (در تقویم زبان کاربر) به میلی‌ثانیه */
private fun remParse(text: String, type: CalendarType): Long? {
    val nums = toLatinDigits(text).split(Regex("[^0-9]+")).filter { it.isNotEmpty() }
    if (nums.size < 3) return null
    val parsed = nums.map { it.toIntOrNull() ?: return null }
    val y = parsed[0]
    val m = parsed[1]
    val d = parsed[2]
    val h = parsed.getOrElse(3) { 9 }
    val mi = parsed.getOrElse(4) { 0 }
    if (m !in 1..12 || d !in 1..31 || h !in 0..23 || mi !in 0..59) return null
    val cal = android.icu.util.Calendar.getInstance(type.timeZone, type.locale)
    cal.clear()
    cal.set(y, m - 1, d, h, mi)
    cal.set(android.icu.util.Calendar.SECOND, 0)
    cal.set(android.icu.util.Calendar.MILLISECOND, 0)
    return cal.timeInMillis
}

private fun remDueText(r: RemItem): String {
    val t = CalendarType.forLanguage(r.lang)
    return formatWith(r.dueMillis, "yyyy/MM/dd HH:mm", t.locale, t.timeZone)
}

private fun remLeadLabel(days: Int, lang: String): String =
    if (days == 0) {
        remPick(lang, "همان روز", "Same day", "في نفس اليوم")
    } else {
        remPick(lang, "" + days + " روز قبل", "" + days + " day(s) before", "قبل " + days + " يوم")
    }

private fun remLevelLabel(level: Int, lang: String): String = when (level) {
    1 -> remPick(lang, "تمام‌صفحه", "Full screen", "ملء الشاشة")
    2 -> remPick(lang, "روی صفحه", "On-screen", "على الشاشة")
    else -> remPick(lang, "مرکز اعلان", "Notification centre", "مركز الإشعارات")
}

@Composable
private fun RemChipGrid(options: List<Pair<Int, String>>, selected: Int, onPick: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        options.chunked(3).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                row.forEach { (value, label) ->
                    FilterChip(
                        selected = selected == value,
                        onClick = { onPick(value) },
                        label = { Text(label, maxLines = 1) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun RemRow(r: RemItem, onToggle: () -> Unit, onDelete: () -> Unit) {
    val due = remDueText(r)
    val info = due + "  ·  " + remLeadLabel(r.leadDays, r.lang) + "  ·  " + remLevelLabel(r.level, r.lang)
    val toggleText = if (r.done) {
        remPick(r.lang, "برگشت به فعال", "Reactivate", "إعادة التفعيل")
    } else {
        remPick(r.lang, "انجام شد", "Done", "تم")
    }
    val deleteText = remPick(r.lang, "حذف", "Delete", "حذف")
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(r.title, fontWeight = FontWeight.Bold)
            if (r.note.isNotBlank()) {
                Text(r.note, style = MaterialTheme.typography.bodySmall)
            }
            Text(info, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onToggle) { Text(toggleText) }
                OutlinedButton(onClick = onDelete) { Text(deleteText) }
            }
        }
    }
}

@Composable
fun RemindersScreen(regionType: CalendarType) {
    val ctx = LocalContext.current
    val lang = currentLang()
    fun p(fa: String, en: String, ar: String): String = remPick(lang, fa, en, ar)

    var list by remember { mutableStateOf(RemStore.all(ctx)) }
    fun reload() {
        list = RemStore.all(ctx)
    }

    var title by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var dateText by remember { mutableStateOf("") }
    var lead by remember { mutableIntStateOf(1) }
    var level by remember { mutableIntStateOf(2) }
    var error by remember { mutableStateOf("") }
    var showFinished by remember { mutableStateOf(false) }
    var seen by remember { mutableStateOf(setOf<Long>()) }
    var canNotify by remember { mutableStateOf(RemNotify.canNotify(ctx)) }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        canNotify = granted && RemNotify.canNotify(ctx)
    }

    // متن‌ها در بدنه کامپوزیبل ساخته می‌شوند (نه داخل onClick)
    val exampleText = formatWith(System.currentTimeMillis(), "yyyy/MM/dd HH:mm", regionType.locale, regionType.timeZone)
    val errTitle = p("عنوان یادداشت را وارد کنید", "Enter a title", "أدخل عنوانًا")
    val errDate = p("تاریخ و ساعت را مثل ", "Write date and time like ", "اكتب التاريخ والوقت مثل ") +
        exampleText + p(" بنویسید.", ".", ".")
    val errPast = p("این زمان گذشته است؛ زمان آینده وارد کنید.", "This time has passed; enter a future time.", "هذا الوقت قد مضى؛ أدخل وقتًا مستقبليًا.")
    val savedMsg = p("یادداشت ثبت شد ✓", "Saved ✓", "تم الحفظ ✓")
    val testTitle = p("آزمایش یادآور", "Reminder test", "اختبار التذكير")
    val testText = p("این یک اعلان آزمایشی است.", "This is a test notification.", "هذا إشعار تجريبي.")
    val testSent = p("اعلان آزمایشی ارسال شد", "Test notification sent", "تم إرسال الإشعار التجريبي")
    val blockedMsg = p("اعلان‌ها برای برنامه خاموش است. از تنظیمات گوشی روشن کنید.", "Notifications are off. Turn them on in phone settings.", "الإشعارات متوقفة. فعّلها من إعدادات الهاتف.")
    val levelOptions = listOf(1, 2, 3).map { it to remLevelLabel(it, lang) }
    val leadOptions = listOf(0, 1, 3, 7, 14, 30).map { it to remLeadLabel(it, lang) }

    val nowMs = System.currentTimeMillis()
    val active = list.filter { !it.done }
    val finished = list.filter { it.done }
    val dueNow = active.filter { it.dueMillis - it.leadDays * REM_DAY_MS <= nowMs }
    val popup = dueNow.firstOrNull { it.level == 1 && it.id !in seen }
    val banners = dueNow.filter { it.level == 2 }

    if (popup != null) {
        val doneTxt = remPick(popup.lang, "انجام شد", "Done", "تم")
        val laterTxt = remPick(popup.lang, "بعداً", "Later", "لاحقاً")
        val dueLine = remPick(popup.lang, "موعد: ", "Due: ", "الموعد: ") + remDueText(popup)
        AlertDialog(
            onDismissRequest = { seen = seen + popup.id },
            title = { Text(popup.title) },
            text = { Text(dueLine) },
            confirmButton = {
                TextButton(onClick = {
                    RemStore.setDone(ctx, popup.id, true)
                    RemScheduler.cancel(ctx, popup.id)
                    seen = seen + popup.id
                    reload()
                }) { Text(doneTxt) }
            },
            dismissButton = {
                TextButton(onClick = { seen = seen + popup.id }) { Text(laterTxt) }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            p("یادداشت‌ها و هشدارها", "Notes & alerts", "الملاحظات والتنبيهات"),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )

        if (!canNotify) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        p(
                            "اعلان‌ها برای این برنامه خاموش است. بدون اجازه، یادآورها در مرکز اعلان دیده نمی‌شوند.",
                            "Notifications are off. Without permission, reminders will not appear.",
                            "الإشعارات متوقفة. بدون الإذن لن تظهر التذكيرات."
                        )
                    )
                    Button(onClick = {
                        if (Build.VERSION.SDK_INT >= 33) {
                            MainActivity.skipAutoLock = true
                            permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            ctx.startActivity(
                                Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                            )
                        }
                    }) {
                        Text(p("اجازه اعلان‌ها", "Allow notifications", "السماح بالإشعارات"))
                    }
                }
            }
        }

        // فرم ثبت یادداشت
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(p("یادداشت جدید", "New note", "ملاحظة جديدة"), fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(p("عنوان", "Title", "العنوان")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text(p("توضیح (اختیاری)", "Details (optional)", "تفاصيل (اختياري)")) },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = dateText,
                    onValueChange = { dateText = it },
                    label = { Text(p("تاریخ و ساعت", "Date and time", "التاريخ والوقت")) },
                    placeholder = { Text(exampleText) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                Text(p("یادآوری چه زمانی؟", "Remind me when?", "متى أتذكر؟"), style = MaterialTheme.typography.bodySmall)
                RemChipGrid(leadOptions, lead) { lead = it }
                Text(p("نوع اعلان", "Notification type", "نوع الإشعار"), style = MaterialTheme.typography.bodySmall)
                RemChipGrid(levelOptions, level) { level = it }

                if (error.isNotEmpty()) {
                    Text(error, color = MaterialTheme.colorScheme.error)
                }

                Button(
                    onClick = {
                        if (title.isBlank()) {
                            error = errTitle
                            return@Button
                        }
                        val due = remParse(dateText, regionType)
                        if (due == null) {
                            error = errDate
                            return@Button
                        }
                        if (due < System.currentTimeMillis()) {
                            error = errPast
                            return@Button
                        }
                        val item = RemItem(0L, title.trim(), note.trim(), due, lead, level, false, lang)
                        val newId = RemStore.add(ctx, item)
                        RemScheduler.schedule(ctx, item.copy(id = newId))
                        title = ""
                        note = ""
                        dateText = ""
                        error = ""
                        reload()
                        Toast.makeText(ctx, savedMsg, Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(p("ثبت یادداشت", "Save note", "حفظ الملاحظة"))
                }
            }
        }

        // دکمه‌های تست سه سطح اعلان
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(p("تست اعلان‌ها", "Test notifications", "اختبار الإشعارات"), fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    listOf(1, 2, 3).forEach { lv ->
                        OutlinedButton(
                            onClick = {
                                if (!RemNotify.canNotify(ctx)) {
                                    Toast.makeText(ctx, blockedMsg, Toast.LENGTH_LONG).show()
                                } else {
                                    RemNotify.show(ctx, 900000L + lv, testTitle, testText, lv)
                                    Toast.makeText(ctx, testSent, Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(remLevelLabel(lv, lang), maxLines = 1)
                        }
                    }
                }
            }
        }

        // یادآورهای روی صفحه (سطح ۲)
        if (banners.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(p("هشدار روی صفحه", "On-screen alerts", "تنبيهات على الشاشة"), fontWeight = FontWeight.Bold)
                    banners.forEach { b ->
                        Text("• " + b.title)
                    }
                }
            }
        }

        // فهرست فعال
        Text(p("یادداشت‌های فعال", "Active notes", "الملاحظات النشطة"), fontWeight = FontWeight.Bold)
        if (active.isEmpty()) {
            Text(p("یادداشتی وجود ندارد.", "No notes yet.", "لا توجد ملاحظات."), style = MaterialTheme.typography.bodySmall)
        }
        active.forEach { r ->
            RemRow(
                r = r,
                onToggle = {
                    val updated = r.copy(done = true)
                    RemStore.setDone(ctx, r.id, true)
                    RemScheduler.schedule(ctx, updated)
                    reload()
                },
                onDelete = {
                    RemScheduler.cancel(ctx, r.id)
                    RemStore.delete(ctx, r.id)
                    reload()
                }
            )
        }

        // فهرست انجام‌شده
        if (finished.isNotEmpty()) {
            OutlinedButton(onClick = { showFinished = !showFinished }) {
                Text(
                    if (showFinished) {
                        p("پنهان کردن انجام‌شده‌ها", "Hide finished", "إخفاء المنتهية")
                    } else {
                        p("نمایش انجام‌شده‌ها (" + finished.size + ")", "Show finished (" + finished.size + ")", "عرض المنتهية (" + finished.size + ")")
                    }
                )
            }
            if (showFinished) {
                finished.forEach { r ->
                    RemRow(
                        r = r,
                        onToggle = {
                            RemStore.setDone(ctx, r.id, false)
                            RemScheduler.schedule(ctx, r.copy(done = false))
                            reload()
                        },
                        onDelete = {
                            RemScheduler.cancel(ctx, r.id)
                            RemStore.delete(ctx, r.id)
                            reload()
                        }
                    )
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
