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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// ---------- Models ----------

data class InvItem(
    val id: Long = 0L,
    val name: String,
    val description: String = "",
    val code: String = "",
    val price: Long = 0L,
    val stock: Int = 0,
    val threshold: Int = 0,
    val imagePath: String? = null
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

// ---------- Database ----------

private const val INV_DB_VERSION = 3

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
            "image_path TEXT)"
    )
    // برای دیتابیس‌های قدیمی که ستون عکس ندارند
    try {
        db.execSQL("ALTER TABLE items ADD COLUMN image_path TEXT")
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
    imagePath = str("image_path")?.takeIf { it.isNotBlank() }
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

// ---------- Helpers ----------

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

// ---------- Screen ----------

@Composable
fun InventoryScreen(vm: MainViewModel, regionType: CalendarType) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("hesabyar_inventory", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var stockItems by remember { mutableStateOf(InvStore.all(ctx)) }
    var sales by remember { mutableStateOf(InvStore.salesAll(ctx)) }
    var view by remember { mutableStateOf(0) } // ۰ = کالاها، ۱ = گزارش فروش
    var query by remember { mutableStateOf("") }
    var sellTarget by remember { mutableStateOf<InvItem?>(null) }
    var deleteTarget by remember { mutableStateOf<InvItem?>(null) }

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
        scope.launch { listState.animateScrollToItem(0) }
    }

    // همه متن‌ها فقط اینجا ساخته می‌شوند، نه داخل callbackها
    val tItemsTab = tr("کالاها", "Items", "المنتجات")
    val tSalesTab = tr("گزارش فروش", "Sales report", "تقرير المبيعات")
    val tNew = tr("ثبت کالای جدید", "Add new item", "إضافة منتج جديد")
    val tEditTitle = tr("ویرایش کالا", "Edit item", "تعديل المنتج")
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
    val tTop = tr("پرفروش‌ترین کالاها", "Best sellers", "الأكثر مبيعًا")
    val tDelete = tr("حذف", "Delete", "حذف")
    val tDeleteAsk = tr("این کالا حذف شود؟", "Delete this item?", "هل تريد حذف هذا المنتج؟")
    val tUnits = tr("عدد", "pcs", "قطعة")
    val tSearchNone = tr("کالایی با این مشخصات پیدا نشد", "No item matches", "لا يوجد منتج مطابق")

    val q = query.trim().lowercase()
    val shown = if (q.isEmpty()) {
        stockItems
    } else {
        stockItems.filter { it.name.lowercase().contains(q) || it.code.lowercase().contains(q) }
    }
    val totalValue = stockItems.sumOf { it.price * it.stock }
    val criticalCount = stockItems.count { it.threshold > 0 && it.stock <= it.threshold }
    val salesTotal = sales.sumOf { it.total }
    val soldUnits = sales.sumOf { it.qty }
    val topSellers = sales
        .groupBy { it.itemName }
        .map { entry -> Pair(entry.key, entry.value.sumOf { it.qty }) }
        .sortedByDescending { it.second }
        .take(5)

    val countText = stockItems.size.toString()
    val totalValueText = formatMoney(totalValue)
    val criticalText = criticalCount.toString()
    val salesCountText = sales.size.toString()
    val salesTotalText = formatMoney(salesTotal)
    val soldUnitsText = soldUnits.toString()

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
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (view == 0) {
                    Button(onClick = { view = 0 }) { Text(tItemsTab) }
                } else {
                    OutlinedButton(onClick = { view = 0 }) { Text(tItemsTab) }
                }
                if (view == 1) {
                    Button(onClick = { view = 1 }) { Text(tSalesTab) }
                } else {
                    OutlinedButton(onClick = { view = 1 }) { Text(tSalesTab) }
                }
            }
        }

        if (view == 0) {
            // فرم ثبت و ویرایش
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            if (editId == 0L) tNew else tEditTitle,
                            fontWeight = FontWeight.Bold
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ProductImage(path = fImage, size = 96.dp)
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

                            if (editId != 0L) {
                                OutlinedButton(onClick = { resetForm() }) { Text(tCancel) }
                            }
                        }
                    }
                }
            }

            // جمع‌ها
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

            // راهنمای رنگ‌ها
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        InvLegend()
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

            items(shown, key = { it.id }) { inv ->
                InvCard(
                    item = inv,
                    onSell = { sellTarget = inv },
                    onEdit = { startEdit(inv) },
                    onDelete = { deleteTarget = inv }
                )
            }
        } else {
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

            if (topSellers.isNotEmpty()) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(tTop, fontWeight = FontWeight.Bold)
                            topSellers.forEach { pair ->
                                Text(pair.first + ": " + pair.second.toString() + " " + tUnits)
                            }
                        }
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
    }
}

// ---------- قسمت‌های کوچک ----------

@Composable
private fun ProductImage(path: String?, size: Dp) {
    val bmp by produceState<ImageBitmap?>(initialValue = null, key1 = path) {
        value = if (path == null) null else withContext(Dispatchers.IO) { decodeThumb(path) }
    }
    Box(
        modifier = Modifier
            .size(size)
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
private fun InvCard(
    item: InvItem,
    onSell: () -> Unit,
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
    val tEdit = tr("ویرایش", "Edit", "تعديل")
    val tDelete = tr("حذف", "Delete", "حذف")
    val priceText = formatMoney(item.price)
    val stockText = item.stock.toString()

    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ProductImage(path = item.imagePath, size = 72.dp)
            Spacer(Modifier.width(12.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(item.name, fontWeight = FontWeight.Bold)
                if (item.code.isNotBlank()) {
                    Text(tCode + ": " + item.code, style = MaterialTheme.typography.bodySmall)
                }
                Text(tPrice + ": " + priceText)
                Text(
                    tStock + ": " + stockText + "  ·  " + label,
                    color = levelColor,
                    fontWeight = FontWeight.Bold
                )
                if (item.description.isNotBlank()) {
                    Text(
                        item.description,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(onClick = onSell) { Text(tSell) }
                    OutlinedButton(onClick = onEdit) { Text(tEdit) }
                    TextButton(onClick = onDelete) {
                        Text(tDelete, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
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
            ProductImage(path = sale.imagePath, size = 48.dp)
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
