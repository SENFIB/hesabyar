package com.hesabyar.app.ui

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONArray
import org.json.JSONObject

private const val BROWSER_PREFS = "hesabyar_browser"
private const val BM_KEY = "bookmarks"
private const val HIST_KEY = "history"
private const val HIST_MAX = 300
private const val SEARCH_URL = "https://www.google.com/search?q="
private const val DESKTOP_UA =
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

data class BrowserLink(val title: String, val url: String, val time: Long = 0L)

// نشانک‌ها و تاریخچه در تنظیمات خود برنامه ذخیره می‌شوند
object BrowserStore {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences(BROWSER_PREFS, Context.MODE_PRIVATE)

    private fun read(ctx: Context, key: String): List<BrowserLink> {
        val raw = prefs(ctx).getString(key, null) ?: return emptyList()
        val arr = try {
            JSONArray(raw)
        } catch (e: Exception) {
            return emptyList()
        }
        val out = ArrayList<BrowserLink>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(BrowserLink(o.optString("title"), o.optString("url"), o.optLong("time")))
        }
        return out
    }

    private fun write(ctx: Context, key: String, list: List<BrowserLink>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("title", it.title).put("url", it.url).put("time", it.time))
        }
        prefs(ctx).edit().putString(key, arr.toString()).apply()
    }

    fun bookmarks(ctx: Context): List<BrowserLink> = read(ctx, BM_KEY)

    fun saveBookmarks(ctx: Context, list: List<BrowserLink>) = write(ctx, BM_KEY, list)

    fun history(ctx: Context): List<BrowserLink> = read(ctx, HIST_KEY)

    fun addHistory(ctx: Context, title: String, url: String) {
        val list = read(ctx, HIST_KEY).toMutableList()
        if (list.firstOrNull()?.url == url) return
        list.add(0, BrowserLink(title, url, System.currentTimeMillis()))
        write(ctx, HIST_KEY, list.take(HIST_MAX))
    }

    fun clearHistory(ctx: Context) {
        prefs(ctx).edit().remove(HIST_KEY).apply()
    }
}

// اگر متن نشانی بود همان را باز می‌کند، وگرنه جست‌وجو می‌کند
private fun normalizeInput(raw: String): String {
    val t = raw.trim()
    if (t.isEmpty()) return ""
    val low = t.lowercase()
    if (low.startsWith("http://") || low.startsWith("https://")) return t
    if (!t.contains(' ') && t.contains('.')) return "https://$t"
    return SEARCH_URL + Uri.encode(t)
}

// لینک‌های غیر وب (مثل تلگرام یا فروشگاه) را در برنامه مربوطه باز می‌کند
private fun openExternal(ctx: Context, url: String) {
    try {
        val intent = if (url.startsWith("intent:")) {
            Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
        } else {
            Intent(Intent.ACTION_VIEW, Uri.parse(url))
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
    } catch (e: Exception) {
        // برنامه‌ای برای این نشانی روی گوشی نیست
    }
}

private fun startDownload(ctx: Context, url: String, ua: String?, disposition: String?, mime: String?) {
    try {
        val name = URLUtil.guessFileName(url, disposition, mime)
        val req = DownloadManager.Request(Uri.parse(url))
        req.setTitle(name)
        req.setMimeType(mime)
        req.addRequestHeader("User-Agent", ua ?: "")
        req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
        } else {
            req.setDestinationInExternalFilesDir(ctx, Environment.DIRECTORY_DOWNLOADS, name)
        }
        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        dm.enqueue(req)
    } catch (e: Exception) {
        // دانلود شروع نشد
    }
}

private fun shareLink(ctx: Context, url: String) {
    val intent = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_TEXT, url)
    ctx.startActivity(Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

private fun copyLink(ctx: Context, url: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("url", url))
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserScreen() {
    val ctx = LocalContext.current

    // همه متن‌ها اینجا ساخته می‌شوند
    val tTitle = tr("مرورگر", "Browser", "المتصفح")
    val tAddressHint = tr("نشانی یا عبارت جست‌وجو", "Address or search", "العنوان أو بحث")
    val tStartHint = tr(
        "نشانی سایت را بالا بنویسید یا یکی از نشانک‌ها را باز کنید.",
        "Type an address above or open a bookmark.",
        "اكتب عنواناً بالأعلى أو افتح إشارة مرجعية."
    )
    val tNoBookmarks = tr("هنوز نشانکی ندارید.", "No bookmarks yet.", "لا توجد إشارات مرجعية بعد.")
    val tBookmarks = tr("نشانک‌ها", "Bookmarks", "الإشارات المرجعية")
    val tHistory = tr("تاریخچه", "History", "السجل")
    val tClearHistory = tr("پاک کردن تاریخچه", "Clear history", "مسح السجل")
    val tNoHistory = tr("تاریخچه‌ای نیست.", "No history.", "لا يوجد سجل.")
    val tFind = tr("جست‌وجو در صفحه", "Find in page", "بحث في الصفحة")
    val tFindPlaceholder = tr("متن را بنویسید", "Type text", "اكتب النص")
    val tDesktop = tr("نمای دسکتاپ", "Desktop view", "عرض سطح المكتب")
    val tShare = tr("اشتراک‌گذاری", "Share", "مشاركة")
    val tCopy = tr("کپی نشانی", "Copy link", "نسخ الرابط")
    val tOpenExternal = tr("باز کردن در مرورگر دیگر", "Open in another browser", "فتح في متصفح آخر")
    val tHome = tr("صفحه شروع", "Start page", "الصفحة الرئيسية")
    val tClose = tr("بستن", "Close", "إغلاق")
    val tNext = tr("بعدی", "Next", "التالي")
    val tPrev = tr("قبلی", "Previous", "السابق")
    val tRemove = tr("حذف", "Delete", "حذف")

    var webView by remember { mutableStateOf<WebView?>(null) }
    var addressText by remember { mutableStateOf("") }
    var currentUrl by remember { mutableStateOf("") }
    var pageTitle by remember { mutableStateOf("") }
    var loadPercent by remember { mutableIntStateOf(100) }
    var canBack by remember { mutableStateOf(false) }
    var canForward by remember { mutableStateOf(false) }
    var desktop by rememberSaveable { mutableStateOf(false) }
    var bookmarks by remember { mutableStateOf(BrowserStore.bookmarks(ctx)) }
    var history by remember { mutableStateOf<List<BrowserLink>>(emptyList()) }
    var showMenu by remember { mutableStateOf(false) }
    var showBookmarks by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var showFind by remember { mutableStateOf(false) }
    var findText by remember { mutableStateOf("") }

    val isBookmarked = currentUrl.isNotEmpty() && bookmarks.any { it.url == currentUrl }

    fun goTo(raw: String) {
        val url = normalizeInput(raw)
        if (url.isEmpty()) return
        currentUrl = url
        addressText = url
        webView?.loadUrl(url)
    }

    fun goHome() {
        currentUrl = ""
        addressText = ""
        pageTitle = ""
        canBack = false
        canForward = false
        webView?.clearHistory()
        webView?.loadUrl("about:blank")
    }

    fun toggleBookmark() {
        if (currentUrl.isEmpty()) return
        val list = bookmarks.toMutableList()
        val idx = list.indexOfFirst { it.url == currentUrl }
        if (idx >= 0) {
            list.removeAt(idx)
        } else {
            list.add(0, BrowserLink(pageTitle.ifBlank { currentUrl }, currentUrl))
        }
        BrowserStore.saveBookmarks(ctx, list)
        bookmarks = list
    }

    // نمای دسکتاپ را با تغییر مرورگر شناسایی‌شده فعال می‌کند
    LaunchedEffect(desktop, webView) {
        val wv = webView ?: return@LaunchedEffect
        wv.settings.userAgentString = if (desktop) DESKTOP_UA else WebSettings.getDefaultUserAgent(ctx)
        if (currentUrl.isNotEmpty()) wv.reload()
    }

    DisposableEffect(Unit) {
        onDispose {
            webView?.destroy()
            webView = null
        }
    }

    BackHandler(enabled = canBack) {
        webView?.goBack()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // نوار نشانی
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = addressText,
                onValueChange = { addressText = it },
                singleLine = true,
                placeholder = { Text(tAddressHint) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { goTo(addressText) }),
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { webView?.reload() }) {
                Icon(imageVector = Icons.Filled.Refresh, contentDescription = null)
            }
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(imageVector = Icons.Filled.MoreVert, contentDescription = null)
                }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    DropdownMenuItem(
                        text = { Text(tBookmarks) },
                        onClick = {
                            showMenu = false
                            showBookmarks = true
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(tHistory) },
                        onClick = {
                            showMenu = false
                            history = BrowserStore.history(ctx)
                            showHistory = true
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(tFind) },
                        onClick = {
                            showMenu = false
                            showFind = true
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(if (desktop) "✓ $tDesktop" else tDesktop) },
                        onClick = {
                            showMenu = false
                            desktop = !desktop
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(tShare) },
                        onClick = {
                            showMenu = false
                            if (currentUrl.isNotEmpty()) shareLink(ctx, currentUrl)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(tCopy) },
                        onClick = {
                            showMenu = false
                            if (currentUrl.isNotEmpty()) copyLink(ctx, currentUrl)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(tOpenExternal) },
                        onClick = {
                            showMenu = false
                            if (currentUrl.isNotEmpty()) openExternal(ctx, currentUrl)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(tHome) },
                        onClick = {
                            showMenu = false
                            goHome()
                        }
                    )
                }
            }
        }

        // نوار پیشرفت بارگذاری
        if (loadPercent < 100) {
            LinearProgressIndicator(
                progress = { loadPercent / 100f },
                modifier = Modifier.fillMaxWidth()
            )
        }

        // نوار جست‌وجو در صفحه
        if (showFind) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = findText,
                    onValueChange = {
                        findText = it
                        if (it.isEmpty()) webView?.clearMatches() else webView?.findAllAsync(it)
                    },
                    singleLine = true,
                    placeholder = { Text(tFindPlaceholder) },
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { webView?.findNext(false) }) { Text(tPrev) }
                TextButton(onClick = { webView?.findNext(true) }) { Text(tNext) }
                IconButton(onClick = {
                    showFind = false
                    findText = ""
                    webView?.clearMatches()
                }) {
                    Icon(imageVector = Icons.Filled.Close, contentDescription = null)
                }
            }
        }

        // محتوای صفحه
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { c ->
                    WebView(c).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.builtInZoomControls = true
                        settings.displayZoomControls = false
                        settings.setSupportZoom(true)
                        settings.loadWithOverviewMode = true
                        settings.useWideViewPort = true
                        settings.mediaPlaybackRequiresUserGesture = true
                        settings.javaScriptCanOpenWindowsAutomatically = false
                        CookieManager.getInstance().setAcceptCookie(true)

                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): Boolean {
                                val uri = request?.url ?: return false
                                val scheme = uri.scheme?.lowercase() ?: return false
                                if (scheme == "http" || scheme == "https" || scheme == "about" ||
                                    scheme == "data" || scheme == "blob" || scheme == "file"
                                ) {
                                    return false
                                }
                                openExternal(c, uri.toString())
                                return true
                            }

                            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                                canBack = view?.canGoBack() == true
                                canForward = view?.canGoForward() == true
                                if (url != null && url.startsWith("http")) {
                                    currentUrl = url
                                    addressText = url
                                }
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                canBack = view?.canGoBack() == true
                                canForward = view?.canGoForward() == true
                                if (url != null && url.startsWith("http")) {
                                    val title = view?.title?.takeIf { it.isNotBlank() } ?: url
                                    BrowserStore.addHistory(c, title, url)
                                }
                            }
                        }

                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                loadPercent = newProgress
                            }

                            override fun onReceivedTitle(view: WebView?, title: String?) {
                                pageTitle = title ?: ""
                            }
                        }

                        setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
                            startDownload(c, url, userAgent, contentDisposition, mimetype)
                        }

                        webView = this
                    }
                },
                update = { }
            )

            // صفحه شروع روی مرورگر قرار می‌گیرد وقتی نشانی خالی است
            if (currentUrl.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(tTitle, style = MaterialTheme.typography.titleLarge)
                    Text(tStartHint, style = MaterialTheme.typography.bodyMedium)
                    Text(tBookmarks, style = MaterialTheme.typography.titleMedium)
                    if (bookmarks.isEmpty()) {
                        Text(tNoBookmarks, style = MaterialTheme.typography.bodySmall)
                    }
                    bookmarks.forEach { b ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { goTo(b.url) }
                                    .padding(12.dp)
                            ) {
                                Text(b.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(b.url, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }

        // نوار پایین: ابزارهای اصلی مرورگر
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { webView?.goBack() }, enabled = canBack) {
                Icon(imageVector = Icons.Filled.ArrowBack, contentDescription = null)
            }
            IconButton(onClick = { webView?.goForward() }, enabled = canForward) {
                Icon(imageVector = Icons.Filled.ArrowForward, contentDescription = null)
            }
            IconButton(onClick = { goHome() }) {
                Icon(imageVector = Icons.Filled.Home, contentDescription = null)
            }
            IconButton(onClick = { toggleBookmark() }, enabled = currentUrl.isNotEmpty()) {
                Icon(
                    imageVector = Icons.Filled.Star,
                    contentDescription = null,
                    tint = if (isBookmarked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = {
                if (showFind) {
                    showFind = false
                    findText = ""
                    webView?.clearMatches()
                } else {
                    showFind = true
                }
            }) {
                Icon(imageVector = Icons.Filled.Search, contentDescription = null)
            }
        }
    }

    if (showBookmarks) {
        AlertDialog(
            onDismissRequest = { showBookmarks = false },
            title = { Text(tBookmarks) },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 400.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (bookmarks.isEmpty()) Text(tNoBookmarks)
                    bookmarks.forEach { b ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable {
                                        showBookmarks = false
                                        goTo(b.url)
                                    }
                            ) {
                                Text(b.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(b.url, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            TextButton(onClick = {
                                val list = bookmarks.filter { it.url != b.url }
                                BrowserStore.saveBookmarks(ctx, list)
                                bookmarks = list
                            }) {
                                Text(tRemove)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showBookmarks = false }) { Text(tClose) }
            }
        )
    }

    if (showHistory) {
        AlertDialog(
            onDismissRequest = { showHistory = false },
            title = { Text(tHistory) },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 400.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (history.isEmpty()) Text(tNoHistory)
                    history.forEach { h ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showHistory = false
                                    goTo(h.url)
                                }
                                .padding(vertical = 4.dp)
                        ) {
                            Text(h.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(h.url, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showHistory = false }) { Text(tClose) }
            },
            dismissButton = {
                TextButton(onClick = {
                    BrowserStore.clearHistory(ctx)
                    history = emptyList()
                }) {
                    Text(tClearHistory)
                }
            }
        )
    }
}
