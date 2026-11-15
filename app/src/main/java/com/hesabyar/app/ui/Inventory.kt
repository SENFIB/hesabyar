package com.hesabyar.app.ui

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
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
import kotlinx.coroutines.launch
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

private class InvDbHelper(ctx: Context) : SQLiteOpenHelper(ctx, "hesabyar_inventory.db", null, 2) {
    override fun onCreate(db: SQLiteDatabase) = createTables(db)
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = createTables(db)
    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        createTables(db)
    }
}

private fun createTables(db: SQLiteDatabase) {
    db.execSQL(
        """CREATE TABLE IF NOT EXISTS items (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            name TEXT NOT NULL,
            description TEXT,
            code TEXT,
            price INTEGER NOT NULL DEFAULT 0,
            stock INTEGER NOT NULL DEFAULT 0,
            threshold INTEGER NOT NULL DEFAULT 0,
            image_path TEXT
        )"""
    )
    // Adds the photo column to databases created by earlier versions. If it already exists, this does nothing.
    try {
        db.execSQL("ALTER TABLE items ADD COLUMN image_path TEXT")
    } catch (e: Exception) {
    }
    db.execSQL(
        """CREATE TABLE IF NOT EXISTS sales (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            item_id INTEGER NOT NULL,
            item_name TEXT NOT NULL,
            image_path TEXT,
            qty INTEGER NOT NULL,
            unit_price INTEGER NOT NULL,
            total INTEGER NOT NULL,
            date_millis INTEGER NOT NULL
        )"""
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

    // Reduces stock and records the sale. Returns false if stock is not enough.
    fun sell(ctx: Context, item: InvItem, qty: Int, unitPrice: Long, dateMillis: Long): Boolean {
        val d = db(ctx)
        d.beginTransaction()
        try {
            val current = d.query(
                "items", arrayOf("stock"), "id = ?", arrayOf(item.id.toString()), null, null, null
            ).use { c -> if (c.moveToFirst()) c.getInt(0) else -1 }

            if (qty <= 0 || current < qty) return false

            d.execSQL("UPDATE items SET stock = stock - ? WHERE id = ?", arrayOf<Any>(qty, item.id))
            d.insertOrThrow("sales", null, ContentValues().apply {
                put("item_id", item.id)
                put("item_name", item.name)
                put("image_path", item.imagePath)
                put("qty", qty)
                put("unit_price", unitPrice)
                put("total", unitPrice * qty)
                put("date_millis", dateMillis)
            })
            d.setTransactionSuccessful()
            return true
        } finally {
            d.endTransaction()
        }
    }
}

// ---------- Helpers ----------

private fun copyImageToApp(ctx: Context, uri: Uri): String? {
    return try {
        val dir = File(ctx.filesDir, "inv_images").apply { mkdirs() }
        val out = File(dir, "item_${System.currentTimeMillis()}.jpg")
        ctx.contentResolver.openInputStream(uri)?.use { input ->
            out.outputStream().use { input.copyTo(it) }
        } ?: return null
        out.absolutePath
    } catch (e: Exception) {
        null
    }
}

private fun decodeThumb(path: String): ImageBitmap? {
    val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
    return BitmapFactory.decodeFile(path, opts)?.asImageBitmap()
}

// Stock levels, T = alert level set by the user
// 0 = green (more than 3T), 1 = orange (more than 2T up to 3T),
// 2 = red (more than T up to 2T), 3 = purple (T or less)
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

    var items by remember { mutableStateOf(InvStore.all(ctx)) }
    var sales by remember { mutableStateOf(InvStore.salesAll(ctx)) }
    var view by remember { mutableStateOf(0) } // 0 = items, 1 = sales report
    var query by remember { mutableStateOf("") }
    var sellTarget by remember { mutableStateOf<InvItem?>(null) }
    var deleteTarget by remember { mutableStateOf<InvItem?>(null) }

    // Form state
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

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            copyImageToApp(ctx, uri)?.let { fImage = it }
        }
    }

    // One-time migration of old products into the inventory
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
            items = InvStore.all(ctx)
        }
    }

    fun reload() {
        items = InvStore.all(ctx)
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

    // All texts are built here, not inside callbacks
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
    val shown = if (q.isEmpty()) items else items.filter {
        it.name.lowercase().contains(q) || it.code.lowercase().contains(q)
    }
    val totalValue = items.sumOf { it.price * it.stock }
    val criticalCount = items.count { it.threshold > 0 && it.stock <= it.threshold }
    val salesTotal = sales.sumOf { it.total }
    val soldUnits = sales.sumOf { it.qty }
    val topSellers = sales
        .groupBy { it.itemName }
        .map { (name, list) -> name to list.sumOf { it.qty } }
        .sortedByDescending { it.second }
        .take(5)

    // Sell dialog
    sellTarget?.let { target ->
        SellDialog(
            item = target,
            onDismiss = { sellTarget = null },
            onConfirm = { qty, unitPrice ->
                val now = System.currentTimeMillis()
                if (InvStore.sell(ctx, target, qty, unitPrice, now)) {
                    vm.addTransaction("${target.name} × $qty", unitPrice * qty, true, "sale")
                    reload()
                }
                sellTarget = null
            }
        )
    }

    // Delete dialog
    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(tDeleteAsk) },
            text = { Text(target.name) },
            confirmButton = {
                Button(onClick = {
                    InvStore.delete(ctx, target)
                    if (editId == target.id) resetForm()
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
            // Add / edit form
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
                                OutlinedButton(onClick = {
                                    picker.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                    )
                                }) { Text(tPickPhoto) }
                                if (fImage != null) {
                                    TextButton(onClick = { fImage = null }) { Text(tRemovePhoto) }
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
                                    // Remove the old photo if it was replaced
                                    if (orig != null && orig != fImage) File(orig).delete()
                                    resetForm()
                                    reload()
                                }
                            }) { Text(if (editId == 0L) tSave else tUpdate) }

                            if (editId != 0L) {
                                OutlinedButton(onClick = { resetForm() }) { Text(tCancel) }
                            }
                        }
                    }
                }
            }

            // Totals
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("$tCount: ${items.size}")
                        Text("$tTotalValue: ${formatMoney(totalValue)}", fontWeight = FontWeight.Bold)
                        Text("$tCritical: $criticalCount")
                    }
                }
            }

            // Legend
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        InvLegend()
                    }
                }
            }

            // Search
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(tSearch) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            if (items.isEmpty()) {
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
            // Sales report
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("$tSalesCount: ${sales.size}")
                        Text("$tSalesTotal: ${formatMoney(salesTotal)}", fontWeight = FontWeight.Bold)
                        Text("$tSoldUnits: $soldUnits")
                    }
                }
            }

            if (topSellers.isNotEmpty()) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = 
