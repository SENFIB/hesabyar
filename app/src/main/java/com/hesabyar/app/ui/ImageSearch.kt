package com.hesabyar.app.ui

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.hesabyar.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

// ---------- مدل‌های داخلی جست‌وجوی تصویری ----------

private class ImgSig(val hash: Long, val colors: IntArray)

private class ImgItem(
    val id: Long,
    val name: String,
    val code: String,
    val price: Long,
    val stock: Int,
    val path: String
)

private class ImgMatch(val item: ImgItem, val score: Float)

// کش اثر انگشت تصویری هر عکس؛ با تغییر فایل دوباره ساخته می‌شود
private val imgSigCache = ConcurrentHashMap<String, Pair<Long, ImgSig>>()

// ---------- خواندن و کوچک‌سازی عکس ----------

private fun imgDecode(open: () -> InputStream?, maxSide: Int): Bitmap? {
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open()?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val big = maxOf(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (big / (sample * 2) >= maxSide) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        open()?.use { BitmapFactory.decodeStream(it, null, opts) }
    } catch (e: Exception) {
        null
    }
}

// مسیر عکس ممکن است کامل یا نسبی (داخل filesDir) باشد
private fun imgResolve(ctx: Context, path: String): File? {
    val direct = File(path)
    if (direct.exists()) return direct
    val inApp = File(ctx.filesDir, path)
    return if (inApp.exists()) inApp else null
}

// ---------- اثر انگشت تصویری ----------

private fun imgGray(p: Int): Int {
    val r = (p shr 16) and 0xFF
    val g = (p shr 8) and 0xFF
    val b = p and 0xFF
    return (r * 299 + g * 587 + b * 114) / 1000
}

// الگوی روشنایی (dHash ۶۴ بیتی) + میانگین رنگ در شبکه ۴×۴
private fun imgSignature(src: Bitmap): ImgSig {
    val gray = Bitmap.createScaledBitmap(src, 9, 8, true)
    var hash = 0L
    var bit = 0
    for (y in 0 until 8) {
        for (x in 0 until 8) {
            if (imgGray(gray.getPixel(x, y)) > imgGray(gray.getPixel(x + 1, y))) {
                hash = hash or (1L shl bit)
            }
            bit++
        }
    }

    val small = Bitmap.createScaledBitmap(src, 4, 4, true)
    val colors = IntArray(48)
    var k = 0
    for (y in 0 until 4) {
        for (x in 0 until 4) {
            val p = small.getPixel(x, y)
            colors[k++] = (p shr 16) and 0xFF
            colors[k++] = (p shr 8) and 0xFF
            colors[k++] = p and 0xFF
        }
    }
    return ImgSig(hash, colors)
}

// عددی بین صفر و یک؛ هرچه بزرگ‌تر، شباهت بیشتر
private fun imgSimilarity(a: ImgSig, b: ImgSig): Float {
    val hashPart = 1f - (a.hash xor b.hash).countOneBits() / 64f
    var diff = 0
    for (i in a.colors.indices) diff += abs(a.colors[i] - b.colors[i])
    val colorPart = 1f - diff / (a.colors.size * 255f)
    return 0.4f * hashPart + 0.6f * colorPart
}

private fun imgCachedSignature(file: File): ImgSig? {
    val key = file.absolutePath
    val modified = file.lastModified()
    imgSigCache[key]?.let { cached ->
        if (cached.first == modified) return cached.second
    }
    val bmp = imgDecode({ FileInputStream(file) }, 160) ?: return null
    val sig = imgSignature(bmp)
    imgSigCache[key] = modified to sig
    return sig
}

// ---------- خواندن کالاهای دارای عکس از پایگاه داده انبار (فقط خواندن) ----------

private fun imgLoadItems(ctx: Context): List<ImgItem> {
    val dbFile = ctx.getDatabasePath("hesabyar_inventory.db")
    if (!dbFile.exists()) return emptyList()

    val db = try {
        SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY)
    } catch (e: Exception) {
        return emptyList()
    }

    val out = ArrayList<ImgItem>()
    try {
        db.rawQuery(
            "SELECT id, name, code, price, stock, image_path FROM items " +
                "WHERE image_path IS NOT NULL AND image_path <> ''",
            null
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    ImgItem(
                        id = c.getLong(0),
                        name = c.getString(1) ?: "",
                        code = c.getString(2) ?: "",
                        price = c.getLong(3),
                        stock = c.getInt(4),
                        path = c.getString(5) ?: ""
                    )
                )
            }
        }
    } catch (e: Exception) {
        // اگر جدول یا ستونی نبود، فهرست خالی برگردانده می‌شود
    } finally {
        db.close()
    }
    return out
}

// جست‌وجوی اصلی: عکس ورودی را با همه عکس‌های کالاها مقایسه می‌کند
private fun imgSearch(ctx: Context, query: Bitmap): List<ImgMatch> {
    val q = imgSignature(query)
    val found = ArrayList<ImgMatch>()
    for (item in imgLoadItems(ctx)) {
        val file = imgResolve(ctx, item.path) ?: continue
        val sig = imgCachedSignature(file) ?: continue
        found.add(ImgMatch(item, imgSimilarity(q, sig)))
    }
    return found.sortedByDescending { it.score }.take(10)
}

// ---------- نمایش‌ها ----------

// بندانگشتی هر کالا
@Composable
private fun ImgThumb(path: String, size: Dp) {
    val ctx = LocalContext.current
    val img by produceState<ImageBitmap?>(initialValue = null, key1 = path) {
        value = withContext(Dispatchers.IO) {
            imgResolve(ctx, path)?.let { file ->
                imgDecode({ FileInputStream(file) }, 200)?.asImageBitmap()
            }
        }
    }
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        img?.let {
            Image(
                bitmap = it,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
private fun ImgResultRow(
    rank: Int,
    match: ImgMatch,
    currency: String,
    codeLabel: String,
    stockLabel: String
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ImgThumb(path = match.item.path, size = 64.dp)
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "$rank. ${match.item.name}",
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (match.item.code.isNotBlank()) {
                    Text(
                        text = "$codeLabel: ${match.item.code}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Text(
                    text = "$stockLabel: ${match.item.stock}  •  ${formatMoney(match.item.price)} $currency",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

// ---------- پنجره جست‌وجو با عکس ----------

@Composable
fun ImageSearchDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val currency = stringResource(R.string.currency)

    // همه متن‌ها باید داخل بدنه Compose ساخته شوند
    val titleText = tr("جست‌وجوی کالا با عکس", "Find item by photo", "البحث عن منتج بالصورة")
    val closeText = tr("بستن", "Close", "إغلاق")
    val hintText = tr(
        "عکس کالا را بگیرید یا از گالری انتخاب کنید. برنامه آن را با عکس کالاهای ذخیره‌شده مقایسه می‌کند.",
        "Take a photo of the item or pick one from the gallery. The app compares it with the saved item photos.",
        "التقط صورة للمنتج أو اختر واحدة من المعرض. يقارنها التطبيق بصور المنتجات المحفوظة."
    )
    val tipText = tr(
        "نکته: عکس نزدیک، واضح و با نور خوب، نتیجه بهتری می‌دهد.",
        "Tip: a close, sharp photo in good light gives better results.",
        "نصيحة: الصورة القريبة والواضحة والمضاءة جيدًا تعطي نتائج أفضل."
    )
    val cameraText = tr("گرفتن عکس", "Take photo", "التقاط صورة")
    val galleryText = tr("از گالری", "From gallery", "من المعرض")
    val searchingText = tr("در حال جست‌وجو…", "Searching…", "جارٍ البحث…")
    val emptyText = tr(
        "کالایی با عکس ذخیره‌شده پیدا نشد. ابتدا برای کالاها در انبار عکس ثبت کنید.",
        "No item with a saved photo was found. Add photos to your items in the store first.",
        "لم يتم العثور على منتج بصورة محفوظة. أضف صورًا للمنتجات في المخزن أولًا."
    )
    val resultsTitle = tr("نزدیک‌ترین کالاها", "Closest items", "أقرب المنتجات")
    val noCameraText = tr("برنامه دوربین پیدا نشد", "No camera app found", "لم يتم العثور على تطبيق الكاميرا")
    val codeLabel = tr("کد", "Code", "الرمز")
    val stockLabel = tr("موجودی", "Stock", "المخزون")

    var query by remember { mutableStateOf<Bitmap?>(null) }
    var results by remember { mutableStateOf<List<ImgMatch>?>(null) }
    var busy by remember { mutableStateOf(false) }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicturePreview()
    ) { bmp ->
        if (bmp != null) query = bmp
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            query = imgDecode({ ctx.contentResolver.openInputStream(uri) }, 320)
        }
    }

    // هر بار عکس جدیدی انتخاب شود، جست‌وجو اجرا می‌شود
    LaunchedEffect(query) {
        val q = query ?: return@LaunchedEffect
        busy = true
        results = withContext(Dispatchers.Default) { imgSearch(ctx, q) }
        busy = false
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = titleText,
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onDismiss) {
                        Text(closeText)
                    }
                }

                Text(hintText, style = MaterialTheme.typography.bodySmall)

                query?.let { bmp ->
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            try {
                                cameraLauncher.launch(null)
                            } catch (e: Exception) {
                                Toast.makeText(ctx, noCameraText, Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(cameraText)
                    }
                    OutlinedButton(
                        onClick = { galleryLauncher.launch("image/*") },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(galleryText)
                    }
                }

                if (busy) {
                    Text(searchingText, style = MaterialTheme.typography.bodyMedium)
                }

                val current = results
                if (current != null && !busy) {
                    if (current.isEmpty()) {
                        Text(emptyText, color = MaterialTheme.colorScheme.error)
                    } else {
                        Text(resultsTitle, style = MaterialTheme.typography.titleMedium)
                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            itemsIndexed(
                                current,
                                key = { _, m -> m.item.id }
                            ) { index, m ->
                                ImgResultRow(
                                    rank = index + 1,
                                    match = m,
                                    currency = currency,
                                    codeLabel = codeLabel,
                                    stockLabel = stockLabel
                                )
                            }
                        }
                    }
                } else if (current == null && !busy) {
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        tipText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
