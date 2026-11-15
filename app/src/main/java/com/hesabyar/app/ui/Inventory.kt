package com.hesabyar.app.ui

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// ---------- مدل‌ها ----------

data class InvItem(
    val id: Long = 0L,
    val name: String,
    val description: String = "",
    val code: String = "",
    val price: Long = 0L,
    val stock: Int = 0,
    val threshold: Int = 0,
    val imagePath: String? = null,
    val views: Long = 0L
)

data class SaleRecord(
    val id: Long,
    val itemId: Long,
    val itemName: String,
    val imagePath: String?,
    val qty: Int,
    val unitPrice: Long,
    val total: Long,
    val dateMillis: Long
)

private data class TopRow(
    val itemId: Long,
    val name: String,
    val imagePath: String?,
    val qty: Int,
    val revenue: Long
)

// ---------- دیتابیس ----------

private const val INV_DB_VERSION = 4

private class InvDbHelper(ctx: Context) :
    SQLiteOpenHelper(ctx, "hesabyar_inventory.db", null, INV_DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        createTables(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        createTables(db)
    }
}

private fun createTables(db: SQLiteDatabase) {
    db.execSQL(
        "CREATE TABLE IF NOT EXISTS items (" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
            "name TEXT NOT NULL, " +
            "description TEXT, " +
            "code TEXT, " +
            "price INTEGER NOT NULL DEFAULT 0, " +
            "stock INTEGER NOT NULL DEFAULT 0, " +
            "threshold INTEGER NOT NULL DEFAULT 0, " +
            "image_path TEXT, " +
            "views INTEGER NOT NULL DEFAULT 0)"
    )
    try {
        db.execSQL("ALTER TABLE items ADD COLUMN image_path TEXT")
    } catch (e: Exception) {
        // ستون از قبل وجود دارد
    }
    try {
        db.execSQL("ALTER TABLE items ADD COLUMN views INTEGER NOT NULL DEFAULT 0")
    } catch (e: Exception) {
        // ستون از قبل وجود دارد
    }
    db.execSQL(
        "CREATE TABLE IF NOT EXISTS sales (" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
            "item_id INTEGER NOT NULL, " +
            "item_name TEXT NOT NULL, " +
            "image_path TEXT, " +
            "qty INTEGER NOT NULL, " +
            "unit_price INTEGER NOT NULL, " +
            "total INTEGER NOT NULL, " +
            "date_millis INTEGER NOT NULL)"
    )
}

private fun Cursor.str(col: String): String? = getString(getColumnIndexOrThrow(col))
private fun Cursor.num(col: String): Long = getLong(getColumnIndexOrThrow(col))

private fun Cursor.toItem(): InvItem = InvItem(
    id = num("id"),
    name = str("name") ?: "",
    description = str("description") ?: "",
    code = str("code") ?: "",
    price = num("price"),
    stock = num("stock").toInt(),
    threshold = num("threshold").toInt(),
    imagePath = str("image_path")?.takeIf { it.isNotBlank() },
    views = num("views")
)

private fun Cursor.toSale(): SaleRecord = SaleRecord(
    id = num("id"),
    itemId = num("item_id"),
    itemName = str("item_name") ?: "",
    imagePath = str("image_path")?.takeIf { it.isNotBlank() },
    qty = num("qty").toInt(),
    unitPrice = num("unit_price"),
    total = num("total"),
    dateMillis = num("date_millis")
)

object InvStore {
    private var helper: InvDbHelper? = null

    private fun db(ctx: Context): SQLiteDatabase {
        val h = helper ?: InvDbHelper(ctx.applicationContext).also { helper = it }
        return h.writableDatabase
    }

    fun all(ctx: Context): List<InvItem> {
        val out = ArrayList<InvItem>()
        db(ctx).query("items", null, null, null, null, null, "name COLLATE NOCASE ASC").use { c ->
            while (c.moveToNext()) out.add(c.toItem())
        }
        return out
    }

    fun salesAll(ctx: Context): List<SaleRecord> {
        val out = ArrayList<SaleRecord>()
        db(ctx).query("sales", null, null, null, null, null, "date_millis DESC").use { c ->
            while (c.moveToNext()) out.add(c.toSale())
        }
        return out
    }

    fun save(ctx: Context, item: InvItem): Long {
        val v = ContentValues().apply {
            put("name", item.name)
            put("description", item.description)
            put("code", item.code)
            put("price", item.price)
            put("stock", item.stock)
            put("threshold", item.threshold)
            put("image_path", item.imagePath)
        }
        return if (item.id == 0L) {
            db(ctx).insert("items", null, v)
        } else {
            db(ctx).update("items", v, "id = ?", arrayOf(item.id.toString()))
            item.id
        }
    }

    fun delete(ctx: Context, item: InvItem) {
        db(ctx).delete("items", "id = ?", arrayOf(item.id.toString()))
        item.imagePath?.let { File(it).delete() }
    }

    fun addView(ctx: Context, itemId: Long) {
        db(ctx).execSQL("UPDATE items SET views = views + 1 WHERE id = ?", arrayOf<Any>(itemId))
    }

    // موجودی را کم می‌کند و فروش را ثبت می‌کند. اگر موجودی کافی نباشد false برمی‌گرداند.
    fun sell(ctx: Context, item: InvItem, qty: Int, unitPrice: Long, dateMillis: Long): Boolean {
        val d = db(ctx)
        d.beginTransaction()
        try {
            val current = d.query(
                "items", arrayOf("stock"), "id = ?", arrayOf(item.id.toString()), null, null, null
            ).use { c -> if (c.moveToFirst()) c.getInt(0) else -1 }

            if (qty <= 0 || current < qty) return false

            d.execSQL("UPDATE items SET stock = stock - ? WHERE id = ?", arrayOf<Any>(qty, item.id))
            val cv = ContentValues().apply {
                put("item_id", item.id)
                put("item_name", item.name)
                put("image_path", item.imagePath)
                put("qty", qty)
                put("unit_price", unitPrice)
                put("total", unitPrice * qty)
                put("date_millis", dateMillis)
            }
            d.insertOrThrow("sales", null, cv)
            d.setTransactionSuccessful()
            return true
        } finally {
            d.endTransaction()
        }
    }
}

// ---------- کمکی‌ها ----------

// عکس انتخاب‌شده را داخل حافظه خصوصی برنامه کپی می‌کند
private fun copyImageToApp(ctx: Context, uri: Uri): String? {
    return try {
        val dir = File(ctx.filesDir, "inv_images")
        dir.mkdirs()
        val out = File(dir, "item_" + System.currentTimeMillis() + ".jpg")
        val input = ctx.contentResolver.openInputStream(uri) ?: return null
        input.use { src -> out.outputStream().use { dst -> src.copyTo(dst) } }
        out.absolutePath
    } catch (e: Exception) {
        null
    }
}

private fun decodeThumb(path: String): ImageBitmap? {
    val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
    return BitmapFactory.decodeFile(path, opts)?.asImageBitmap()
}

// جداکننده هزار‌رقم هنگام تایپ مبلغ؛ فقط نمایش است و مقدار واقعی همان ارقام است
private class InvAmountTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text
        val n = raw.length
        val sb = StringBuilder()
        for (i in raw.indices) {
            if (i > 0 && (n - i) % 3 == 0) sb.append(',')
            sb.append(raw[i])
        }
        val out = sb.toString()
        val mapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int {
                if (offset <= 0) return 0
                var seen = 0
                for (idx in out.indices) {
                    if (out[idx].isDigit()) {
                        seen++
                        if (seen == offset) return idx + 1
                    }
                }
                return out.length
            }

            override fun transformedToOriginal(offset: Int): Int {
                val end = offset.coerceIn(0, out.length)
                var count = 0
                for (idx in 0 until end) {
                    if (out[idx].isDigit()) count++
                }
                return count
            }
        }
        return TransformedText(AnnotatedString(out), mapping)
    }
}

// سطح موجودی، T = حد هشدار که کاربر تعیین می‌کند
// 0 سبز: بیش از ۳T
// 1 نارنجی: بیش از ۲T تا ۳T
// 2 قرمز: بیش از T تا ۲T
// 3 بنفش: T یا کمتر
private fun invLevel(stock: Int, t: Int): Int = when {
    stock > 3 * t -> 0
    stock > 2 * t -> 1
    stock > t -> 2
    else -> 3
}

private fun invColorOf(level: Int): Color = when (level) {
    0 -> Color(0xFF2E7D32)
    1 -> Color(0xFFEF6C00)
    2 -> Color(0xFFD32F2F)
    else -> Color(0xFF7B1FA2)
}

// ---------- صفحه ----------

@Composable
fun InventoryScreen(vm: MainViewModel, regionType: CalendarType) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("hesabyar_inventory", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var stockItems by remember { mutableStateOf(InvStore.all(ctx)) }
    var sales by remember { mutableStateOf(InvStore.salesAll(ctx)) }
    // ۰ کالاها، ۱ گزارش فروش، ۲ بیشترین فروش، ۳ بیشترین بررسی
    var view by remember { mutableStateOf(0) }
    var query by remember { mutableStateOf("") }
    var sellTarget by remember { mutableStateOf<InvItem?>(null) }
    var deleteTarget by remember { mutableStateOf<InvItem?>(null) }
    var detailId by remember { mutableStateOf<Long?>(null) }

    // نمایش یا عدم نمایش بخش‌ها به انتخاب کاربر
    var showForm by remember { mutableStateOf(false) }
    var showTotals by remember { mutableStateOf(true) }
    var showLegend by remember { mutableStateOf(false) }

    // وضعیت فرم
    var editId by remember { mutableStateOf(0L) }
    var fName by remember { mutableStateOf("") }
    var fDesc by remember { mutableStateOf("") }
    var fCode by remember { mutableStateOf("") }
    var fPrice by remember { mutableStateOf("") }
    var fStock by remember { mutableStateOf("") }
    var fThreshold by remember { mutableStateOf("") }
    var fImage by remember { mutableStateOf<String?>(null) }
    var fOriginalImage by remember { mutableStateOf<String?>(null) }
    var triedSave by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val path = copyImageToApp(ctx, uri)
            if (path != null) fImage = path
        }
    }

    // انتقال یک‌باره کالاهای قدیمی به این بخش
    LaunchedEffect(Unit) {
        if (!prefs.getBoolean("migrated", false)) {
            vm.products.value.forEach { p ->
                InvStore.save(
                    ctx,
                    InvItem(
                        name = p.name,
                        description = "",
                        code = p.code,
                        price = p.price,
                        stock = p.stock,
                        threshold = 0,
                        imagePath = null
                    )
                )
            }
            prefs.edit().putBoolean("migrated", true).apply()
            stockItems = InvStore.all(ctx)
        }
    }

    fun reload() {
        stockItems = InvStore.all(ctx)
        sales = InvStore.salesAll(ctx)
    }

    fun resetForm() {
        editId = 0L
        fName = ""
        fDesc = ""
        fCode = ""
        fPrice = ""
        fStock = ""
        fThreshold = ""
        fImage = null
        fOriginalImage = null
        triedSave = false
        showForm = false
    }

    fun startEdit(inv: InvItem) {
        editId = inv.id
        fName = inv.name
        fDesc = inv.description
        fCode = inv.code
        fPrice = inv.price.toString()
        fStock = inv.stock.toString()
        fThreshold = inv.threshold.toString()
        fImage = inv.imagePath
        fOriginalImage = inv.imagePath
        triedSave = false
        showForm = true
        view = 0
        scope.launch { listState.animateScrollToItem(0) }
    }

    fun openDetail(inv: InvItem) {
        InvStore.addView(ctx, inv.id)
        stockItems = InvStore.all(ctx)
        detailId = inv.id
    }

    // همه متن‌ها فقط اینجا ساخته می‌شوند، نه داخل callbackها
    val tTabItems = tr("کالاها", "Items", "المنتجات")
    val tTabSales = tr("گزارش فروش", "Sales report", "تقرير المبيعات")
    val tTabBest = tr("بیشترین فروش", "Best sellers", "الأكثر مبيعًا")
    val tTabViews = tr("بیشترین بررسی", "Most viewed", "الأكثر مشاهدة")
    val tAddItem = tr("ثبت کالای جدید", "Add new item", "إضافة منتج جديد")
    val tHideForm = tr("بستن فرم", "Close form", "إغلاق النموذج")
    val tShowTotals = tr("جمع‌ها", "Totals", "الإجماليات")
    val tShowLegend = tr("راهنمای رنگ‌ها", "Color guide", "دليل الألوان")
    val tEditTitle = tr("ویرایش کالا", "Edit item", "تعديل المنتج")
    val tNewTitle = tr("ثبت کالای جدید", "New item", "منتج جديد")
    val tName = tr("نام کالا", "Item name", "اسم المنتج")
    val tDesc = tr("توضیحات کالا", "Description", "الوصف")
    val tCode = tr("کد کالا", "Item code", "رمز المنتج")
    val tPrice = tr("قیمت", "Price", "السعر")
    val tStock = tr("موجودی", "Stock", "المخزون")
    val tThresholdLabel = tr("حد هشدار موجودی", "Low-stock alert level", "حد تنبيه المخزون")
    val tPickPhoto = tr("انتخاب عکس", "Choose photo", "اختيار صورة")
    val tRemovePhoto = tr("حذف عکس", "Remove photo", "حذف الصورة")
    val tNameRequired = tr("نام کالا را وارد کنید", "Enter the item name", "أدخل اسم المنتج")
    val tSave = tr("ذخیره کالا", "Save item", "حفظ المنتج")
    val tUpdate = tr("ثبت تغییرات", "Save changes", "حفظ التغييرات")
    val tCancel = tr("انصراف", "Cancel", "إلغاء")
    val tSearch = tr("جست‌وجو با نام یا کد", "Search by name or code", "بحث بالاسم أو الرمز")
    val tTotalValue = tr("ارزش کل موجودی", "Total stock value", "القيمة الإجمالية للمخزون")
    val tCount = tr("تعداد کالاها", "Items", "عدد المنتجات")
    val tCritical = tr("کالاهای بحرانی", "Critical items", "المنتجات الحرجة")
    val tEmpty = tr("هنوز کالایی ثبت نشده", "No items yet", "لا توجد منتجات بعد")
    val tNoSales = tr("هنوز فروشی ثبت نشده", "No sales yet", "لا توجد مبيعات بعد")
    val tSalesCount = tr("تعداد فروش", "Number of sales", "عدد عمليات البيع")
    val tSalesTotal = tr("جمع فروش", "Total sales", "إجمالي المبيعات")
    val tSoldUnits = tr("تعداد فروخته‌شده", "Units sold", "الوحدات المباعة")
    val tBestHeader = tr("کالاها بر اساس تعداد فروش", "Items by units sold", "المنتجات حسب الكمية المباعة")
    val tViewsHeader = tr("کالاها بر اساس تعداد بررسی", "Items by number of views", "المنتجات حسب عدد المشاهدات")
    val tViewsHint = tr(
        "هر بار که کالایی را باز کنید، یک بررسی ثبت می‌شود",
        "Each time you open an item, one view is recorded",
        "في كل مرة تفتح فيها منتجًا تُسجَّل مشاهدة واحدة"
    )
    val tDelete = tr("حذف", "Delete", "حذف")
    val tDeleteAsk = tr("این کالا حذف شود؟", "Delete this item?", "هل تريد حذف هذا المنتج؟")
    val tUnits = tr("عدد", "pcs", "قطعة")
    val tSearchNone = tr("کالایی با این مشخصات پیدا نشد", "No item matches", "لا يوجد منتج مطابق")
    val tNoViews = tr("هنوز کالایی بررسی نشده", "No item has been viewed yet", "لم يتم عرض أي منتج بعد")
    val tOpen = tr("مشاهده", "View", "عرض")
    val tViewsWord = tr("بررسی", "views", "مشاهدة")

    val q = query.trim().lowercase()
    val shown = if (q.isEmpty()) {
        stockItems
    } else {
        stockItems.filter { it.name.lowercase().contains(q) || it.code.lowercase().contains(q) }
    }
    // سه ستون مساوی در هر ردیف
    val gridRows = shown.chunked(3)
    val viewed = stockItems.filter { it.views > 0L }.sortedByDescending { it.views }
    val totalValue = stockItems.sumOf { it.price * it.stock }
    val criticalCount = stockItems.count { it.threshold > 0 && it.stock <= it.threshold }
    val salesTotal = sales.sumOf { it.total }
    val soldUnits = sales.sumOf { it.qty }
    val topRows: List<TopRow> = sales
        .groupBy { it.itemId }
        .map { entry ->
            val rows = entry.value
            TopRow(
                itemId = entry.key,
                name = rows[0].itemName,
                imagePath = rows[0].imagePath,
                qty = rows.sumOf { it.qty },
                revenue = rows.sumOf { it.total }
            )
        }
        .sortedByDescending { it.qty }
    val maxQty = topRows.maxOfOrNull { it.qty } ?: 0
    val maxViews = viewed.maxOfOrNull { it.views } ?: 0L

    val countText = stockItems.size.toString()
    val totalValueText = formatMoney(totalValue)
    val criticalText = criticalCount.toString()
    val salesCountText = sales.size.toString()
    val salesTotalText = formatMoney(salesTotal)
    val soldUnitsText = soldUnits.toString()
    val formTitle = if (editId == 0L) tNewTitle else tEditTitle
    val formToggleText = if (showForm) tHideForm else tAddItem

    // دیالوگ فروش
    val target = sellTarget
    if (target != null) {
        SellDialog(
            item = target,
            onDismiss = { sellTarget = null },
            onConfirm = { qty, unitPrice ->
                val now = System.currentTimeMillis()
                val ok = InvStore.sell(ctx, target, qty, unitPrice, now)
                if (ok) {
                    val title = target.name + " × " + qty.toString()
                    vm.addTransaction(title, unitPrice * qty, true, "sale")
                    reload()
                }
                sellTarget = null
            }
        )
    }

    // دیالوگ مشخصات کالا
    val detailNow = detailId?.let { id -> stockItems.find { it.id == id } }
    if (detailNow != null) {
        ItemDetailDialog(item = detailNow, onDismiss = { detailId = null })
    }

    // دیالوگ حذف
    val delTarget = deleteTarget
    if (delTarget != null) {
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(tDeleteAsk) },
            text = { Text(delTarget.name) },
            confirmButton = {
                Button(onClick = {
                    InvStore.delete(ctx, delTarget)
                    if (editId == delTarget.id) resetForm()
                    deleteTarget = null
                    reload()
                }) { Text(tDelete) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text(tCancel) }
            }
        )
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // تب‌های اصلی: چهار ستون مساوی
        item {
            EqualRow {
                InvTabButton(label = tTabItems, selected = view == 0, onClick = { view = 0 }, modifier = Modifier.weight(1f))
                InvTabButton(label = tTabSales, selected = view == 1, onClick = { view = 1 }, modifier = Modifier.weight(1f))
                InvTabButton(label = tTabBest, selected = view == 2, onClick = { view = 2 }, modifier = Modifier.weight(1f))
                InvTabButton(label = tTabViews, selected = view == 3, onClick = { view = 3 }, modifier = Modifier.weight(1f))
            }
        }

        when (view) {
            0 -> {
                // دکمه‌های نمایش بخش‌ها: سه ستون مساوی
                item {
                    EqualRow {
                        InvTabButton(
                            label = formToggleText,
                            selected = showForm,
                            onClick = { if (showForm) resetForm() else showForm = true },
                            modifier = Modifier.weight(1f)
                        )
                        InvTabButton(
                            label = tShowTotals,
                            selected = showTotals,
                            onClick = { showTotals = !showTotals },
                            modifier = Modifier.weight(1f)
                        )
                        InvTabButton(
                            label = tShowLegend,
                            selected = showLegend,
                            onClick = { showLegend = !showLegend },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                // فرم ثبت و ویرایش (فقط وقتی کاربر بخواهد)
                if (showForm) {
                    item {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(formTitle, fontWeight = FontWeight.Bold)
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    ProductImage(path = fImage, modifier = Modifier.size(96.dp))
                                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        OutlinedButton(onClick = { picker.launch("image/*") }) {
                                            Text(tPickPhoto)
                                        }
                                        if (fImage != null) {
                                            TextButton(onClick = { fImage = null }) {
                                                Text(tRemovePhoto)
                                            }
                                        }
                                    }
                                }

                                OutlinedTextField(
                                    value = fName,
                                    onValueChange = { fName = it },
                                    label = { Text(tName) },
                                    singleLine = true,
                                    isError = triedSave && fName.isBlank(),
                                    modifier = Modifier.fillMaxWidth()
                                )
                                if (triedSave && fName.isBlank()) {
                                    Text(tNameRequired, color = MaterialTheme.colorScheme.error)
                                }
                                OutlinedTextField(
                                    value = fDesc,
                                    onValueChange = { fDesc = it },
                                    label = { Text(tDesc) },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                OutlinedTextField(
                                    value = fCode,
                                    onValueChange = { fCode = it },
                                    label = { Text(tCode) },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                OutlinedTextField(
                                    value = fPrice,
                                    onValueChange = { fPrice = digitsOnly(it) },
                                    label = { Text(tPrice) },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    visualTransformation = InvAmountTransformation(),
                                    modifier = Modifier.fillMaxWidth()
                                )
                                OutlinedTextField(
                                    value = fStock,
                                    onValueChange = { fStock = digitsOnly(it) },
                                    label = { Text(tStock) },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.fillMaxWidth()
                                )
                                OutlinedTextField(
                                    value = fThreshold,
                                    onValueChange = { fThreshold = digitsOnly(it) },
                                    label = { Text(tThresholdLabel) },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.fillMaxWidth()
                                )

                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = {
                                        triedSave = true
                                        if (fName.isNotBlank()) {
                                            val orig = fOriginalImage
                                            InvStore.save(
                                                ctx,
                                                InvItem(
                                                    id = editId,
                                                    name = fName.trim(),
                                                    description = fDesc.trim(),
                                                    code = fCode.trim(),
                                                    price = fPrice.toLongOrNull() ?: 0L,
                                                    stock = fStock.toIntOrNull() ?: 0,
                                                    threshold = fThreshold.toIntOrNull() ?: 0,
                                                    imagePath = fImage
                                                )
                                            )
                                            // اگر عکس عوض شد، عکس قبلی پاک شود
                                            if (orig != null && orig != fImage) File(orig).delete()
                                            resetForm()
                                            reload()
                                        }
                                    }) {
                                        Text(if (editId == 0L) tSave else tUpdate)
                                    }
                                    OutlinedButton(onClick = { resetForm() }) { Text(tCancel) }
                                }
                            }
                        }
                    }
                }

                // جمع‌ها (قابل نمایش یا پنهان)
                if (showTotals) {
                    item {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(tCount + ": " + countText)
                                Text(tTotalValue + ": " + totalValueText, fontWeight = FontWeight.Bold)
                                Text(tCritical + ": " + criticalText)
                            }
                        }
                    }
                }

                // راهنمای رنگ‌ها (قابل نمایش یا پنهان)
                if (showLegend) {
                    item {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                InvLegend()
                            }
                        }
                    }
                }

                // جست‌وجو
                item {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text(tSearch) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                if (stockItems.isEmpty()) {
                    item { Text(tEmpty) }
                } else if (shown.isEmpty()) {
                    item { Text(tSearchNone) }
                }

                // نمایش سه‌ستونه کالاها با ارتفاع یکسان در هر ردیف
                items(gridRows, key = { row -> row.first().id }) { row ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(IntrinsicSize.Max)
                    ) {
                        row.forEach { inv ->
                            InvGridCard(
                                item = inv,
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight(),
                                onSell = { sellTarget = inv },
                                onView = { openDetail(inv) },
                                onEdit = { startEdit(inv) },
                                onDelete = { deleteTarget = inv }
                            )
                        }
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }

            1 -> {
                // گزارش فروش
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(tSalesCount + ": " + salesCountText)
                            Text(tSalesTotal + ": " + salesTotalText, fontWeight = FontWeight.Bold)
                            Text(tSoldUnits + ": " + soldUnitsText)
                        }
                    }
                }

                if (sales.isEmpty()) {
                    item { Text(tNoSales) }
                }

                items(sales, key = { it.id }) { sale ->
                    SaleRow(sale = sale, regionType = regionType)
                }
            }

            2 -> {
                // بیشترین فروش
                item {
                    Text(tBestHeader, fontWeight = FontWeight.Bold)
                }

                if (topRows.isEmpty()) {
                    item { Text(tNoSales) }
                }

                itemsIndexed(topRows, key = { _, row -> row.itemId }) { index, row ->
                    val frac = if (maxQty > 0) row.qty.toFloat() / maxQty.toFloat() else 0f
                    RankCard(
                        rank = index + 1,
                        name = row.name,
                        imagePath = row.imagePath,
                        line1 = row.qty.toString() + " " + tUnits,
                        line2 = formatMoney(row.revenue),
                        fraction = frac,
                        openLabel = "",
                        onOpen = null
                    )
                }
            }

            else -> {
                // بیشترین بررسی
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(tViewsHeader, fontWeight = FontWeight.Bold)
                            Text(tViewsHint, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                if (viewed.isEmpty()) {
                    item { Text(tNoViews) }
                }

                itemsIndexed(viewed, key = { _, inv -> inv.id }) { index, inv ->
                    val frac = if (maxViews > 0L) inv.views.toFloat() / maxViews.toFloat() else 0f
                    RankCard(
                        rank = index + 1,
                        name = inv.name,
                        imagePath = inv.imagePath,
                        line1 = inv.views.toString() + " " + tViewsWord,
                        line2 = tStock + ": " + inv.stock.toString(),
                        fraction = frac,
                        openLabel = tOpen,
                        onOpen = { openDetail(inv) }
                    )
                }
            }
        }
    }
}

// ---------- قسمت‌های کوچک ----------

// یک ردیف با ستون‌های هم‌ارتفاع
@Composable
private fun EqualRow(content: @Composable RowScope.() -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Max),
        content = content
    )
}

@Composable
private fun InvTabButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val pad = PaddingValues(horizontal = 2.dp, vertical = 4.dp)
    if (selected) {
        Button(onClick = onClick, modifier = modifier.fillMaxHeight(), contentPadding = pad) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier.fillMaxHeight(), contentPadding = pad) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun ProductImage(path: String?, modifier: Modifier) {
    val bmp by produceState<ImageBitmap?>(initialValue = null, key1 = path) {
        value = if (path == null) null else withContext(Dispatchers.IO) { decodeThumb(path) }
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        val shownBmp = bmp
        if (shownBmp != null) {
            Image(
                bitmap = shownBmp,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Text("—", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun InvLabel(level: Int): String = when (level) {
    0 -> tr("موجود", "In stock", "متوفر")
    1 -> tr("رو به کاهش", "Decreasing", "في انخفاض")
    2 -> tr("کم", "Low", "منخفض")
    else -> tr("بحرانی", "Critical", "حرج")
}

@Composable
private fun InvLegend() {
    val l0 = tr("بیشتر از ۳ برابر حد هشدار", "More than 3× the alert level", "أكثر من 3 أضعاف حد التنبيه")
    val l1 = tr("بیشتر از ۲ تا ۳ برابر حد هشدار", "2× to 3× the alert level", "من ضعفين إلى 3 أضعاف الحد")
    val l2 = tr("بیشتر از حد هشدار تا ۲ برابر", "Above alert level up to 2×", "أعلى من الحد حتى ضعفين")
    val l3 = tr("برابر حد هشدار یا کمتر", "At or below alert level", "عند حد التنبيه أو أقل")
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        LegendRow(Color(0xFF2E7D32), l0)
        LegendRow(Color(0xFFEF6C00), l1)
        LegendRow(Color(0xFFD32F2F), l2)
        LegendRow(Color(0xFF7B1FA2), l3)
    }
}

@Composable
private fun LegendRow(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .background(color, RoundedCornerShape(6.dp))
        )
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun InvGridCard(
    item: InvItem,
    modifier: Modifier,
    onSell: () -> Unit,
    onView: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val level = invLevel(item.stock, item.threshold)
    val levelColor = invColorOf(level)
    val label = InvLabel(level)
    val tCode = tr("کد", "Code", "الرمز")
    val tPrice = tr("قیمت", "Price", "السعر")
    val tStock = tr("موجودی", "Stock", "المخزون")
    val tSell = tr("فروش", "Sell", "بيع")
    val tOpen = tr("مشاهده", "View", "عرض")
    val tEdit = tr("ویرایش", "Edit", "تعديل")
    val tDel = tr("حذف", "Delete", "حذف")
    val codeText = tCode + ": " + item.code
    val priceText = tPrice + ": " + formatMoney(item.price)
    val stockText = tStock + ": " + item.stock.toString()

    Card(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(6.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            ProductImage(
                path = item.imagePath,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(90.dp)
            )
            Text(
                item.name,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (item.code.isNotBlank()) {
                Text(
                    codeText,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                priceText,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                stockText,
                style = MaterialTheme.typography.labelMedium,
                color = levelColor,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = levelColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            // دکمه‌ها به پایین کارت چسبیده‌اند تا همه کارت‌های یک ردیف هم‌تراز باشند
            Spacer(Modifier.weight(1f))

            Row(
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Button(
                    onClick = onSell,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(0.dp)
                ) { Text(tSell, style = MaterialTheme.typography.labelSmall, maxLines = 1) }
                OutlinedButton(
                    onClick = onView,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(0.dp)
                ) { Text(tOpen, style = MaterialTheme.typography.labelSmall, maxLines = 1) }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedButton(
                    onClick = onEdit,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(0.dp)
                ) { Text(tEdit, style = MaterialTheme.typography.labelSmall, maxLines = 1) }
                TextButton(
                    onClick = onDelete,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text(
                        tDel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
private fun RankCard(
    rank: Int,
    name: String,
    imagePath: String?,
    line1: String,
    line2: String,
    fraction: Float,
    openLabel: String,
    onOpen: (() -> Unit)?
) {
    val f = fraction.coerceIn(0f, 1f)
    val rankText = rank.toString()
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(rankText, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(10.dp))
                ProductImage(path = imagePath, modifier = Modifier.size(48.dp))
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(line1, style = MaterialTheme.typography.bodySmall)
                    Text(line2, style = MaterialTheme.typography.bodySmall)
                }
                if (onOpen != null) {
                    OutlinedButton(onClick = onOpen) { Text(openLabel) }
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(f)
                        .height(6.dp)
                        .background(MaterialTheme.colorScheme.primary)
                )
            }
        }
    }
}

@Composable
private fun ItemDetailDialog(item: InvItem, onDismiss: () -> Unit) {
    val tTitle = tr("مشخصات کالا", "Item details", "تفاصيل المنتج")
    val tCode = tr("کد", "Code", "الرمز")
    val tPrice = tr("قیمت", "Price", "السعر")
    val tStock = tr("موجودی", "Stock", "المخزون")
    val tViews = tr("تعداد بررسی", "Views", "عدد المشاهدات")
    val tNoDesc = tr("توضیحی ثبت نشده", "No description", "لا يوجد وصف")
    val tClose = tr("بستن", "Close", "إغلاق")
    val priceText = formatMoney(item.price)
    val stockText = item.stock.toString()
    val viewsText = item.views.toString()
    val descText = if (item.description.isBlank()) tNoDesc else item.description

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tTitle) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ProductImage(path = item.imagePath, modifier = Modifier.size(120.dp))
                Text(item.name, fontWeight = FontWeight.Bold)
                if (item.code.isNotBlank()) {
                    Text(tCode + ": " + item.code)
                }
                Text(tPrice + ": " + priceText)
                Text(tStock + ": " + stockText)
                Text(tViews + ": " + viewsText)
                Text(descText, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(tClose) }
        }
    )
}

@Composable
private fun SaleRow(sale: SaleRecord, regionType: CalendarType) {
    val dateText = formatWith(sale.dateMillis, "yyyy/MM/dd HH:mm", deviceLocale(), regionType.timeZone)
    val unitText = formatMoney(sale.unitPrice)
    val totalText = formatMoney(sale.total)
    val qtyText = sale.qty.toString()
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ProductImage(path = sale.imagePath, modifier = Modifier.size(48.dp))
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(sale.itemName, fontWeight = FontWeight.Bold)
                Text(
                    qtyText + " × " + unitText + " = " + totalText,
                    style = MaterialTheme.typography.bodySmall
                )
                Text(dateText, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun SellDialog(
    item: InvItem,
    onDismiss: () -> Unit,
    onConfirm: (Int, Long) -> Unit
) {
    var qtyText by remember { mutableStateOf("1") }
    var priceText by remember { mutableStateOf(item.price.toString()) }

    val qty = qtyText.toIntOrNull() ?: 0
    val unit = priceText.toLongOrNull() ?: 0L
    val tooMany = qty > item.stock
    val invalid = qty <= 0 || tooMany

    val totalText = formatMoney(unit * qty)
    val stockText = item.stock.toString()
    val itemName = item.name

    val tTitle = tr("فروش " + itemName, "Sell " + itemName, "بيع " + itemName)
    val tStockInfo = tr("موجودی فعلی: " + stockText, "Current stock: " + stockText, "المخزون الحالي: " + stockText)
    val tQty = tr("تعداد فروش", "Quantity", "الكمية")
    val tUnit = tr("قیمت فروش واحد", "Unit sale price", "سعر البيع للوحدة")
    val tTotal = tr("جمع فروش: " + totalText, "Total: " + totalText, "الإجمالي: " + totalText)
    val tNotEnough = tr("موجودی کافی نیست", "Not enough stock", "المخزون غير كافٍ")
    val tConfirm = tr("ثبت فروش", "Record sale", "تسجيل البيع")
    val tCancel = tr("انصراف", "Cancel", "إلغاء")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tTitle) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tStockInfo)
                OutlinedTextField(
                    value = qtyText,
                    onValueChange = { qtyText = digitsOnly(it) },
                    label = { Text(tQty) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = priceText,
                    onValueChange = { priceText = digitsOnly(it) },
                    label = { Text(tUnit) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    visualTransformation = InvAmountTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                Text(tTotal, fontWeight = FontWeight.Bold)
                if (tooMany) {
                    Text(tNotEnough, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(qty, unit) },
                enabled = !invalid
            ) { Text(tConfirm) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(tCancel) }
        }
    )
}
