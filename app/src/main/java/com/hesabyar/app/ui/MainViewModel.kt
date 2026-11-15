package com.hesabyar.app.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.hesabyar.app.data.AppDatabase
import com.hesabyar.app.data.Category
import com.hesabyar.app.data.ProductEntity
import com.hesabyar.app.data.TransactionEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getInstance(application)
    private val txDao = db.transactionDao()
    private val productDao = db.productDao()
    private val prefs = application.getSharedPreferences("hesabyar_prefs", Context.MODE_PRIVATE)

    val transactions: StateFlow<List<TransactionEntity>> = txDao.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val products: StateFlow<List<ProductEntity>> = productDao.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // تقویم سال مالی (null یعنی همان تقویم زبان)
    private val _fiscal = MutableStateFlow(
        prefs.getString(KEY_FISCAL, null)?.let { name ->
            CalendarType.entries.firstOrNull { it.name == name }
        }
    )
    val fiscal: StateFlow<CalendarType?> = _fiscal.asStateFlow()

    // false = خودکار بر اساس زبان، true = تنظیمات گوشی
    private val _deviceClock = MutableStateFlow(prefs.getBoolean(KEY_DEVICE_CLOCK, false))
    val deviceClock: StateFlow<Boolean> = _deviceClock.asStateFlow()

    // اندیس شهر در لیست آب‌وهوا
    private val _weatherCity = MutableStateFlow(prefs.getInt(KEY_CITY, 0))
    val weatherCity: StateFlow<Int> = _weatherCity.asStateFlow()

    // دسته‌بندی‌های دلخواه که کاربر اضافه کرده
    private val _customIncome = MutableStateFlow(loadList(KEY_CUSTOM_INCOME))
    val customIncome: StateFlow<List<String>> = _customIncome.asStateFlow()

    private val _customExpense = MutableStateFlow(loadList(KEY_CUSTOM_EXPENSE))
    val customExpense: StateFlow<List<String>> = _customExpense.asStateFlow()

    private fun loadList(key: String): List<String> =
        (prefs.getStringSet(key, emptySet()) ?: emptySet()).sorted()

    fun setFiscal(type: CalendarType) {
        prefs.edit().putString(KEY_FISCAL, type.name).apply()
        _fiscal.value = type
    }

    fun setDeviceClock(value: Boolean) {
        prefs.edit().putBoolean(KEY_DEVICE_CLOCK, value).apply()
        _deviceClock.value = value
    }

    fun setWeatherCity(index: Int) {
        prefs.edit().putInt(KEY_CITY, index).apply()
        _weatherCity.value = index
    }

    fun saveCategory(name: String, isIncome: Boolean) {
        val clean = name.trim()
        if (clean.isEmpty()) return
        val flow = if (isIncome) _customIncome else _customExpense
        val key = if (isIncome) KEY_CUSTOM_INCOME else KEY_CUSTOM_EXPENSE
        if (clean in flow.value) return
        val updated = (flow.value + clean).sorted()
        flow.value = updated
        prefs.edit().putStringSet(key, updated.toSet()).apply()
    }

    // ---------- حسابداری ----------

    // category: یا کلید دسته‌بندی آماده است، یا "custom:نام"
    fun addTransaction(title: String, amount: Long, isIncome: Boolean, category: String) {
        viewModelScope.launch {
            txDao.insert(
                TransactionEntity(
                    title = title,
                    amount = amount,
                    isIncome = isIncome,
                    category = category,
                    dateMillis = System.currentTimeMillis()
                )
            )
        }
    }

    fun deleteTransaction(item: TransactionEntity) {
        viewModelScope.launch { txDao.delete(item) }
    }

    // ---------- کالاها ----------

    fun saveProduct(id: Long?, name: String, code: String, price: Long, stock: Int) {
        viewModelScope.launch {
            if (id == null) {
                productDao.insert(ProductEntity(name = name, code = code, price = price, stock = stock))
            } else {
                productDao.update(ProductEntity(id = id, name = name, code = code, price = price, stock = stock))
            }
        }
    }

    fun deleteProduct(item: ProductEntity) {
        viewModelScope.launch { productDao.delete(item) }
    }

    // فروش: موجودی کم می‌شود و درآمد به حسابداری اضافه می‌شود
    fun sell(product: ProductEntity, qty: Int, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val ok = db.withTransaction {
                val rows = productDao.decreaseStock(product.id, qty)
                if (rows > 0) {
                    txDao.insert(
                        TransactionEntity(
                            title = "${product.name} × $qty",
                            amount = product.price * qty,
                            isIncome = true,
                            category = Category.SALE.key,
                            dateMillis = System.currentTimeMillis()
                        )
                    )
                    true
                } else {
                    false
                }
            }
            onResult(ok)
        }
    }

    companion object {
        private const val KEY_FISCAL = "fiscal"
        private const val KEY_DEVICE_CLOCK = "device_clock"
        private const val KEY_CITY = "weather_city"
        private const val KEY_CUSTOM_INCOME = "custom_income"
        private const val KEY_CUSTOM_EXPENSE = "custom_expense"
    }
}
