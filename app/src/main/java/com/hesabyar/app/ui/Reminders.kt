package com.hesabyar.app.ui

import android.Manifest
import android.app.Notification
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
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
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
private const val TEST_NOTIF_BASE = 900_000
private const val ALERT_LONG_MS = 30_000L

// کلیدهای داده‌ای که بین اعلان و صفحه‌ی هشدار رد و بدل می‌شوند
const val ALERT_EXTRA_TITLE = "alert_title"
const val ALERT_EXTRA_TEXT = "alert_text"
const val ALERT_EXTRA_LEVEL = "alert_level"
const val ALERT_EXTRA_NOTIF = "alert_notif"
const val ALERT_EXTRA_KIND = "alert_kind"
const val ALERT_EXTRA_REF = "alert_ref"

// کانال‌های نسخه‌ی جدید (تنظیمات کانال بعد از ساخت قفل می‌شود، پس نام جدید)
private const val CH_L1 = "alert_l1_v2"
private const val CH_L2 = "alert_l2_v2"
private const val CH_L3 = "alert_l3_v2"
private val OLD_CHANNELS = listOf("rem_fullscreen", "rem_onscreen", "rem_center")

// شناسه‌ی اعلان هر یادآور و هر چک؛ جلوگیری از تداخل
fun reminderNotifId(id: Long): Int = (100_000L + id).toInt()
fun chequeNotifId(id: Long): Int = (200_000L + id).toInt()
fun chequeAlertName(id: Long): String = "hesabyar_chq_" + id

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

/** نمایش اعلان در سه سطح؛ صدا و رفتار هر سطح اینجا تعیین می‌شود */
object RemNotify {

    private fun soundOf(type: Int): Uri? =
        RingtoneManager.getDefaultUri(type) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

    private fun soundTypeFor(level: Int): Int = when (level) {
        1 -> RingtoneManager.TYPE_ALARM
        2 -> RingtoneManager.TYPE_RINGTONE
        else -> RingtoneManager.TYPE_NOTIFICATION
    }

    private fun attrs(usage: Int): AudioAttributes =
        AudioAttributes.Builder()
            .setUsage(usage)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

    fun ensureChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        OLD_CHANNELS.forEach { nm.deleteNotificationChannel(it) }

        // سطح ۱: صفحه کامل و زنگ بلند (صدای آلارم که در نوتیف تکرار می‌شود)
        nm.createNotificationChannel(
            NotificationChannel(CH_L1, "یادآور سطح ۱ (صفحه کامل)", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "صفحه کامل با زنگ بلند تا بسته شدن"
                setSound(soundOf(RingtoneManager.TYPE_ALARM), attrs(AudioAttributes.USAGE_ALARM))
                enableVibration(true)
                vibrationPattern = longArrayOf(0L, 800L, 500L, 800L, 500L, 800L)
            }
        )

        // سطح ۲: بنر بالای صفحه و صدای متوسط (یک بار)
        nm.createNotificationChannel(
            NotificationChannel(CH_L2, "یادآور سطح ۲ (بنر بالا)", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "بنر بالای صفحه با صدای متوسط"
                setSound(
                    soundOf(RingtoneManager.TYPE_RINGTONE),
                    attrs(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                )
                enableVibration(true)
                vibrationPattern = longArrayOf(0L, 300L, 200L, 300L)
            }
        )

        // سطح ۳: فقط مرکز اعلان و صدای کوتاه
        nm.createNotificationChannel(
            NotificationChannel(CH_L3, "یادآور سطح ۳ (مرکز اعلان)", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "فقط در مرکز اعلان با صدای کوتاه"
                setSound(soundOf(RingtoneManager.TYPE_NOTIFICATION), attrs(AudioAttributes.USAGE_NOTIFICATION))
                enableVibration(false)
            }
        )
    }

    fun canNotify(ctx: Context): Boolean {
        val enabled = NotificationManagerCompat.from(ctx).areNotificationsEnabled()
        if (Build.VERSION.SDK_INT < 33) return enabled
        return enabled &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    /** Intent صفحه‌ی هشدار سطح ۱ (با داده‌های لازم) */
    fun alertIntent(
        ctx: Context,
        title: String,
        text: String,
        level: Int,
        notifId: Int,
        kind: String,
        ref: Long
    ): Intent =
        Intent(ctx, ReminderAlertActivity::class.java)
            .putExtra(ALERT_EXTRA_TITLE, title)
            .putExtra(ALERT_EXTRA_TEXT, text)
            .putExtra(ALERT_EXTRA_LEVEL, level)
            .putExtra(ALERT_EXTRA_NOTIF, notifId)
            .putExtra(ALERT_EXTRA_KIND, kind)
            .putExtra(ALERT_EXTRA_REF, ref)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

    @Suppress("MissingPermission")
    fun show(
        ctx: Context,
        notifId: Int,
        title: String,
        text: String,
        level: Int,
        kind: String = "",
        ref: Long = 0L
    ) {
        if (!canNotify(ctx)) return
        ensureChannels(ctx)
        val lv = if (level in 1..3) level else 2
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        val openApp = PendingIntent.getActivity(
            ctx,
            notifId,
            Intent(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            flags
        )
        val page = PendingIntent.getActivity(
            ctx,
            notifId + 7_777_777,
            alertIntent(ctx, title, text, lv, notifId, kind, ref),
            flags
        )

        val channel = when (lv) {
            1 -> CH_L1
            2 -> CH_L2
            else -> CH_L3
        }

        val b = NotificationCompat.Builder(ctx, channel)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setCategory(if (lv == 1) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_REMINDER)
            .setPriority(if (lv == 3) NotificationCompat.PRIORITY_DEFAULT else NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(if (lv == 1) page else openApp)

        if (lv == 1) {
            // صفحه کامل وقتی گوشی قفل است یا خاموش است باز می‌شود
            b.setFullScreenIntent(page, true)
            // بعد از مدت مشخص، اعلان (و زنگ تکرارشونده‌اش) خودکار بسته می‌شود
            b.setTimeoutAfter(ALERT_LONG_MS)
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            soundOf(soundTypeFor(lv))?.let { b.setSound(it) }
        }

        val n = b.build()
        // سطح ۱: صدا تکرار می‌شود تا وقتی اعلان بسته نشده
        if (lv == 1) n.flags = n.flags or Notification.FLAG_INSISTENT
        NotificationManagerCompat.from(ctx).notify(notifId, n)
    }
}

/** صفحه‌ی تمام‌صفحه‌ی هشدار سطح ۱ (روی قفل هم نمایش داده می‌شود) */
class ReminderAlertActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

        val title = intent.getStringExtra(ALERT_EXTRA_TITLE) ?: ""
        val text = intent.getStringExtra(ALERT_EXTRA_TEXT) ?: ""
        val level = intent.getIntExtra(ALERT_EXTRA_LEVEL, 1)
        val notifId = intent.getIntExtra(ALERT_EXTRA_NOTIF, 0)
        val kind = intent.getStringExtra(ALERT_EXTRA_KIND) ?: ""
        val ref = intent.getLongExtra(ALERT_EXTRA_REF, 0L)

        setContent {
            MaterialTheme {
                AlertPage(
                    title = title,
                    text = text,
                    level = level,
                    canMarkDone = kind.isNotEmpty() && ref > 0L,
                    onDone = {
                        if (kind == "rem" && ref > 0L) {
                            RemStore.setDone(this@ReminderAlertActivity, ref, true)
                            RemScheduler.cancel(this@ReminderAlertActivity, ref)
                        }
                        if (kind == "chq" && ref > 0L) {
                            ChequeStore.setDone(this@ReminderAlertActivity, ref, true)
                            AlertWork.cancel(this@ReminderAlertActivity, chequeAlertName(ref))
                        }
                        NotificationManagerCompat.from(this@ReminderAlertActivity).cancel(notifId)
                        finish()
                    },
                    onDismiss = {
                        NotificationManagerCompat.from(this@ReminderAlertActivity).cancel(notifId)
                        finish()
                    },
                    onOpenApp = {
                        NotificationManagerCompat.from(this@ReminderAlertActivity).cancel(notifId)
                        startActivity(
                            Intent(this@ReminderAlertActivity, MainActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        )
                        finish()
                    }
                )
            }
        }
    }
}

@Composable
private fun AlertPage(
    title: String,
    text: String,
    level: Int,
    canMarkDone: Boolean,
    onDone: () -> Unit,
    onDismiss: () -> Unit,
    onOpenApp: () -> Unit
) {
    val tHead = tr("هشدار مهم", "Important alert", "تنبيه مهم")
    val tDone = tr("انجام شد", "Done", "تم")
    val tDismiss = tr("بستن و قطع صدا", "Dismiss and stop sound", "إغلاق وإيقاف الصوت")
    val tOpen = tr("باز کردن برنامه", "Open the app", "فتح التطبيق")
    val container = when (level) {
        1 -> MaterialTheme.colorScheme.errorContainer
        2 -> MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.secondaryContainer
    }
    Surface(modifier = Modifier.fillMaxSize(), color = container) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(28.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(tHead, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(16.dp))
            Text(
                title,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(12.dp))
            Text(text, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
            Spacer(Modifier.height(32.dp))
            if (canMarkDone) {
                Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text(tDone) }
                Spacer(Modifier.height(8.dp))
            }
            OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text(tDismiss) }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onOpenApp, modifier = Modifier.fillMaxWidth()) { Text(tOpen) }
        }
    }
}

/** زمان‌بندی یک‌باره با WorkManager؛ برای یادآورها و چک و سفته استفاده می‌شود */
object AlertWork {
    fun schedule(
        ctx: Context,
        name: String,
        fireAt: Long,
        title: String,
        text: String,
        level: Int,
        notifId: Int,
        kind: String,
        ref: Long
    ) {
        val wm = WorkManager.getInstance(ctx)
        wm.cancelUniqueWork(name)
        val waitMs = maxOf(0L, fireAt - System.currentTimeMillis())
        val req = OneTimeWorkRequestBuilder<ReminderWorker>()
            .setInitialDelay(waitMs, TimeUnit.MILLISECONDS)
            .setInputData(
                workDataOf(
                    "title" to title,
                    "text" to text,
                    "level" to level,
                    "notif_id" to notifId,
                    "kind" to kind,
                    "ref" to ref
                )
            )
            .build()
        wm.enqueueUniqueWork(name, ExistingWorkPolicy.REPLACE, req)
    }

    fun cancel(ctx: Context, name: String) {
        WorkManager.getInstance(ctx).cancelUniqueWork(name)
    }
}

/** کارگر یک‌باره: در زمان موعد اعلان را نشان می‌دهد */
class ReminderWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        val appCtx = applicationContext
        val preTitle = inputData.getString("title")
        if (preTitle != null) {
            RemNotify.show(
                appCtx,
                inputData.getInt("notif_id", 0),
                preTitle,
                inputData.getString("text") ?: "",
                inputData.getInt("level", 2),
                inputData.getString("kind") ?: "",
                inputData.getLong("ref", 0L)
            )
            return Result.success()
        }
        // پشتیبانی از زمان‌بندی‌های قدیمی که فقط شناسه را داشتند
        val id = inputData.getLong("id", 0L)
        val r = RemStore.get(appCtx, id) ?: return Result.success()
        if (r.done) return Result.success()
        val due = remDueText(r)
        RemNotify.show(
            appCtx,
            reminderNotifId(r.id),
            remPick(r.lang, "یادآور: ", "Reminder: ", "تذكير: ") + r.title,
            remPick(r.lang, "موعد: ", "Due: ", "الموعد: ") + due,
            r.level,
            "rem",
            r.id
        )
        return Result.success()
    }
}

/** زمان‌بندی یادآورهای یادداشت */
object RemScheduler {
    private fun name(id: Long): String = "hesabyar_rem_" + id

    fun schedule(ctx: Context, r: RemItem) {
        if (r.done) {
            AlertWork.cancel(ctx, name(r.id))
            return
        }
        val fireAt = r.dueMillis - r.leadDays * REM_DAY_MS
        AlertWork.schedule(
            ctx,
            name(r.id),
            fireAt,
            remPick(r.lang, "یادآور: ", "Reminder: ", "تذكير: ") + r.title,
            remPick(r.lang, "موعد: ", "Due: ", "الموعد: ") + remDueText(r),
            r.level,
            reminderNotifId(r.id),
            "rem",
            r.id
        )
    }

    fun cancel(ctx: Context, id: Long) {
        AlertWork.cancel(ctx, name(id))
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

/** کارت تست سه سطح اعلان؛ برای یادداشت‌ها و چک و سفته یکسان است */
@Composable
fun AlertTestCard() {
    val ctx = LocalContext.current
    val lang = currentLang()
    val header = remPick(lang, "تست اعلان‌ها", "Test notifications", "اختبار الإشعارات")
    val hint = remPick(
        lang,
        "سطح ۱: صفحه کامل و زنگ بلند تا بسته شدن. سطح ۲: بنر بالا و صدای متوسط. سطح ۳: فقط مرکز اعلان و صدای کوتاه.",
        "Level 1: full screen with a long alarm. Level 2: top banner with a medium sound. Level 3: notification centre only with a short sound.",
        "المستوى 1: ملء الشاشة مع منبه طويل. المستوى 2: شريط علوي وصوت متوسط. المستوى 3: مركز الإشعارات فقط بصوت قصير."
    )
    val testTitle = remPick(lang, "آزمایش یادآور", "Reminder test", "اختبار التذكير")
    val testText = remPick(lang, "این یک اعلان آزمایشی است.", "This is a test notification.", "هذا إشعار تجريبي.")
    val testSent = remPick(lang, "اعلان آزمایشی ارسال شد", "Test notification sent", "تم إرسال الإشعار التجريبي")
    val blockedMsg = remPick(
        lang,
        "اعلان‌ها برای برنامه خاموش است. از تنظیمات گوشی روشن کنید.",
        "Notifications are off. Turn them on in phone settings.",
        "الإشعارات متوقفة. فعّلها من إعدادات الهاتف."
    )

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(header, fontWeight = FontWeight.Bold)
            Text(hint, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                listOf(1, 2, 3).forEach { lv ->
                    OutlinedButton(
                        onClick = {
                            if (!RemNotify.canNotify(ctx)) {
                                Toast.makeText(ctx, blockedMsg, Toast.LENGTH_LONG).show()
                            } else {
                                val nid = TEST_NOTIF_BASE + lv
                                RemNotify.show(ctx, nid, testTitle, testText, lv)
                                // سطح ۱ علاوه بر اعلان، صفحه‌ی کامل را هم نشان می‌دهد
                                if (lv == 1) {
                                    ctx.startActivity(RemNotify.alertIntent(ctx, testTitle, testText, lv, nid, "", 0L))
                                }
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
}

/** تبدیل نیازهای قدیمی (فقط برای سازگاری) و توابع اصلی صفحه یادداشت‌ها */
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

    val levelOptions = listOf(1, 2, 3).map { it to remLevelLabel(it, lang) }
    val leadOptions = listOf(0, 1, 3, 7, 14, 30).map { it to remLeadLabel(it, lang) }
    val nowMs = System.currentTimeMillis()
    val active = list.filter { !it.done }
    val finished = list.filter { it.done }
    val banners = active.filter { it.level == 2 && it.dueMillis - it.leadDays * REM_DAY_MS <= nowMs }

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
                            "اعلان‌ها برای این برنامه خاموش است. بدون اجازه، یادآورها دیده نمی‌شوند.",
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

        // تست سه سطح اعلان
        AlertTestCard()

        // یادآورهای روی صفحه (سطح ۲) وقتی برنامه باز است
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
                    RemStore.setDone(ctx, r.id, true)
                    RemScheduler.schedule(ctx, r.copy(done = true))
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
