package com.hesabyar.app.ui

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
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

// ---------- مدل ----------

// نام پایگاه داده حقوق؛ برای پشتیبان‌گیری بعدی لازم است
const val SALARY_DB_NAME = "hesabyar_salary.db"

data class SalEmployee(
    val id: Long = 0L,
    val name: String,
    val position: String = "",
    val photoPath: String? = null,
    val married: Boolean = false,
    val salary: Long = 0L,
    val insurancePercent: Double = 0.0,
    val supplementary: Long = 0L,
    val hasChildren: Boolean = false,
    val childCount: Int = 0,
    val childAllowance: Long = 0L,
    val createdMillis: Long = 0L
) {
    // بیمه: درصدی از حقوق پایه
    val insuranceAmount: Long get() = Math.round(salary * insurancePercent / 100.0)
    // جمع حق اولاد = تعداد فرزند × مبلغ هر فرزند
    val childTotal: Long get() = if (hasChildren) childCount.toLong() * childAllowance else 0L
    // جمع پرداختی = حقوق + حق اولاد
    val gross: Long get() = salary + childTotal
    // کسورات = بیمه + بیمه تکمیلی
    val deductions: Long get() = insuranceAmount + supplementary
    // خالص پرداختی
    val net: Long get() = gross - deductions
}

// ---------- دیتابیس ----------

private class SalDbHelper(ctx: Context) :
    SQLiteOpenHelper(ctx, SALARY_DB_NAME, null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        createSalaryTable(db)
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        createSalaryTable(db)
    }
}

private fun createSalaryTable(db: SQLiteDatabase) {
    db.execSQL(
        "CREATE TABLE IF NOT EXISTS employees (" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
            "name TEXT NOT NULL, " +
            "position TEXT, " +
            "photo_path TEXT, " +
            "married INTEGER NOT NULL DEFAULT 0, " +
            "salary INTEGER NOT NULL DEFAULT 0, " +
            "insurance_percent REAL NOT NULL DEFAULT 0, " +
            "supplementary INTEGER NOT NULL DEFAULT 0, " +
            "has_children INTEGER NOT NULL DEFAULT 0, " +
            "child_count INTEGER NOT NULL DEFAULT 0, " +
            "child_allowance INTEGER NOT NULL DEFAULT 0, " +
            "created_millis INTEGER NOT NULL DEFAULT 0)"
    )
}

private fun Cursor.sStr(col: String): String? = getString(getColumnIndexOrThrow(col))
private fun Cursor.sNum(col: String): Long = getLong(getColumnIndexOrThrow(col))
private fun Cursor.sReal(col: String): Double = getDouble(getColumnIndexOrThrow(col))

private fun Cursor.toSalEmployee(): SalEmployee = SalEmployee(
    id = sNum("id"),
    name = sStr("name") ?: "",
    position = sStr("position") ?: "",
    photoPath = sStr("photo_path")?.takeIf { it.isNotBlank() },
    married = sNum("married") == 1L,
    salary = sNum("salary"),
    insurancePercent = sReal("insurance_percent"),
    supplementary = sNum("supplementary"),
    hasChildren = sNum("has_children") == 1L,
    childCount = sNum("child_count").toInt(),
    childAllowance = sNum("child_allowance"),
    createdMillis = sNum("created_millis")
)

private object SalStore {
    private var helper: SalDbHelper? = null

    private fun db(ctx: Context): SQLiteDatabase {
        val h = helper ?: SalDbHelper(ctx.applicationContext).also { helper = it }
        return h.writableDatabase
    }

    fun all(ctx: Context): List<SalEmployee> {
        val out = ArrayList<SalEmployee>()
        db(ctx).query("employees", null, null, null, null, null, "name COLLATE NOCASE ASC").use { c ->
            while (c.moveToNext()) out.add(c.toSalEmployee())
        }
        return out
    }

    fun save(ctx: Context, e: SalEmployee) {
        val v = ContentValues().apply {
            put("name", e.name)
            put("position", e.position)
            put("photo_path", e.photoPath)
            put("married", if (e.married) 1 else 0)
            put("salary", e.salary)
            put("insurance_percent", e.insurancePercent)
            put("supplementary", e.supplementary)
            put("has_children", if (e.hasChildren) 1 else 0)
            put("child_count", e.childCount)
            put("child_allowance", e.childAllowance)
            put("created_millis", e.createdMillis)
        }
        if (e.id == 0L) {
            db(ctx).insert("employees", null, v)
        } else {
            db(ctx).update("employees", v, "id = ?", arrayOf(e.id.toString()))
        }
    }

    fun delete(ctx: Context, e: SalEmployee) {
        db(ctx).delete("employees", "id = ?", arrayOf(e.id.toString()))
        e.photoPath?.let { File(it).delete() }
    }
}

// ---------- کمکی‌ها ----------

// عکس کارمند را داخل حافظه خصوصی برنامه کپی می‌کند
private fun salCopyPhoto(ctx: Context, uri: Uri): String? {
    return try {
        val dir = File(ctx.filesDir, "salary_photos")
        dir.mkdirs()
        val out = File(dir, "emp_" + System.currentTimeMillis() + ".jpg")
        val input = ctx.contentResolver.openInputStream(uri) ?: return null
        input.use { src -> out.outputStream().use { dst -> src.copyTo(dst) } }
        out.absolutePath
    } catch (e: Exception) {
        null
    }
}

private fun salDecode(path: String): ImageBitmap? {
    val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
    return BitmapFactory.decodeFile(path, opts)?.asImageBitmap()
}

// فقط رقم، با ارقام فارسی و عربی هم کار می‌کند
private fun salDigits(text: String): String = toLatinDigits(text).filter { it.isDigit() }

// درصد: رقم و یک نقطه اعشار
private fun salPercentText(text: String): String {
    val s = toLatinDigits(text).replace('٫', '.').replace(',', '.')
    val out = StringBuilder()
    var dot = false
    for (c in s) {
        if (c.isDigit()) {
            out.append(c)
        } else if (c == '.' && !dot) {
            dot = true
            out.append(c)
        }
    }
    return out.toString()
}

private fun salPercentValue(text: String): Double = salPercentText(text).toDoubleOrNull() ?: 0.0

private fun salPctFormat(v: Double): String = if (v % 1.0 == 0.0) v.toLong().toString() else v.toString()

// جداکننده هزار‌رقم هنگام تایپ مبلغ؛ مقدار واقعی فقط ارقام است
private class SalAmountTransformation : VisualTransformation {
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

// وضعیت فرم ثبت و ویرایش؛ جدا از صفحه تا خواندن و ذخیره آسان باشد
private class SalFormState {
    var editId by mutableStateOf(0L)
    var created by mutableStateOf(0L)
    var name by mutableStateOf("")
    var position by mutableStateOf("")
    var photo by mutableStateOf<String?>(null)
    var originalPhoto by mutableStateOf<String?>(null)
    // -1 = انتخاب نشده، ۰ = مجرد، ۱ = متاهل
    var marital by mutableStateOf(-1)
    var salary by mutableStateOf("")
    var insurance by mutableStateOf("")
    var supplementary by mutableStateOf("")
    var hasChildren by mutableStateOf(false)
    var childCount by mutableStateOf("")
    var childAllowance by mutableStateOf("")
    var triedSave by mutableStateOf(false)

    fun reset() {
        editId = 0L
        created = 0L
        name = ""
        position = ""
        photo = null
        originalPhoto = null
        marital = -1
        salary = ""
        insurance = ""
        supplementary = ""
        hasChildren = false
        childCount = ""
        childAllowance = ""
        triedSave = false
    }

    fun load(e: SalEmployee) {
        editId = e.id
        created = e.createdMillis
        name = e.name
        position = e.position
        photo = e.photoPath
        originalPhoto = e.photoPath
        marital = if (e.married) 1 else 0
        salary = if (e.salary > 0L) e.salary.toString() else ""
        insurance = if (e.insurancePercent > 0.0) salPctFormat(e.insurancePercent) else ""
        supplementary = if (e.supplementary > 0L) e.supplementary.toString() else ""
        hasChildren = e.hasChildren
        childCount = if (e.childCount > 0) e.childCount.toString() else ""
        childAllowance = if (e.childAllowance > 0L) e.childAllowance.toString() else ""
        triedSave = false
    }

    fun build(): SalEmployee = SalEmployee(
        id = editId,
        name = name.trim(),
        position = position.trim(),
        photoPath = photo,
        married = marital == 1,
        salary = salDigits(salary).toLongOrNull() ?: 0L,
        insurancePercent = salPercentValue(insurance),
        supplementary = salDigits(supplementary).toLongOrNull() ?: 0L,
        hasChildren = hasChildren,
        childCount = salDigits(childCount).toIntOrNull() ?: 0,
        childAllowance = salDigits(childAllowance).toLongOrNull() ?: 0L,
        createdMillis = if (created > 0L) created else System.currentTimeMillis()
    )
}

// ---------- صفحه اصلی تب حقوق و دستمزد ----------

@Composable
fun SalaryScreen(vm: MainViewModel, regionType: CalendarType) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val form = remember { SalFormState() }

    var staffList by remember { mutableStateOf(SalStore.all(ctx)) }
    // ۰ ثبت کارمند، ۱ گزارش و جست‌وجو
    var view by remember { mutableStateOf(0) }
    var query by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<SalEmployee?>(null) }

    // همه متن‌ها فقط اینجا ساخته می‌شوند، نه داخل callbackها
    val tTabForm = tr("ثبت کارمند", "Add employee", "تسجيل موظف")
    val tTabReport = tr("گزارش و جست‌وجو", "Report & search", "التقرير والبحث")
    val tSearch = tr("جست‌وجوی نام یا سمت", "Search name or position", "ابحث بالاسم أو المسمى")
    val tHint = tr(
        "برای دیدن اطلاعات یک کارمند، نام یا سمت او را جست‌وجو کنید. لیست کامل نمایش داده نمی‌شود.",
        "Search an employee's name or position to see their details. The full list is not shown.",
        "ابحث عن اسم الموظف أو مسماه لعرض بياناته. لا تُعرض القائمة الكاملة."
    )
    val tNoMatch = tr("کارمندی با این مشخصات پیدا نشد", "No employee matches", "لا يوجد موظف مطابق")
    val tEmpty = tr("هنوز کارمندی ثبت نشده", "No employees yet", "لا يوجد موظفون بعد")
    val tStatCount = tr("تعداد کارمندان", "Employees", "عدد الموظفين")
    val tStatGross = tr("جمع حقوق ماهانه", "Monthly payroll", "إجمالي الرواتب")
    val tStatNet = tr("جمع خالص پرداخت", "Net payroll", "صافي الرواتب")
    val tDeleteAsk = tr("این کارمند حذف شود؟", "Delete this employee?", "هل تريد حذف هذا الموظف؟")
    val tDelete = tr("حذف", "Delete", "حذف")
    val tCancel = tr("انصراف", "Cancel", "إلغاء")
    val tSaved = tr("کارمند ثبت شد ✓", "Employee saved ✓", "تم حفظ الموظف ✓")
    val tPayDone = tr("پرداخت به‌عنوان هزینه ثبت شد ✓", "Payment recorded as expense ✓", "تم تسجيل الدفع كمصروف ✓")

    val q = query.trim().lowercase()
    val matches = if (q.isEmpty()) {
        emptyList<SalEmployee>()
    } else {
        staffList.filter { e -> e.name.lowercase().contains(q) || e.position.lowercase().contains(q) }
    }
    val countText = staffList.size.toString()
    val grossText = formatMoney(staffList.sumOf { it.gross })
    val netText = formatMoney(staffList.sumOf { it.net })

    // ذخیره: پس از ذخیره، فرم خالی می‌شود تا کارمند بعدی ثبت شود
    val onSaved: (SalEmployee) -> Unit = { emp ->
        val orig = form.originalPhoto
        SalStore.save(ctx, emp)
        if (orig != null && orig != emp.photoPath) File(orig).delete()
        form.reset()
        staffList = SalStore.all(ctx)
        Toast.makeText(ctx, tSaved, Toast.LENGTH_SHORT).show()
    }

    // ثبت پرداخت به‌عنوان هزینه؛ فقط وقتی کاربر دکمه را بزند
    val onPay: (String, Long) -> Unit = { title, amount ->
        vm.addTransaction(title, amount, false, "other_expense")
        Toast.makeText(ctx, tPayDone, Toast.LENGTH_SHORT).show()
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
                    SalStore.delete(ctx, delTarget)
                    if (form.editId == delTarget.id) form.reset()
                    deleteTarget = null
                    staffList = SalStore.all(ctx)
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
        // دو تب داخلی با عرض مساوی
        item {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Max)
            ) {
                SalTabButton(
                    label = tTabForm,
                    selected = view == 0,
                    onClick = { view = 0 },
                    modifier = Modifier.weight(1f)
                )
                SalTabButton(
                    label = tTabReport,
                    selected = view == 1,
                    onClick = { view = 1 },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        if (view == 0) {
            item {
                SalRegisterForm(form = form, onSave = onSaved)
            }
        } else {
            // جمع‌ها: فقط عدد و مبلغ کل، بدون لیست کارمندان
            item {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Max)
                ) {
                    SalStatBox(label = tStatCount, value = countText, modifier = Modifier.weight(1f))
                    SalStatBox(label = tStatGross, value = grossText, modifier = Modifier.weight(1f))
                    SalStatBox(label = tStatNet, value = netText, modifier = Modifier.weight(1f))
                }
            }
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(tSearch) },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (staffList.isEmpty()) {
                item { Text(tEmpty) }
            } else if (q.isEmpty()) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            tHint,
                            modifier = Modifier.padding(14.dp),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            } else if (matches.isEmpty()) {
                item { Text(tNoMatch) }
            }
            items(matches, key = { it.id }) { emp ->
                SalEmployeeCard(
                    emp = emp,
                    regionType = regionType,
                    onEdit = {
                        form.load(emp)
                        view = 0
                        scope.launch { listState.animateScrollToItem(0) }
                    },
                    onPay = onPay,
                    onDelete = { deleteTarget = emp }
                )
            }
        }
    }
}

// ---------- فرم ثبت و ویرایش ----------

@Composable
private fun SalRegisterForm(
    form: SalFormState,
    onSave: (SalEmployee) -> Unit
) {
    val ctx = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val path = salCopyPhoto(ctx, uri)
            if (path != null) form.photo = path
        }
    }

    val tNewTitle = tr("ثبت کارمند جدید", "New employee", "موظف جديد")
    val tEditTitle = tr("ویرایش کارمند", "Edit employee", "تعديل الموظف")
    val tSave = tr("ذخیره کارمند", "Save employee", "حفظ الموظف")
    val tUpdate = tr("ثبت تغییرات", "Save changes", "حفظ التغييرات")
    val tCancel = tr("انصراف", "Cancel", "إلغاء")
    val tPickPhoto = tr("انتخاب عکس", "Choose photo", "اختيار صورة")
    val tRemovePhoto = tr("حذف عکس", "Remove photo", "حذف الصورة")
    val tName = tr("نام و نام خانوادگی", "Full name", "الاسم الكامل")
    val tNameRequired = tr("نام کارمند را وارد کنید", "Enter the employee's name", "أدخل اسم الموظف")
    val tPosition = tr("سمت (اختیاری)", "Position (optional)", "المسمى الوظيفي (اختياري)")
    val tMaritalTitle = tr("وضعیت تأهل", "Marital status", "الحالة الاجتماعية")
    val tSingle = tr("مجرد", "Single", "أعزب")
    val tMarried = tr("متاهل", "Married", "متزوج")
    val tMaritalRequired = tr("یکی از دو گزینه را انتخاب کنید", "Choose one option", "اختر أحد الخيارين")
    val tSalary = tr("حقوق ماهانه", "Monthly salary", "الراتب الشهري")
    val tSalaryRequired = tr("مبلغ حقوق را وارد کنید", "Enter the monthly salary", "أدخل الراتب الشهري")
    val tInsurance = tr("بیمه (درصد از حقوق)", "Insurance (% of salary)", "التأمين (% من الراتب)")
    val tInsHint = tr("مبلغ بیمه", "Insurance amount", "مبلغ التأمين")
    val tSupp = tr("بیمه تکمیلی (ماهانه)", "Supplementary insurance (monthly)", "تأمين تكميلي (شهري)")
    val tChildTitle = tr("حق اولاد", "Child allowance", "بدل الأطفال")
    val tNoChild = tr("ندارد", "No", "لا")
    val tYesChild = tr("دارد", "Yes", "نعم")
    val tChildCount = tr("تعداد فرزندان", "Number of children", "عدد الأطفال")
    val tChildEach = tr("حق اولاد هر فرزند (ماهانه)", "Allowance per child (monthly)", "بدل كل طفل (شهري)")
    val tChildTotal = tr("جمع حق اولاد", "Total child allowance", "إجمالي بدل الأطفال")
    val tSummary = tr("خلاصه ماهانه", "Monthly summary", "الملخص الشهري")
    val tBase = tr("حقوق پایه", "Base salary", "الراتب الأساسي")
    val tGross = tr("جمع پرداختی", "Gross", "الإجمالي")
    val tInsDeduct = tr("کسر بیمه", "Insurance deduction", "خصم التأمين")
    val tSuppDeduct = tr("کسر بیمه تکمیلی", "Supplementary deduction", "خصم التأمين التكميلي")
    val tNet = tr("خالص پرداختی", "Net pay", "صافي الدفع")
    val tRemoveTxt = tr("حذف عکس", "Remove photo", "حذف الصورة")

    val preview = form.build()
    val titleText = if (form.editId == 0L) tNewTitle else tEditTitle
    val saveText = if (form.editId == 0L) tSave else tUpdate
    val nameBad = form.triedSave && preview.name.isBlank()
    val maritalBad = form.triedSave && form.marital < 0
    val salaryBad = form.triedSave && preview.salary <= 0L

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(titleText, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

            // عکس کارمند
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                SalPhoto(path = form.photo, modifier = Modifier.size(96.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = { picker.launch("image/*") }) { Text(tPickPhoto) }
                    if (form.photo != null) {
                        TextButton(onClick = { form.photo = null }) { Text(tRemoveTxt) }
                    }
                }
            }

            OutlinedTextField(
                value = form.name,
                onValueChange = { form.name = it },
                label = { Text(tName) },
                singleLine = true,
                isError = nameBad,
                modifier = Modifier.fillMaxWidth()
            )
            if (nameBad) {
                Text(tNameRequired, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            OutlinedTextField(
                value = form.position,
                onValueChange = { form.position = it },
                label = { Text(tPosition) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            // وضعیت تأهل: یکی از دو گزینه
            SalSectionTitle(tMaritalTitle)
            SalChoice(
                options = listOf(tSingle, tMarried),
                selected = form.marital,
                onSelect = { form.marital = it }
            )
            if (maritalBad) {
                Text(tMaritalRequired, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            // حقوق و بیمه
            SalSectionTitle(tSalary)
            OutlinedTextField(
                value = form.salary,
                onValueChange = { form.salary = salDigits(it) },
                label = { Text(tSalary) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                visualTransformation = SalAmountTransformation(),
                isError = salaryBad,
                modifier = Modifier.fillMaxWidth()
            )
            if (salaryBad) {
                Text(tSalaryRequired, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            OutlinedTextField(
                value = form.insurance,
                onValueChange = { form.insurance = salPercentText(it) },
                label = { Text(tInsurance) },
                supportingText = { Text(tInsHint + ": " + formatMoney(preview.insuranceAmount)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = form.supplementary,
                onValueChange = { form.supplementary = salDigits(it) },
                label = { Text(tSupp) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                visualTransformation = SalAmountTransformation(),
                modifier = Modifier.fillMaxWidth()
            )

            // حق اولاد: اگر «دارد» باشد، کادرهای زیرش نمایش داده می‌شوند
            SalSectionTitle(tChildTitle)
            SalChoice(
                options = listOf(tNoChild, tYesChild),
                selected = if (form.hasChildren) 1 else 0,
                onSelect = { form.hasChildren = it == 1 }
            )
            if (form.hasChildren) {
                OutlinedTextField(
                    value = form.childCount,
                    onValueChange = { form.childCount = salDigits(it) },
                    label = { Text(tChildCount) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = form.childAllowance,
                    onValueChange = { form.childAllowance = salDigits(it) },
                    label = { Text(tChildEach) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    visualTransformation = SalAmountTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    tChildTotal + ": " + formatMoney(preview.childTotal),
                    fontWeight = FontWeight.Bold
                )
            }

            // خلاصه ماهانه به‌صورت زنده
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(tSummary, fontWeight = FontWeight.Bold)
                    SalMoneyLine(tBase, formatMoney(preview.salary))
                    if (preview.hasChildren) {
                        SalMoneyLine(tChildTotal, formatMoney(preview.childTotal))
                    }
                    SalMoneyLine(tGross, formatMoney(preview.gross), bold = true)
                    SalMoneyLine(tInsDeduct, formatMoney(preview.insuranceAmount))
                    if (preview.supplementary > 0L) {
                        SalMoneyLine(tSuppDeduct, formatMoney(preview.supplementary))
                    }
                    SalMoneyLine(tNet, formatMoney(preview.net), bold = true)
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    form.triedSave = true
                    val emp = form.build()
                    val ok = emp.name.isNotBlank() && form.marital >= 0 && emp.salary > 0L
                    if (ok) onSave(emp)
                }) { Text(saveText) }
                OutlinedButton(onClick = { form.reset() }) { Text(tCancel) }
            }
        }
    }
}

// ---------- کارت کارمند در گزارش ----------

@Composable
private fun SalEmployeeCard(
    emp: SalEmployee,
    regionType: CalendarType,
    onEdit: () -> Unit,
    onPay: (String, Long) -> Unit,
    onDelete: () -> Unit
) {
    val tMarried = tr("متاهل", "Married", "متزوج")
    val tSingle = tr("مجرد", "Single", "أعزب")
    val tSalary = tr("حقوق ماهانه", "Monthly salary", "الراتب الشهري")
    val tIns = tr("بیمه", "Insurance", "التأمين")
    val tSupp = tr("بیمه تکمیلی", "Supplementary", "تأمين تكميلي")
    val tChild = tr("حق اولاد", "Child allowance", "بدل الأطفال")
    val tNet = tr("خالص پرداختی", "Net pay", "صافي الدفع")
    val tRegistered = tr("تاریخ ثبت", "Added", "تاريخ الإضافة")
    val tEdit = tr("ویرایش", "Edit", "تعديل")
    val tPay = tr("ثبت پرداخت", "Record payment", "تسجيل الدفع")
    val tDel = tr("حذف", "Delete", "حذف")
    val tPayPrefix = tr("حقوق ", "Salary ", "راتب ")

    val maritalText = if (emp.married) tMarried else tSingle
    val payTitle = tPayPrefix + emp.name
    val pctText = salPctFormat(emp.insurancePercent)
    val dateText = if (emp.createdMillis > 0L) {
        formatWith(emp.createdMillis, "yyyy/MM/dd", regionType.locale, regionType.timeZone)
    } else {
        ""
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SalPhoto(path = emp.photoPath, modifier = Modifier.size(64.dp))
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        emp.name,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (emp.position.isNotBlank()) {
                        Text(
                            emp.position,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text(maritalText, style = MaterialTheme.typography.labelSmall)
                }
            }

            // جزئیات مالی
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                SalMoneyLine(tSalary, formatMoney(emp.salary))
                SalMoneyLine(tIns + " (" + pctText + "%)", formatMoney(emp.insuranceAmount))
                if (emp.supplementary > 0L) {
                    SalMoneyLine(tSupp, formatMoney(emp.supplementary))
                }
                if (emp.hasChildren) {
                    SalMoneyLine(
                        tChild + " (" + emp.childCount.toString() + " × " + formatMoney(emp.childAllowance) + ")",
                        formatMoney(emp.childTotal)
                    )
                }
                SalMoneyLine(tNet, formatMoney(emp.net), bold = true)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = onEdit,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 4.dp)
                ) { Text(tEdit, maxLines = 1, style = MaterialTheme.typography.labelLarge) }
                Button(
                    onClick = { onPay(payTitle, emp.net) },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 4.dp)
                ) { Text(tPay, maxLines = 1, style = MaterialTheme.typography.labelLarge) }
                TextButton(onClick = onDelete) {
                    Text(tDel, color = MaterialTheme.colorScheme.error, maxLines = 1)
                }
            }

            if (dateText.isNotEmpty()) {
                Text(
                    tRegistered + ": " + dateText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ---------- قسمت‌های کوچک ----------

@Composable
private fun SalTabButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val pad = PaddingValues(horizontal = 4.dp, vertical = 6.dp)
    if (selected) {
        Button(onClick = onClick, modifier = modifier.fillMaxHeight(), contentPadding = pad) {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier.fillMaxHeight(), contentPadding = pad) {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun SalStatBox(label: String, value: String, modifier: Modifier) {
    Card(modifier = modifier.fillMaxHeight()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                value,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun SalSectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary
    )
}

// انتخاب یکی از چند گزینه؛ گزینه انتخاب‌شده پررنگ است
@Composable
private fun SalChoice(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Max),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        options.forEachIndexed { i, label ->
            if (i == selected) {
                Button(onClick = { onSelect(i) }, modifier = Modifier.weight(1f)) {
                    Text(label, maxLines = 1)
                }
            } else {
                OutlinedButton(onClick = { onSelect(i) }, modifier = Modifier.weight(1f)) {
                    Text(label, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun SalMoneyLine(label: String, value: String, bold: Boolean = false) {
    val weight = if (bold) FontWeight.Bold else FontWeight.Normal
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontWeight = weight, style = MaterialTheme.typography.bodyMedium)
        Text(value, fontWeight = weight, style = MaterialTheme.typography.bodyMedium)
    }
}

// عکس دایره‌ای کارمند؛ اگر عکس نباشد آیکون شخص نشان داده می‌شود
@Composable
private fun SalPhoto(path: String?, modifier: Modifier) {
    val bmp by produceState<ImageBitmap?>(initialValue = null, key1 = path) {
        value = if (path == null) null else withContext(Dispatchers.IO) { salDecode(path) }
    }
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        val shown = bmp
        if (shown != null) {
            Image(
                bitmap = shown,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(
                Icons.Filled.Person,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxSize(0.5f)
            )
        }
    }
}
