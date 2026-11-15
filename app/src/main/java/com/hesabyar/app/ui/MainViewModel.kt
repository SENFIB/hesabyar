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

    private val _deviceDate = MutableStateFlow(prefs.getBoolean(KEY_DEVICE_DATE, false))
    val deviceDate: StateFlow<Boolean> = _deviceDate.asStateFlow()

    private val _deviceTime = MutableStateFlow(prefs.getBoolean(KEY_DEVICE_TIME, false))
    val deviceTime: StateFlow<Boolean> = _deviceTime.asStateFlow()

    fun setFiscal(type: CalendarType) {
        prefs.edit().putString(KEY_FISCAL, type.name).apply()
        _fiscal.value = type
    }

    fun toggleDeviceDate() {
        val newValue = !_deviceDate.value
        prefs.edit().putBoolean(KEY_DEVICE_DATE, newValue).apply()
        _deviceDate.value = newValue
    }

    fun toggleDeviceTime() {
        val newValue = !_deviceTime.value
        prefs.edit().putBoolean(KEY_DEVICE_TIME, newValue).apply()
        _deviceTime.value = newValue
    }

    // ---------- حسابداری ----------

    fun addTransaction(title: String, amount: Long, category: Category) {
        viewModelScope.launch {
            txDao.insert(
                TransactionEntity(
                    title = title,
                    amount = amount,
                    isIncome = category.isIncome,
                    category = category.key,
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
        private const val KEY_DEVICE_DATE = "device_date"
        private const val KEY_DEVICE_TIME = "device_time"
    }
}
