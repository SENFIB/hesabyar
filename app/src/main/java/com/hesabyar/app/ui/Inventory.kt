package com.hesabyar.app.ui

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.hesabyar.app.R
import com.hesabyar.app.data.Category

// ================= مدل و دیتابیس انبار =================

data class InvItem(
    val id: Long,
    val name: String,
    val description: String,
    val code: String,
    val price: Long,
    val stock: Int,
    val threshold: Int
)

class InvDb(ctx: Context) : SQLiteOpenHelper(ctx, "hesabyar_inventory.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE items (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "name TEXT NOT NULL, " +
                "description TEXT NOT NULL, " +
                "code TEXT NOT NULL, " +
                "price INTEGER NOT NULL, " +
                "stock INTEGER NOT NULL, " +
                "threshold INTEGER NOT NULL)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
}

object InvStore {
    private var helper: InvDb? = null

    private fun db(ctx: Context): SQLiteDatabase {
        val h = helper ?: InvDb(ctx.applicationContext).also { helper = it }
        return h.writableDatabase
    }

    fun all(ctx: Context): List<InvItem> {
        val out = ArrayList<InvItem>()
        db(ctx).rawQuery(
            "SELECT id, name, description, code, price, stock, threshold FROM items ORDER BY name",
            null
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    InvItem(
                        id = c.getLong(0),
                        name = c.getString(1) ?: "",
                        description = c.getString(2) ?: "",
                        code = c.getString(3) ?: "",
                        price = c.getLong(4),
                        stock = c.getInt(5),
                        threshold = c.getInt(6)
                    )
                )
            }
        }
        return out
    }

    // id = 0 یعنی کالای جدید
    fun save(ctx: Context, item: InvItem) {
        val cv = ContentValues().apply {
            put("name", item.name)
            put("description", item.description)
            put("code", item.code)
            put("price", item.price)
            put("stock", item.stock)
            put("threshold", item.threshold)
        }
        if (item.id == 0L) {
            db(ctx).insert("items", null, cv)
        } else {
            db(ctx).update("items", cv, "id = ?", arrayOf(item.id.toString()))
        }
    }

    fun setStock(ctx: Context, id: Long, stock: Int) {
        val cv = ContentValues().apply { put("stock", stock) }
        db(ctx).update("items", cv, "id = ?", arrayOf(id.toString()))
    }

    fun delete(ctx: Context, id: Long) {
        db(ctx).delete("items", "id = ?", arrayOf(id.toString()))
    }
}

// ================= رنگ هشدار موجودی =================
// T = حد هشداری که کاربر تعیین کرده.
// بیشتر از ۳T: سبز | تا ۳T: نارنجی | تا ۲T: قرمز | تا T یا کمتر: بنفش

private val INV_OK = Color(0xFF2E7D32)
private val INV_WARN = Color(0xFFEF6C00)
private val INV_LOW = Color(0xFFD32F2F)
private val INV_CRIT = Color(0xFF7B1FA2)

private fun invColor(stock: Int, t: Int): Color = when {
    stock <= t -> INV_CRIT
    stock <= 2 * t -> INV_LOW
    stock <= 3 * t -> INV_WARN
    else -> INV_OK
}

@Composable
private fun invLabel(stock: Int, t: Int): String = when {
    stock <= t -> tr("بحرانی", "Critical", "حرج")
    stock <= 2 * t -> tr("کم", "Low", "منخفض")
    stock <= 3 * t -> tr("رو به کاهش", "Decreasing", "في تناقص")
    else -> tr("کافی", "OK", "كافٍ")
}

// ================= صفحه انبار =================

@Composable
fun InventoryScreen(vm: MainViewModel, regionType: CalendarType) {
    val ctx = LocalContext.current
    var version by remember { mutableStateOf(0) }
    val items = remember(version) { InvStore.all(ctx) }
    val oldProducts by vm.products.collectAsState()

    // یک‌بار، کالاهای قبلی (بخش فروشگاه قدیمی) به این بخش منتقل می‌شوند
    LaunchedEffect(oldProducts) {
        val p = ctx.getSharedPreferences("hesabyar_inventory", Context.MODE_PRIVATE)
        if (!p.getBoolean("migrated", false) && oldProducts.isNotEmpty()) {
            oldProducts.forEach { old ->
                InvStore.save(ctx, InvItem(0L, old.name, "", old.code, old.price, old.stock, 0))
            }
            p.edit().putBoolean("migrated", true).apply()
            version++
        }
    }

    // وضعیت فرم
    var editId by rememberSaveable { mutableStateOf(0L) }
    var name by rememberSaveable { mutableStateOf("") }
    var desc by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    var priceText by rememberSaveable { mutableStateOf("") }
    var stockText by rememberSaveable { mutableStateOf("") }
    var thresholdText by rememberSaveable { mutableStateOf("") }
    var search by rememberSaveable { mutableStateOf("") }
    var errText by remember { mutableStateOf<String?>(null) }
    var sellTarget by remember { mutableStateOf<InvItem?>(null) }

    fun clearForm() {
        editId = 0L
        name = ""
        desc = ""
        code = ""
        priceText = ""
        stockText = ""
        thresholdText = ""
        errText = null
    }

    // متن‌ها باید داخل بدنه Compose ساخته شوند، نه داخل onClick
    val isEditing = editId != 0L
    val currency = stringResource(R.string.currency)
    val titleNew = tr("ثبت کالای جدید", "Add product", "إضافة سلعة")
    val titleEdit = tr("ویرایش کالا", "Edit product", "تعديل السلعة")
    val lblName = tr("نام کالا", "Product name", "اسم السلعة")
    val lblDesc = tr("توضیحات کالا", "Description", "وصف السلعة")
    val lblCode = tr("کد کالا", "Product code", "رمز السلعة")
    val lblPrice = tr("قیمت", "Price", "السعر")
    val lblStock = tr("موجودی", "Stock", "المخزون")
    val lblThreshold = tr("حد هشدار موجودی", "Low-stock alert level", "حد تنبيه المخزون")
    val thresholdHelp = tr(
        "وقتی موجودی به این عدد برسد یا کمتر شود بنفش میشود. تا ۲ برابر این عدد قرمز، تا ۳ برابر نارنجی و بیشتر از آن سبز.",
        "At or below this number the colour is purple. Up to 2x red, up to 3x orange, above that green.",
        "عند هذا الرقم أو أقل يصبح اللون بنفسجياً. حتى ضعفه أحمر، وحتى ثلاثة أضعافه برتقالي، وأعلى من ذلك أخضر."
    )
    val saveNew = tr("ذخیره کالا", "Save product", "حفظ السلعة")
    val saveEdit = tr("ثبت تغییرات", "Save changes", "حفظ التغييرات")
    val cancelEditText = tr("انصراف از ویرایش", "Cancel edit", "إلغاء التعديل")
    val errNameText = tr("نام کالا را وارد کنید", "Enter the product name", "أدخل اسم السلعة")
    val legendText = tr(
        "رنگ‌ها: سبز = کافی، نارنجی = رو به کاهش، قرمز = کم، بنفش = بحرانی",
        "Colours: green = OK, orange = decreasing, red = low, purple = critical",
        "الألوان: أخضر = كافٍ، برتقالي = في تناقص، أحمر = منخفض، بنفسجي = حرج"
    )
    val searchLabel = tr("جست‌وجو با نام یا کد", "Search by name or code", "ابحث بالاسم أو الرمز")
    val totalLabel = tr("ارزش کل موجودی", "Total stock value", "القيمة الإجمالية للمخزون")
    val countLabel = tr("تعداد کالاها", "Products", "عدد السلع")
    val emptyText = tr("هنوز کالایی ثبت نشده است.", "No products yet.", "لا توجد سلع بعد.")
    val noResultText = tr("موردی با این جست‌وجو پیدا نشد.", "No match found.", "لا توجد نتيجة مطابقة.")

    val q = search.trim().lowercase()
    val shown = if (q.isEmpty()) {
        items
    } else {
        items.filter { it.name.lowercase().contains(q) || it.code.lowercase().contains(q) }
    }
    val totalValue = items.sumOf { it.price * it.stock }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // فرم ثبت و ویرایش
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        if (isEditing) titleEdit else titleNew,
                        style = MaterialTheme.typography.titleMedium
                    )

                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(lblName) }
                    )
                    OutlinedTextField(
                        value = desc,
                        onValueChange = { desc = it },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        label = { Text(lblDesc) }
                    )
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(lblCode) }
                    )
                    OutlinedTextField(
                        value = priceText,
                        onValueChange = { priceText = digitsOnly(it) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        visualTransformation = ThousandsVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("$lblPrice ($currency)") }
                    )
                    OutlinedTextField(
                        value = stockText,
                        onValueChange = { stockText = digitsOnly(it) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(lblStock) }
                    )
                    OutlinedTextField(
                        value = thresholdText,
                        onValueChange = { thresholdText = digitsOnly(it) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(lblThreshold) }
                    )
                    Text(thresholdHelp, style = MaterialTheme.typography.bodySmall)

                    errText?.let { Text(it, color = MaterialTheme.colorScheme.error) }

                    Button(
                        onClick = {
                            if (name.isBlank()) {
                                errText = errNameText
                            } else {
                                InvStore.save(
                                    ctx,
                                    InvItem(
                                        id = editId,
                                        name = name.trim(),
                                        description = desc.trim(),
                                        code = code.trim(),
                                        price = priceText.toLongOrNull() ?: 0L,
                                        stock = stockText.toIntOrNull() ?: 0,
                                        threshold = thresholdText.toIntOrNull() ?: 0
                                    )
                                )
                                clearForm()
                                version++
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (isEditing) saveEdit else saveNew)
                    }

                    if (isEditing) {
                        TextButton(onClick = { clearForm() }) {
                            Text(cancelEditText)
                        }
                    }
                }
            }
        }

        // خلاصه و راهنمای رنگ
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        "$totalLabel: ${formatMoney(totalValue)} $currency",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text("$countLabel: ${items.size}", style = MaterialTheme.typography.bodyMedium)
                    Text(legendText, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        // جست‌وجو
        item {
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(searchLabel) }
            )
        }

        if (items.isEmpty()) {
            item { Text(emptyText) }
        } else if (shown.isEmpty()) {
            item { Text(noResultText) }
        }

        // فهرست کالاها
        items(shown, key = { it.id }) { p ->
            InvCard(
                item = p,
                onEdit = {
                    editId = p.id
                    name = p.name
                    desc = p.description
                    code = p.code
                    priceText = p.price.toString()
                    stockText = p.stock.toString()
                    thresholdText = p.threshold.toString()
                    errText = null
                },
                onSell = { sellTarget = p },
                onDelete = {
                    InvStore.delete(ctx, p.id)
                    if (editId == p.id) clearForm()
                    version++
                }
            )
        }
    }

    // پنجره فروش
    val target = sellTarget
    if (target != null) {
        InvSellDialog(
            item = target,
            onDismiss = { sellTarget = null },
            onConfirm = { qty ->
                InvStore.setStock(ctx, target.id, target.stock - qty)
                // فروش به عنوان درآمد در حسابداری ثبت می‌شود
                vm.addTransaction("${target.name} × $qty", target.price * qty, true, Category.SALE.key)
                sellTarget = null
                version++
            }
        )
    }
}

// ================= کارت هر کالا =================

@Composable
private fun InvCard(
    item: InvItem,
    onEdit: () -> Unit,
    onSell: () -> Unit,
    onDelete: () -> Unit
) {
    val color = invColor(item.stock, item.threshold)
    val label = invLabel(item.stock, item.threshold)
    val currency = stringResource(R.string.currency)
    val codeText = tr("کد", "Code", "الرمز")
    val stockLbl = tr("موجودی", "Stock", "المخزون")
    val priceLbl = tr("قیمت", "Price", "السعر")
    val thrLbl = tr("حد هشدار", "Alert level", "حد التنبيه")
    val editText = tr("ویرایش", "Edit", "تعديل")
    val sellText = tr("فروش", "Sell", "بيع")

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Surface(color = color, shape = RoundedCornerShape(8.dp)) {
                    Text(
                        text = label,
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
            }
            if (item.code.isNotBlank()) {
                Text("$codeText: ${item.code}", style = MaterialTheme.typography.bodySmall)
            }
            if (item.description.isNotBlank()) {
                Text(item.description, style = MaterialTheme.typography.bodySmall)
            }
            Text(
                "$stockLbl: ${item.stock}   •   $priceLbl: ${formatMoney(item.price)} $currency",
                style = MaterialTheme.typography.bodyMedium
            )
            Text("$thrLbl: ${item.threshold}", style = MaterialTheme.typography.bodySmall)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onEdit) { Text(editText) }
                Button(onClick = onSell, enabled = item.stock > 0) { Text(sellText) }
                TextButton(onClick = onDelete) { Text(stringResource(R.string.delete)) }
            }
        }
    }
}

// ================= پنجره فروش =================

@Composable
private fun InvSellDialog(
    item: InvItem,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    var qtyText by remember { mutableStateOf("1") }
    var err by remember { mutableStateOf<String?>(null) }

    val titleText = tr("فروش: ", "Sell: ", "بيع: ") + item.name
    val qtyLabel = tr(
        "تعداد فروش (موجودی: ${item.stock})",
        "Quantity (stock: ${item.stock})",
        "الكمية (المخزون: ${item.stock})"
    )
    val errQty = tr("تعداد معتبر وارد کنید", "Enter a valid quantity", "أدخل كمية صحيحة")
    val errStock = tr("موجودی کافی نیست", "Not enough stock", "المخزون غير كافٍ")
    val confirmText = tr("ثبت فروش", "Confirm sale", "تأكيد البيع")
    val cancelText = tr("انصراف", "Cancel", "إلغاء")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(titleText) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = qtyText,
                    onValueChange = { qtyText = digitsOnly(it) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(qtyLabel) }
                )
                err?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val qty = qtyText.toIntOrNull() ?: 0
                when {
                    qty <= 0 -> err = errQty
                    qty > item.stock -> err = errStock
                    else -> onConfirm(qty)
                }
            }) {
                Text(confirmText)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(cancelText)
            }
        }
    )
}
