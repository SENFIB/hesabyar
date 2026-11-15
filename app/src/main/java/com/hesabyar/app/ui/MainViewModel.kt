package com.hesabyar.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hesabyar.app.data.AppDatabase
import com.hesabyar.app.data.TransactionEntity
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val dao = AppDatabase.getInstance(application).transactionDao()

    val transactions: StateFlow<List<TransactionEntity>> = dao.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun add(title: String, amount: Long, isIncome: Boolean, category: String) {
        viewModelScope.launch {
            dao.insert(
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

    fun delete(item: TransactionEntity) {
        viewModelScope.launch {
            dao.delete(item)
        }
    }
}
