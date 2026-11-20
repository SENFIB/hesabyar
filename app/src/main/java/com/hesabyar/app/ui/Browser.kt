package com.hesabyar.app.ui

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.os.Message
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream

// ---------- مدل و ذخیره‌سازی نشان‌ها و تاریخچه ----------

data class BrowserLink(val title: String, val url: String, val time: Long)

object BrowserStore {
    private const val PREFS = "hesabyar_browser"
    private const val MAX_HISTORY = 300

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun bookmarks(ctx: Context): List<BrowserLink> = readList(prefs(ctx).getString("bookmarks", null))

    fun saveBookmarks(ctx: Context, list: List<BrowserLink>) {
        prefs(ctx).edit().putString("bookmarks", writeList(list)).apply()
    }

    fun history(ctx: Context): List<BrowserLink> = readList(prefs(ctx).getString("history", null))

    fun addHistory(ctx: Context, title: String, url: String) {
        if (url.isBlank() || url == "about:blank") return
        val list = history(ctx).toMutableList()
        if (list.firstOrNull()?.url == url) return
        list.add(0, BrowserLink(title.ifBlank { url }, url, System.currentTimeMillis()))
        while (list.size > MAX_HISTORY) list.removeAt(list.lastIndex)
        prefs(ctx).edit().putString("history", writeList(list)).apply()
    }

    fun clearHistory(ctx: Context) {
        prefs(ctx).edit().remove("history").apply()
    }

    fun adBlock(ctx: Context): Boolean = prefs(ctx).getBoolean("adblock", true)

    fun setAdBlock(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean("adblock", on).apply()
    }

    private fun readList(raw: String?): List<BrowserLink> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                BrowserLink(o.optString("title"), o.optString("url"), o.optLong("time"))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun writeList(list: List<BrowserLink>): String {
        val arr = JSONArray()
        list.forEach { b ->
            arr.put(JSONObject().put("title", b.title).put("url", b.url).put("time", b.time))
        }
        return arr.toString()
    }
}

// ---------- فهرست ساده تبلیغات (دامنه‌های رایج) ----------

private val AD_HOSTS = listOf(
    "doubleclick.net",
    "googlesyndication.com",
    "googleadservices.com",
    "adservice.google.com",
    "google-analytics.com",
    "outbrain.com",
    "taboola.com",
    "criteo.com",
    "adnxs.com",
    "scorecardresearch.com",
    "popads.net",
    "propellerads.com"
)

private fun isAdHost(host: String): Boolean =
    AD_HOSTS.any { host == it || host.endsWith("." + it) }

// ---------- وضعیت تب‌ها ----------

private const val DESKTOP_UA =
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

private class BrowserTab(val id: Long, startUrl: String, val isPrivate: Boolean = false) {
    // رشته خالی یعنی صفحه شروع
    var url by mutableStateOf(startUrl)
    var title by mutableStateOf("")
    var progress by mutableIntStateOf(100)
    var canBack by mutableStateOf(false)
    var canForward by mutableStateOf(false)
    var desktop by mutableStateOf(false)
    var mobileUa = ""
    var webView: WebView? = null
}

// تب‌ها تا زمانی که برنامه باز است نگه داشته می‌شوند، حتی وقتی به تب دیگری می‌روید
private object BrowserSession {
    private var nextId = 2L
    val tabs = mutableStateListOf(BrowserTab(1L, ""))
    var activeId by mutableLongStateOf(1L)

    // تب‌های بسته‌شده (فقط تب‌های عادی) برای بازگشایی دوباره
    val closed = mutableStateListOf<BrowserLink>()

    fun active(): BrowserTab = tabs.firstOrNull { it.id == activeId } ?: tabs[0]

    fun openTab(url: String, priv: Boolean = false) {
        val t = BrowserTab(nextId++, url, priv)
        tabs.add(t)
        activeId = t.id
    }

    fun select(id: Long) {
        activeId = id
    }

    fun reopenLast() {
        if (closed.isEmpty()) return
        val last = closed.removeAt(0)
        openTab(last.url)
    }

    fun close(id: Long) {
        val idx = tabs.indexOfFirst { it.id == id }
        if (idx < 0) return
        val tab = tabs[idx]
        tab.webView?.stopLoading()
        if (!tab.isPrivate && tab.url.isNotEmpty()) {
            closed.add(0, BrowserLink(tab.title.ifBlank { tab.url }, tab.url, System.currentTimeMillis()))
            while (closed.size > 10) closed.removeAt(closed.lastIndex)
        }
        if (tab.isPrivate) {
            val otherPrivate = tabs.any { it.isPrivate && it.id != id }
            wipeTab(tab, lastPrivate = !otherPrivate)
        }
        tabs.removeAt(idx)
        if (tabs.isEmpty()) {
            val fresh = BrowserTab(nextId++, "")
            tabs.add(fresh)
            activeId = fresh.id
        } else if (activeId == id) {
            activeId = tabs[maxOf(0, idx - 1)].id
        }
    }
}

// پاک کردن داده‌های یک تب خصوصی هنگام بستن
private fun wipeTab(tab: BrowserTab, lastPrivate: Boolean) {
    tab.webView?.let { w ->
        w.clearHistory()
        w.clearCache(true)
    }
    // کوکی‌ها و فضای ذخیره سایت‌ها فقط وقتی پاک می‌شوند که تب خصوصی دیگری باز نباشد
    if (lastPrivate) clearWebData()
}

// کوکی‌ها و داده‌های سایت‌ها (برای همه تب‌ها؛ WebView کوکی جدا برای هر تب ندارد)
private fun clearWebData() {
    val cm = CookieManager.getInstance()
    cm.removeAllCookies(null)
    cm.flush()
    WebStorage.getInstance().deleteAllData()
}

private class WebTexts(
    val googleNote: String,
    val downloadStarted: String,
    val downloadFailed: String,
    val noBrowser: String
)

// ---------- کمک‌های بیرونی ----------

// ورودی کاربر را به نشانی تبدیل می‌کند؛ اگر متن جست‌وجو باشد، در گوگل جست‌وجو می‌شود
private fun normalizeInput(raw: String): String {
    val s = raw.trim()
    if (s.isEmpty()) return ""
    val lower = s.lowercase()
    if (lower.startsWith("http://") || lower.startsWith("https://")) return s
    if (!s.contains(' ') && s.contains('.') && !s.startsWith(".") && !s.endsWith(".")) {
        return "https://$s"
    }
    return "https://www.google.com/search?q=" + Uri.encode(s)
}

private fun isWebLink(url: String): Boolean {
    val u = url.lowercase()
    return u.startsWith("http://") || u.startsWith("https://") ||
        u.startsWith("about:") || u.startsWith("data:") || u.startsWith("blob:")
}

// باز کردن نشانی در برنامه‌های بیرونی (مرورگر گوشی، تلگرام و ...)
private fun openExternal(ctx: Context, url: String, preferChrome: Boolean): Boolean {
    return try {
        val intent = if (url.startsWith("intent:")) {
            Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
        } else {
            Intent(Intent.ACTION_VIEW, Uri.parse(url))
        }
        intent.addCategory(Intent.CATEGORY_BROWSABLE)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (preferChrome) {
            try {
                ctx.startActivity(Intent(intent).setPackage("com.android.chrome"))
                return true
            } catch (e: Exception) {
                // Chrome نصب نیست؛ با مرورگر پیش‌فرض باز می‌شود
            }
        }
        ctx.startActivity(intent)
        true
    } catch (e: Exception) {
        false
    }
}

// ساخت یک WebView با تنظیمات مرورگر کامل
@SuppressLint("SetJavaScriptEnabled")
private fun createWebView(ctx: Context, tab: BrowserTab, texts: WebTexts): WebView {
    val appCtx = ctx.applicationContext
    val web = WebView(ctx)
    val s = web.settings
    s.javaScriptEnabled = true
    s.domStorageEnabled = true
    s.loadWithOverviewMode = true
    s.useWideViewPort = true
    s.builtInZoomControls = true
    s.displayZoomControls = false
    s.setSupportZoom(true)
    s.setSupportMultipleWindows(true)
    s.javaScriptCanOpenWindowsAutomatically = true
    s.mediaPlaybackRequiresUserGesture = false
    s.allowFileAccess = false
    tab.mobileUa = s.userAgentString ?: ""

    val cookies = CookieManager.getInstance()
    cookies.setAcceptCookie(true)
    cookies.setAcceptThirdPartyCookies(web, true)

    web.webViewClient = object : WebViewClient() {
        // مسدودکردن درخواست‌های تبلیغاتی (اگر در تنظیمات روشن باشد)
        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
            if (!BrowserStore.adBlock(appCtx)) return null
            val host = request?.url?.host?.lowercase() ?: return null
            if (!isAdHost(host)) return null
            return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
        }

        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            val uri = request?.url ?: return false
            val url = uri.toString()
            val host = uri.host ?: ""
            // گوگل ورود از داخل مرورگرهای درون‌برنامه‌ای را نمی‌پذیرد؛ پس در Chrome باز می‌شود
            if (host == "accounts.google.com") {
                openExternal(appCtx, url, preferChrome = true)
                Toast.makeText(appCtx, texts.googleNote, Toast.LENGTH_LONG).show()
                return true
            }
            if (isWebLink(url)) return false
            if (!openExternal(appCtx, url, preferChrome = false)) {
                Toast.makeText(appCtx, texts.noBrowser, Toast.LENGTH_SHORT).show()
            }
            return true
        }

        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            url?.let { tab.url = it }
            tab.progress = 0
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            tab.progress = 100
            tab.canBack = view?.canGoBack() ?: false
            tab.canForward = view?.canGoForward() ?: false
            val finalUrl = url ?: return
            if (finalUrl == "about:blank") return
            tab.url = finalUrl
            // در تب خصوصی تاریخچه ذخیره نمی‌شود
            if (!tab.isPrivate) {
                BrowserStore.addHistory(appCtx, view?.title ?: tab.title, finalUrl)
            }
        }

        override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
            tab.canBack = view?.canGoBack() ?: false
            tab.canForward = view?.canGoForward() ?: false
        }
    }

    web.webChromeClient = object : WebChromeClient() {
        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            tab.progress = newProgress
        }

        override fun onReceivedTitle(view: WebView?, title: String?) {
            tab.title = title ?: ""
        }

        // لینک‌هایی که در صفحه جدید باز می‌شوند، در تب جدید باز می‌شوند
        override fun onCreateWindow(
            view: WebView?,
            isDialog: Boolean,
            isUserGesture: Boolean,
            resultMsg: Message?
        ): Boolean {
            val msg = resultMsg ?: return false
            val transport = msg.obj as? WebView.WebViewTransport ?: return false
            val popup = WebView(view?.context ?: appCtx)
            popup.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(v: WebView?, request: WebResourceRequest?): Boolean {
                    val u = request?.url?.toString() ?: return true
                    BrowserSession.openTab(u, tab.isPrivate)
                    val target = v
                    target?.post { target.destroy() }
                    return true
                }
            }
            transport.webView = popup
            msg.sendToTarget()
            return true
        }
    }

    web.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
        try {
            val name = URLUtil.guessFileName(url, contentDisposition, mimeType)
            val request = DownloadManager.Request(Uri.parse(url))
            request.setMimeType(mimeType)
            request.addRequestHeader("User-Agent", userAgent)
            CookieManager.getInstance().getCookie(url)?.let { request.addRequestHeader("Cookie", it) }
            request.setTitle(name)
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            val dm = appCtx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(request)
            Toast.makeText(appCtx, texts.downloadStarted, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(appCtx, texts.downloadFailed, Toast.LENGTH_SHORT).show()
        }
    }
    return web
}

// نمایش WebView یک تب؛ اگر قبلاً ساخته شده باشد همان را دوباره نشان می‌دهد
@Composable
private fun WebPage(tab: BrowserTab, texts: WebTexts) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { c ->
            tab.webView ?: createWebView(c, tab, texts).also { w ->
                tab.webView = w
                if (tab.url.isNotEmpty()) w.loadUrl(tab.url)
            }
        }
    )
}

// صفحه شروع: میانبرها و نشان‌ها
@Composable
private fun StartPage(
    bookmarks: List<BrowserLink>,
    onOpen: (String) -> Unit,
    onRemove: (String) -> Unit
) {
    val tStart = tr("صفحه شروع", "Start page", "الصفحة الرئيسية")
    val tShortcuts = tr("میانبرها", "Shortcuts", "اختصارات")
    val tBookmarks = tr("نشان‌ها", "Bookmarks", "الإشارات المرجعية")
    val tNoBookmarks = tr("هنوز نشانی ذخیره نشده", "No bookmarks yet", "لا توجد إشارات بعد")
    val tOpenLink = tr("باز کردن", "Open", "فتح")
    val tRemove = tr("حذف", "Remove", "حذف")
    val tWiki = tr("ویکی‌پدیا", "Wikipedia", "ويكيبيديا")
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(tStart, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
        Text(tShortcuts, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { onOpen("https://www.google.com") }, modifier = Modifier.weight(1f)) {
                Text("Google", maxLines = 1)
            }
            OutlinedButton(onClick = { onOpen("https://www.youtube.com") }, modifier = Modifier.weight(1f)) {
                Text("YouTube", maxLines = 1)
            }
            OutlinedButton(onClick = { onOpen("https://fa.wikipedia.org") }, modifier = Modifier.weight(1f)) {
                Text(tWiki, maxLines = 1)
            }
        }
        Text(tBookmarks, fontWeight = FontWeight.Bold)
        if (bookmarks.isEmpty()) {
            Text(tNoBookmarks)
        }
        bookmarks.forEach { b ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Text(
                        b.title.ifBlank { b.url },
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        b.url,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row {
                        TextButton(onClick = { onOpen(b.url) }) { Text(tOpenLink) }
                        TextButton(onClick = { onRemove(b.url) }) {
                            Text(tRemove, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
}

// یک ردیف در منوی پایین صفحه
@Composable
private fun SheetItem(text: String, onClick: () -> Unit) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp)
    )
}

// ---------- صفحه اصلی مرورگر ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen() {
    val ctx = LocalContext.current
    val tabs = BrowserSession.tabs
    val active = BrowserSession.active()

    // در حالت راست‌به‌چپ آیکون‌ها برعکس می‌شوند تا «برگشت» به سمت صفحه قبل اشاره کند
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val backIcon = if (rtl) Icons.Filled.ArrowForward else Icons.Filled.ArrowBack
    val forwardIcon = if (rtl) Icons.Filled.ArrowBack else Icons.Filled.ArrowForward

    val tAddress = tr("نشانی یا جست‌وجو", "Address or search", "العنوان أو البحث")
    val tNewTab = tr("تب جدید", "New tab", "تبويب جديد")
    val tPrivateTab = tr("تب خصوصی", "Private tab", "تبويب خاص")
    val tNewPrivateTab = tr("تب خصوصی جدید", "New private tab", "تبويب خاص جديد")
    val tPrivateNote = tr(
        "تب خصوصی: تاریخچه ذخیره نمی‌شود، نشان ذخیره نمی‌شود و با بستن تب، کوکی‌ها و داده‌های سایت‌ها پاک می‌شوند (ممکن است از سایت‌ها خارج شوید).",
        "Private tab: history and bookmarks are not saved. Closing it clears cookies and site data (you may be signed out of sites).",
        "تبويب خاص: لا يُحفظ السجل ولا الإشارات. عند إغلاقه تُمسح الكوكيز وبيانات المواقع (وقد تخرج من المواقع)."
    )
    val tNoBookmarkPrivate = tr("در تب خصوصی نشان ذخیره نمی‌شود", "Bookmarks are not saved in private tabs", "لا يتم حفظ الإشارات في التبويبات الخاصة")
    val tMenu = tr("منو", "Menu", "القائمة")
    val tBack = tr("برگشت", "Back", "رجوع")
    val tForward = tr("جلوتر", "Forward", "تقدم")
    val tReload = tr("بارگذاری مجدد", "Reload", "إعادة التحميل")
    val tHome = tr("صفحه شروع", "Home", "الرئيسية")
    val tFind = tr("یافتن در صفحه", "Find in page", "بحث في الصفحة")
    val tFindHint = tr("متن را بنویسید", "Type text", "اكتب النص")
    val tPrev = tr("قبلی", "Previous", "السابق")
    val tNext = tr("بعدی", "Next", "التالي")
    val tClose = tr("بستن", "Close", "إغلاق")
    val tBookmarkAdd = tr("نشان کردن این صفحه", "Bookmark this page", "إضافة إشارة مرجعية")
    val tBookmarkRemove = tr("حذف نشان این صفحه", "Remove bookmark", "إزالة الإشارة المرجعية")
    val tHistory = tr("تاریخچه", "History", "السجل")
    val tClearHistory = tr("پاک کردن تاریخچه", "Clear history", "مسح السجل")
    val tNoHistory = tr("تاریخچه‌ای نیست", "No history", "لا يوجد سجل")
    val tDesktopOn = tr("نمایش نسخه دسکتاپ", "Desktop site", "موقع الكمبيوتر")
    val tDesktopOff = tr("نمایش نسخه موبایل", "Mobile site", "موقع الجوال")
    val tShare = tr("اشتراک‌گذاری", "Share", "مشاركة")
    val tCopy = tr("کپی نشانی", "Copy link", "نسخ الرابط")
    val tCopied = tr("نشانی کپی شد", "Link copied", "تم نسخ الرابط")
    val tOutside = tr("باز کردن در مرورگر گوشی", "Open in phone browser", "فتح في متصفح الهاتف")
    val tGoogle = tr("ورود به حساب گوگل", "Sign in to Google", "تسجيل الدخول إلى Google")
    val tNewTabMenu = tr("تب جدید", "New tab", "تبويب جديد")
    val tFindMenu = tr("یافتن در صفحه", "Find in page", "بحث في الصفحة")
    val tHistoryMenu = tr("تاریخچه", "History", "السجل")
    val tReopen = tr("بازگشایی آخرین تب بسته‌شده", "Reopen closed tab", "إعادة فتح التبويب المغلق")
    val tNoClosed = tr("تب بسته‌شده‌ای نیست", "No recently closed tabs", "لا توجد تبويبات مغلقة مؤخراً")
    val tZoomIn = tr("بزرگ‌نمایی", "Zoom in", "تكبير")
    val tZoomOut = tr("کوچک‌نمایی", "Zoom out", "تصغير")
    val tAdBlock = tr("مسدودکننده تبلیغ", "Ad blocker", "حاجب الإعلانات")
    val tOn = tr("روشن", "On", "مفعّل")
    val tOff = tr("خاموش", "Off", "معطّل")
    val tClearData = tr("پاک کردن تاریخچه، کوکی و کش", "Clear history, cookies and cache", "مسح السجل والكوكيز والذاكرة المؤقتة")
    val tCleared = tr("داده‌های مرور پاک شد", "Browsing data cleared", "تم مسح بيانات التصفح")
    val tGoogleNote = tr(
        "ورود گوگل در Chrome باز شد؛ گوگل ورود از داخل برنامه‌های مرورگر را نمی‌پذیرد.",
        "Google sign-in opened in Chrome. Google does not allow sign-in inside in-app browsers.",
        "فُتح تسجيل دخول Google في Chrome، لأن Google لا يسمح بالتسجيل داخل المتصفحات المدمجة."
    )
    val tDownloadStarted = tr("دانلود شروع شد (پوشه Downloads)", "Download started", "بدأ التنزيل")
    val tDownloadFailed = tr("دانلود انجام نشد", "Download failed", "فشل التنزيل")
    val tNoBrowser = tr("برنامه‌ای برای باز کردن این نشانی پیدا نشد", "No app found to open this link", "لا يوجد تطبيق لفتح هذا الرابط")
    val webTexts = WebTexts(tGoogleNote, tDownloadStarted, tDownloadFailed, tNoBrowser)

    var addressText by remember { mutableStateOf("") }
    var showFind by remember { mutableStateOf(false) }
    var findText by remember { mutableStateOf("") }
    var showHistory by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var adBlockOn by remember { mutableStateOf(BrowserStore.adBlock(ctx)) }
    var dataTick by remember { mutableIntStateOf(0) }
    val bookmarks = remember(dataTick) { BrowserStore.bookmarks(ctx) }
    val history = remember(dataTick) { BrowserStore.history(ctx) }
    val adLabel = tAdBlock + ": " + (if (adBlockOn) tOn else tOff)

    LaunchedEffect(active.id, active.url) {
        addressText = active.url
    }

    val isBookmarked = active.url.isNotEmpty() && bookmarks.any { it.url == active.url }

    fun go(raw: String) {
        val u = normalizeInput(raw)
        if (u.isEmpty()) return
        active.url = u
        active.webView?.loadUrl(u)
        addressText = u
    }

    fun goHome() {
        active.webView?.stopLoading()
        active.url = ""
        addressText = ""
    }

    fun goBack() {
        val w = active.webView
        when {
            active.canBack && w != null -> w.goBack()
            active.url.isNotEmpty() -> goHome()
            tabs.size > 1 -> BrowserSession.close(active.id)
        }
    }

    fun toggleBookmark() {
        if (active.isPrivate) {
            Toast.makeText(ctx, tNoBookmarkPrivate, Toast.LENGTH_SHORT).show()
            return
        }
        val list = BrowserStore.bookmarks(ctx).toMutableList()
        val existing = list.indexOfFirst { it.url == active.url }
        if (existing >= 0) {
            list.removeAt(existing)
        } else if (active.url.isNotEmpty()) {
            list.add(0, BrowserLink(active.title.ifBlank { active.url }, active.url, System.currentTimeMillis()))
        }
        BrowserStore.saveBookmarks(ctx, list)
        dataTick++
    }

    fun removeBookmark(url: String) {
        BrowserStore.saveBookmarks(ctx, BrowserStore.bookmarks(ctx).filter { it.url != url })
        dataTick++
    }

    fun toggleDesktop() {
        val next = !active.desktop
        active.desktop = next
        val w = active.webView ?: return
        if (active.mobileUa.isNotBlank()) {
            w.settings.userAgentString = if (next) DESKTOP_UA else active.mobileUa
        }
        if (active.url.isNotEmpty()) w.reload()
    }

    fun shareCurrent() {
        if (active.url.isEmpty()) return
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, active.url)
        }
        ctx.startActivity(Intent.createChooser(i, null))
    }

    fun copyCurrent() {
        if (active.url.isEmpty()) return
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("link", active.url))
        Toast.makeText(ctx, tCopied, Toast.LENGTH_SHORT).show()
    }

    fun openOutside() {
        if (active.url.isEmpty()) return
        if (!openExternal(ctx, active.url, preferChrome = false)) {
            Toast.makeText(ctx, tNoBrowser, Toast.LENGTH_SHORT).show()
        }
    }

    fun openGoogle() {
        if (openExternal(ctx, "https://accounts.google.com/", preferChrome = true)) {
            Toast.makeText(ctx, tGoogleNote, Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(ctx, tNoBrowser, Toast.LENGTH_SHORT).show()
        }
    }

    fun reopenClosed() {
        if (BrowserSession.closed.isEmpty()) {
            Toast.makeText(ctx, tNoClosed, Toast.LENGTH_SHORT).show()
        } else {
            BrowserSession.reopenLast()
        }
    }

    fun clearAllData() {
        tabs.forEach { t -> t.webView?.clearCache(true) }
        clearWebData()
        BrowserStore.clearHistory(ctx)
        dataTick++
        Toast.makeText(ctx, tCleared, Toast.LENGTH_SHORT).show()
    }

    // دکمه برگشت گوشی: اول برگشت در صفحه، بعد صفحه شروع، بعد بستن تب
    BackHandler(enabled = active.canBack || active.url.isNotEmpty() || tabs.size > 1) {
        goBack()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // نوار تب‌ها
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically
        ) {
            tabs.forEach { t ->
                val isActive = t.id == active.id
                val label = when {
                    t.title.isNotBlank() -> t.title
                    t.url.isEmpty() -> if (t.isPrivate) tPrivateTab else tNewTab
                    else -> t.url
                }
                val bg = when {
                    t.isPrivate && isActive -> Color(0xFF455A64)
                    t.isPrivate -> Color(0xFF263238)
                    isActive -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.surfaceVariant
                }
                val textColor = when {
                    t.isPrivate -> Color.White
                    isActive -> MaterialTheme.colorScheme.onPrimary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                Row(
                    modifier = Modifier
                        .padding(end = 6.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(bg)
                        .clickable { BrowserSession.select(t.id) }
                        .padding(start = 10.dp, end = 2.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelMedium,
                        color = textColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 120.dp)
                    )
                    IconButton(
                        onClick = { BrowserSession.close(t.id) },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = tClose,
                            modifier = Modifier.size(14.dp),
                            tint = textColor
                        )
                    }
                }
            }
            IconButton(onClick = { BrowserSession.openTab("") }) {
                Icon(Icons.Filled.Add, contentDescription = tNewTab)
            }
        }

        // نوار نشانی و دکمه منو
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            OutlinedTextField(
                value = addressText,
                onValueChange = { addressText = it },
                label = { Text(tAddress) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { go(addressText) }),
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { showMenu = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = tMenu)
            }
        }

        // دکمه‌های ناوبری (در RTL آیکون‌ها جهت درست را نشان می‌دهند)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = { goBack() },
                enabled = active.canBack || active.url.isNotEmpty()
            ) { Icon(backIcon, contentDescription = tBack) }
            IconButton(
                onClick = { active.webView?.goForward() },
                enabled = active.canForward
            ) { Icon(forwardIcon, contentDescription = tForward) }
            IconButton(onClick = { active.webView?.reload() }) {
                Icon(Icons.Filled.Refresh, contentDescription = tReload)
            }
            IconButton(onClick = { goHome() }) {
                Icon(Icons.Filled.Home, contentDescription = tHome)
            }
            IconButton(onClick = { toggleBookmark() }) {
                Icon(
                    Icons.Filled.Star,
                    contentDescription = tBookmarkAdd,
                    tint = if (isBookmarked) Color(0xFFF59E0B) else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = { showFind = !showFind }) {
                Icon(Icons.Filled.Search, contentDescription = tFind)
            }
        }

        // یادآوری تب خصوصی
        if (active.isPrivate) {
            Text(
                tPrivateNote,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // نوار پیشرفت بارگذاری
        if (active.progress in 1..99) {
            LinearProgressIndicator(
                progress = active.progress / 100f,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // نوار یافتن در صفحه
        if (showFind) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = findText,
                    onValueChange = {
                        findText = it
                        active.webView?.findAllAsync(it)
                    },
                    label = { Text(tFindHint) },
                    singleLine = true,
                    keyboardActions = KeyboardActions(onSearch = { active.webView?.findNext(true) }),
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { active.webView?.findNext(false) }) { Text(tPrev) }
                TextButton(onClick = { active.webView?.findNext(true) }) { Text(tNext) }
                TextButton(onClick = {
                    active.webView?.clearMatches()
                    findText = ""
                    showFind = false
                }) { Text(tClose) }
            }
        }

        // محتوا: صفحه شروع یا صفحه وب
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            if (active.url.isEmpty()) {
                StartPage(
                    bookmarks = bookmarks,
                    onOpen = { go(it) },
                    onRemove = { removeBookmark(it) }
                )
            } else {
                key(active.id) {
                    WebPage(tab = active, texts = webTexts)
                }
            }
        }
    }

    // منوی سه‌نقطه: پایین صفحه، با اسکرول
    if (showMenu) {
        ModalBottomSheet(
            onDismissRequest = { showMenu = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 24.dp)
            ) {
                SheetItem(if (isBookmarked) tBookmarkRemove else tBookmarkAdd) {
                    showMenu = false
                    toggleBookmark()
                }
                SheetItem(tNewTabMenu) {
                    showMenu = false
                    BrowserSession.openTab("")
                }
                SheetItem(tNewPrivateTab) {
                    showMenu = false
                    BrowserSession.openTab("", true)
                }
                SheetItem(tReopen) {
                    showMenu = false
                    reopenClosed()
                }
                SheetItem(tHistoryMenu) {
                    showMenu = false
                    showHistory = true
                }
                SheetItem(tFindMenu) {
                    showMenu = false
                    showFind = true
                }
                SheetItem(tZoomIn) {
                    showMenu = false
                    active.webView?.zoomIn()
                }
                SheetItem(tZoomOut) {
                    showMenu = false
                    active.webView?.zoomOut()
                }
                SheetItem(if (active.desktop) tDesktopOff else tDesktopOn) {
                    showMenu = false
                    toggleDesktop()
                }
                SheetItem(adLabel) {
                    showMenu = false
                    adBlockOn = !adBlockOn
                    BrowserStore.setAdBlock(ctx, adBlockOn)
                }
                SheetItem(tShare) {
                    showMenu = false
                    shareCurrent()
                }
                SheetItem(tCopy) {
                    showMenu = false
                    copyCurrent()
                }
                SheetItem(tOutside) {
                    showMenu = false
                    openOutside()
                }
                SheetItem(tGoogle) {
                    showMenu = false
                    openGoogle()
                }
                SheetItem(tClearData) {
                    showMenu = false
                    clearAllData()
                }
            }
        }
    }

    // دیالوگ تاریخچه
    if (showHistory) {
        AlertDialog(
            onDismissRequest = { showHistory = false },
            title = { Text(tHistory) },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (history.isEmpty()) {
                        Text(tNoHistory)
                    }
                    history.forEach { h ->
                        TextButton(
                            onClick = {
                                showHistory = false
                                go(h.url)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                h.title.ifBlank { h.url },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    BrowserStore.clearHistory(ctx)
                    dataTick++
                }) { Text(tClearHistory) }
            },
            dismissButton = {
                TextButton(onClick = { showHistory = false }) { Text(tClose) }
            }
        )
    }
}
