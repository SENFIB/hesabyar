package com.hesabyar.app.ui
import com.hesabyar.app.MainActivity
import androidx.compose.ui.Alignment
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Environment
import android.os.IBinder
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
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

const val DL_DB_NAME = "hesabyar_downloads.db"
const val DL_PREFS = "hesabyar_downloads"
private const val DL_CH = "dl_channel"
private const val DL_NOTIF_ID = 4101
private const val DL_MAX_PARALLEL = 3
private const val DL_UA =
    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

data class DlItem(
    val id: Long,
    val url: String,
    val fileName: String,
    val path: String,
    val total: Long,
    val done: Long,
    val status: String,   // queued, running, paused, done, failed
    val error: String
)

/** پوشه ذخیره: داخل فضای برنامه، قابل دیدن با مدیر فایل */
fun dlFolder(ctx: Context): File {
    val dir = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: File(ctx.filesDir, "downloads")
    if (!dir.exists()) dir.mkdirs()
    return dir
}

object DlSettings {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences(DL_PREFS, Context.MODE_PRIVATE)

    fun wifiOnly(ctx: Context): Boolean = prefs(ctx).getBoolean("wifi_only", false)
    fun autoStart(ctx: Context): Boolean = prefs(ctx).getBoolean("auto_start", true)
    fun schedOn(ctx: Context): Boolean = prefs(ctx).getBoolean("sched_on", false)
    fun startHour(ctx: Context): Int = prefs(ctx).getInt("start_h", 1)
    fun endHour(ctx: Context): Int = prefs(ctx).getInt("end_h", 7)
    fun speedKb(ctx: Context): Int = prefs(ctx).getInt("speed_kb", 0)

    fun set(ctx: Context, key: String, value: Any) {
        val e = prefs(ctx).edit()
        when (value) {
            is Boolean -> e.putBoolean(key, value)
            is Int -> e.putInt(key, value)
        }
        e.apply()
    }
}

object DlPolicy {
    /** null یعنی مجاز است؛ در غیر این صورت کد دلیل توقف */
    fun blockReason(ctx: Context): String? {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return "offline"
        val caps = cm.getNetworkCapabilities(net) ?: return "offline"
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return "offline"
        if (DlSettings.wifiOnly(ctx) && !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return "wifi"
        if (DlSettings.schedOn(ctx) && !inWindow(ctx)) return "schedule"
        return null
    }

    fun inWindow(ctx: Context): Boolean {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val s = DlSettings.startHour(ctx)
        val e = DlSettings.endHour(ctx)
        return when {
            s == e -> true
            s < e -> h >= s && h < e
            else -> h >= s || h < e
        }
    }
}

private class DlDbHelper(ctx: Context) : SQLiteOpenHelper(ctx, DL_DB_NAME, null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS downloads (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, url TEXT NOT NULL, " +
                "file_name TEXT NOT NULL, path TEXT NOT NULL, " +
                "total INTEGER NOT NULL, done INTEGER NOT NULL, " +
                "status TEXT NOT NULL, error TEXT NOT NULL)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
}

object DlStore {
    val flow = MutableStateFlow<List<DlItem>>(emptyList())
    /** کار فعال هر دانلود (برای توقف) */
    val jobs = ConcurrentHashMap<Long, Job>()
    /** زبان برنامه؛ برای متن اعلان سرویس */
    var lang: String = Locale.getDefault().language

    private lateinit var app: Context
    private var helper: DlDbHelper? = null

    @Synchronized
    fun init(ctx: Context) {
        if (::app.isInitialized) return
        app = ctx.applicationContext
        helper = DlDbHelper(app)
        load()
    }

    private fun db(): SQLiteDatabase = helper!!.writableDatabase

    private fun load() {
        val out = ArrayList<DlItem>()
        db().query("downloads", null, null, null, null, null, "id ASC").use { c ->
            while (c.moveToNext()) {
                out.add(
                    DlItem(
                        c.getLong(0), c.getString(1), c.getString(2), c.getString(3),
                        c.getLong(4), c.getLong(5), c.getString(6), c.getString(7)
                    )
                )
            }
        }
        flow.value = out
    }

    private fun fileNameFrom(url: String): String {
        val raw = try {
            URLDecoder.decode(url.substringBefore('?').substringAfterLast('/'), "UTF-8")
        } catch (e: Exception) {
            ""
        }
        val clean = raw.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
        return if (clean.isNotEmpty()) clean else "download_" + System.currentTimeMillis()
    }

    private fun uniqueName(name: String): String {
        val dir = dlFolder(app)
        val taken = flow.value.map { it.fileName }.toSet()
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var candidate = name
        var n = 1
        while (taken.contains(candidate) || File(dir, candidate).exists()) {
            candidate = base + " (" + n + ")" + ext
            n++
        }
        return candidate
    }

    @Synchronized
    fun add(url: String): Long {
        val name = uniqueName(fileNameFrom(url))
        val cv = ContentValues().apply {
            put("url", url)
            put("file_name", name)
            put("path", File(dlFolder(app), name).absolutePath)
            put("total", 0L)
            put("done", 0L)
            put("status", "queued")
            put("error", "")
        }
        val id = db().insert("downloads", null, cv)
        load()
        return id
    }

    @Synchronized
    fun setStatus(id: Long, status: String, error: String = "") {
        val cv = ContentValues().apply {
            put("status", status)
            put("error", error)
        }
        db().update("downloads", cv, "id = ?", arrayOf(id.toString()))
        load()
    }

    @Synchronized
    fun setProgress(id: Long, done: Long, total: Long) {
        val cv = ContentValues().apply {
            put("done", done)
            put("total", total)
        }
        db().update("downloads", cv, "id = ?", arrayOf(id.toString()))
        load()
    }

    @Synchronized
    fun finish(id: Long, status: String, error: String) {
        setStatus(id, status, error)
    }

    /** دانلودهایی که با بسته شدن سرویس، «در حال دانلود» مانده‌اند به صف برمی‌گردند */
    @Synchronized
    fun resetStale() {
        val cv = ContentValues().apply { put("status", "queued") }
        db().update("downloads", cv, "status = ?", arrayOf("running"))
        load()
    }

    fun pause(id: Long) {
        setStatus(id, "paused")
        jobs[id]?.cancel()
    }

    fun pauseAll() {
        for (item in flow.value) {
            if (item.status == "running" || item.status == "queued") pause(item.id)
        }
    }

    fun resume(ctx: Context, id: Long) {
        setStatus(id, "queued")
        startDownloadService(ctx)
    }

    @Synchronized
    fun delete(id: Long) {
        jobs[id]?.cancel()
        val item = flow.value.firstOrNull { it.id == id }
        if (item != null && item.status != "done") File(item.path).delete()
        db().delete("downloads", "id = ?", arrayOf(id.toString()))
        load()
    }
}

/** شروع سرویس دانلود (در پیش‌زمینه) */
fun startDownloadService(ctx: Context) {
    try {
        ContextCompat.startForegroundService(ctx, Intent(ctx, DownloadService::class.java))
    } catch (e: Exception) {
        // اگر سیستم اجازه نداد، آیتم در صف می‌ماند و بار بعد اجرا می‌شود
    }
}

class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loop: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        DlStore.init(this)
        if (DlStore.jobs.isEmpty()) DlStore.resetStale()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        DlStore.init(this)
        showForeground()
        if (loop == null || loop?.isActive == false) {
            loop = scope.launch { dispatch() }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun dispatch() {
        while (true) {
            val reason = DlPolicy.blockReason(this)
            if (reason != null) {
                // شرایط نامناسب است: دانلودها به صف برمی‌گردند و سرویس می‌ایستد
                for (id in DlStore.jobs.keys.toList()) {
                    DlStore.setStatus(id, "queued")
                    DlStore.jobs[id]?.cancel()
                }
                stopSelf()
                return
            }
            val list = DlStore.flow.value
            if (DlStore.jobs.isEmpty() && list.none { it.status == "queued" }) {
                stopSelf()
                return
            }
            for (item in list) {
                if (item.status == "queued" &&
                    DlStore.jobs.size < DL_MAX_PARALLEL &&
                    !DlStore.jobs.containsKey(item.id)
                ) {
                    startJob(item)
                }
            }
            updateNotification()
            delay(1500)
        }
    }

    private fun startJob(item: DlItem) {
        DlStore.setStatus(item.id, "running")
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                fetch(item)
                DlStore.finish(item.id, "done", "")
            } catch (e: CancellationException) {
                throw e // توقف کاربر یا سیستم؛ وضعیت قبلاً در DB ثبت شده
            } catch (e: Exception) {
                DlStore.finish(item.id, "failed", e.message ?: "error")
            } finally {
                DlStore.jobs.remove(item.id)
            }
        }
        DlStore.jobs[item.id] = job
        job.start()
    }

    private suspend fun fetch(item: DlItem) {
        val file = File(item.path)
        var have = if (file.exists()) file.length() else 0L
        val conn = URL(item.url).openConnection() as HttpURLConnection
        // با لغو کار، اتصال بسته می‌شود تا خواندن متوقف شود
        coroutineContext[Job]?.invokeOnCompletion { conn.disconnect() }
        try {
            conn.connectTimeout = 15000
            conn.readTimeout = 20000
            conn.setRequestProperty("User-Agent", DL_UA)
            if (have > 0) conn.setRequestProperty("Range", "bytes=" + have + "-")

            val code = conn.responseCode
            if (code == 416 && have > 0) {
                DlStore.setProgress(item.id, have, have)
                return
            }
            if (code !in 200..299) throw IOException("HTTP " + code)

            val partial = code == 206 && have > 0
            if (!partial) have = 0L
            val length = conn.contentLengthLong
            val total = if (length > 0) have + length else -1L
            DlStore.setProgress(item.id, have, total)

            var sent = 0L
            var done = have
            var lastUi = System.currentTimeMillis()
            val startedAt = System.currentTimeMillis()

            conn.inputStream.use { input ->
                FileOutputStream(file, partial).use { out ->
                    val buf = ByteArray(32 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        sent += n

                        val limitKb = DlSettings.speedKb(this)
                        if (limitKb > 0) {
                            val expected = sent * 1000L / (limitKb * 1024L)
                            val actual = System.currentTimeMillis() - startedAt
                            if (expected > actual) delay(expected - actual)
                        }

                        val now = System.currentTimeMillis()
                        if (now - lastUi > 800) {
                            DlStore.setProgress(item.id, done, total)
                            lastUi = now
                        }
                    }
                }
            }
            if (total > 0 && done < total) throw IOException("connection lost")
            DlStore.setProgress(item.id, done, if (total > 0) total else done)
        } finally {
            conn.disconnect()
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(DL_CH, "دانلود", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun buildNotification(): Notification {
        val lang = DlStore.lang
        val list = DlStore.flow.value
        val running = list.count { it.status == "running" }
        val queued = list.count { it.status == "queued" }
        val text = remPick(
            lang,
            "" + running + " در حال دانلود · " + queued + " در صف",
            "" + running + " downloading · " + queued + " queued",
            "" + running + " جارٍ التنزيل · " + queued + " في الانتظار"
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, DL_CH)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(remPick(lang, "مدیریت دانلود", "Download manager", "مدير التنزيل"))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .build()
    }

    private fun showForeground() {
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(DL_NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(DL_NOTIF_ID, n)
        }
    }

    private fun updateNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(DL_NOTIF_ID, buildNotification())
    }
}

private fun sizeText(done: Long, total: Long): String {
    fun f(b: Long): String =
        if (b >= 1048576L) String.format(Locale.US, "%.1f MB", b / 1048576.0)
        else String.format(Locale.US, "%.0f KB", b / 1024.0)
    return if (total > 0) f(done) + " / " + f(total) else f(done)
}

@Composable
private fun DlRow(item: DlItem, lang: String, ctx: Context) {
    val statusText = when (item.status) {
        "queued" -> remPick(lang, "در صف", "Queued", "في الانتظار")
        "running" -> remPick(lang, "در حال دانلود", "Downloading", "جارٍ التنزيل")
        "paused" -> remPick(lang, "متوقف شده", "Paused", "متوقف")
        "done" -> remPick(lang, "کامل شد", "Completed", "اكتمل")
        else -> remPick(lang, "خطا: " + item.error, "Error: " + item.error, "خطأ: " + item.error)
    }
    val frac = if (item.total > 0) (item.done.toFloat() / item.total.toFloat()).coerceIn(0f, 1f) else 0f
    val pauseTxt = remPick(lang, "توقف", "Pause", "إيقاف مؤقت")
    val resumeTxt = remPick(lang, "ادامه", "Resume", "متابعة")
    val deleteTxt = remPick(lang, "حذف", "Delete", "حذف")

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(item.fileName, fontWeight = FontWeight.Bold, maxLines = 2)
            Text(statusText + "  ·  " + sizeText(item.done, item.total))
            if (item.status != "done" && item.status != "failed") {
                LinearProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth())
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (item.status == "running") {
                    OutlinedButton(onClick = { DlStore.pause(item.id) }) { Text(pauseTxt) }
                } else if (item.status != "done") {
                    Button(onClick = { DlStore.resume(ctx, item.id) }) { Text(resumeTxt) }
                }
                OutlinedButton(onClick = { DlStore.delete(item.id) }) { Text(deleteTxt) }
            }
        }
    }
}

@Composable
fun DownloadsScreen() {
    val ctx = LocalContext.current
    val lang = currentLang()
    DlStore.lang = lang
    val list by DlStore.flow.collectAsState()

    LaunchedEffect(Unit) {
        DlStore.init(ctx)
        if (DlSettings.autoStart(ctx) && DlStore.flow.value.any { it.status == "queued" }) {
            startDownloadService(ctx)
        }
    }

    fun p(fa: String, en: String, ar: String): String = remPick(lang, fa, en, ar)

    var url by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    var wifiOnly by remember { mutableStateOf(DlSettings.wifiOnly(ctx)) }
    var autoStart by remember { mutableStateOf(DlSettings.autoStart(ctx)) }
    var schedOn by remember { mutableStateOf(DlSettings.schedOn(ctx)) }
    var startH by remember { mutableStateOf(DlSettings.startHour(ctx).toString()) }
    var endH by remember { mutableStateOf(DlSettings.endHour(ctx).toString()) }
    var speed by remember { mutableStateOf(DlSettings.speedKb(ctx).toString()) }

    val reason = DlPolicy.blockReason(ctx)
    val folder = dlFolder(ctx).absolutePath
    val errUrl = p(
        "لینک معتبر وارد کنید (با http یا https شروع شود)",
        "Enter a valid link starting with http or https",
        "أدخل رابطًا صالحًا يبدأ بـ http أو https"
    )
    val addedMsg = p("به صف دانلود اضافه شد ✓", "Added to queue ✓", "تمت الإضافة إلى قائمة التنزيل ✓")
    val waitWifi = p("در انتظار Wi-Fi (گزینه فقط Wi-Fi روشن است)", "Waiting for Wi-Fi (Wi-Fi only is on)", "بانتظار Wi-Fi (خيار Wi-Fi فقط مفعّل)")
    val waitNet = p("در انتظار اتصال اینترنت", "Waiting for internet", "بانتظار الإنترنت")
    val waitSched = p("خارج از بازه زمانی دانلود", "Outside the download window", "خارج نافذة التنزيل")

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            p("مدیریت دانلود", "Download manager", "مدير التنزيل"),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )

        // افزودن لینک
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(p("لینک فایل", "File link", "رابط الملف")) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth()
                )
                if (msg.isNotEmpty()) Text(msg, color = MaterialTheme.colorScheme.primary)
                Button(
                    onClick = {
                        val u = url.trim()
                        if (!(u.startsWith("http://") || u.startsWith("https://"))) {
                            msg = errUrl
                            return@Button
                        }
                        DlStore.add(u)
                        if (DlSettings.autoStart(ctx)) startDownloadService(ctx)
                        url = ""
                        msg = addedMsg
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(p("افزودن به صف", "Add to queue", "إضافة إلى القائمة"))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = { startDownloadService(ctx) },
                        modifier = Modifier.weight(1f)
                    ) { Text(p("شروع همه", "Start all", "ابدأ الكل")) }
                    OutlinedButton(
                        onClick = { DlStore.pauseAll() },
                        modifier = Modifier.weight(1f)
                    ) { Text(p("توقف همه", "Pause all", "إيقاف الكل")) }
                }
                Text(
                    p("پوشه ذخیره: ", "Save folder: ", "مجلد الحفظ: ") + folder,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        // تنظیمات
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(p("تنظیمات دانلود", "Download settings", "إعدادات التنزيل"), fontWeight = FontWeight.Bold)

                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(p("فقط با Wi-Fi", "Wi-Fi only", "فقط عبر Wi-Fi"), modifier = Modifier.weight(1f))
                    Switch(checked = wifiOnly, onCheckedChange = {
                        wifiOnly = it
                        DlSettings.set(ctx, "wifi_only", it)
                    })
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(p("شروع خودکار صف", "Auto start queue", "بدء القائمة تلقائيًا"), modifier = Modifier.weight(1f))
                    Switch(checked = autoStart, onCheckedChange = {
                        autoStart = it
                        DlSettings.set(ctx, "auto_start", it)
                    })
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(p("زمان‌بندی روزانه", "Daily schedule", "جدولة يومية"), modifier = Modifier.weight(1f))
                    Switch(checked = schedOn, onCheckedChange = {
                        schedOn = it
                        DlSettings.set(ctx, "sched_on", it)
                    })
                }
                if (schedOn) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = startH,
                            onValueChange = { v ->
                                startH = v
                                toLatinDigits(v).toIntOrNull()?.let { if (it in 0..23) DlSettings.set(ctx, "start_h", it) }
                            },
                            label = { Text(p("شروع (ساعت ۰ تا ۲۳)", "Start hour (0–23)", "ساعة البدء (0–23)")) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = endH,
                            onValueChange = { v ->
                                endH = v
                                toLatinDigits(v).toIntOrNull()?.let { if (it in 0..23) DlSettings.set(ctx, "end_h", it) }
                            },
                            label = { Text(p("پایان (ساعت ۰ تا ۲۳)", "End hour (0–23)", "ساعة الانتهاء (0–23)")) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                OutlinedTextField(
                    value = speed,
                    onValueChange = { v ->
                        speed = v
                        toLatinDigits(v).toIntOrNull()?.let { if (it >= 0) DlSettings.set(ctx, "speed_kb", it) }
                    },
                    label = { Text(p("سرعت محدود (کیلوبایت بر ثانیه، ۰ = بدون محدودیت)", "Speed limit (KB/s, 0 = unlimited)", "حد السرعة (ك.ب/ث، 0 = بدون حد)")) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        // وضعیت صف
        if (reason != null && list.any { it.status == "queued" }) {
            val why = when (reason) {
                "wifi" -> waitWifi
                "schedule" -> waitSched
                else -> waitNet
            }
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
            ) {
                Text(why, modifier = Modifier.padding(12.dp))
            }
        }

        // فهرست دانلودها
        Text(p("صف دانلود", "Download queue", "قائمة التنزيل"), fontWeight = FontWeight.Bold)
        if (list.isEmpty()) {
            Text(
                p("هنوز دانلودی اضافه نشده است.", "No downloads yet.", "لم تتم إضافة أي تنزيل بعد."),
                style = MaterialTheme.typography.bodySmall
            )
        }
        list.reversed().forEach { item ->
            DlRow(item = item, lang = lang, ctx = ctx)
        }
        Spacer(Modifier.height(24.dp))
    }
}
