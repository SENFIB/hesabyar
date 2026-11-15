package com.hesabyar.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hesabyar.app.data.TransactionEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

val incomeCategories = listOf("حقوق", "فروش", "هدیه", "سایر")
val expenseCategories = listOf("خوراک", "حمل‌ونقل", "قبض", "خرید", "تفریح", "سلامت", "سایر")

@Composable
fun HesabyarScreen(vm: MainViewModel = viewModel()) {
    val transactions by vm.transactions.collectAsState()

    val totalIncome = transactions.filter { it.isIncome }.sumOf { it.amount }
    val totalExpense = transactions.filter { !it.isIncome }.sumOf { it.amount }

    Scaffold { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                SummaryCard(
                    balance = totalIncome - totalExpense,
                    income = totalIncome,
                    expense = totalExpense
                )
            }

            item {
                AddTransactionCard(onAdd = { title, amount, isIncome, category ->
                    vm.add(title, amount, isIncome, category)
                })
            }

            item {
                Text("تراکنش‌ها", style = MaterialTheme.typography.titleMedium)
            }

            if (transactions.isEmpty()) {
                item {
                    Text("هنوز تراکنشی ثبت نشده است.")
                }
            }

            items(transactions, key = { it.id }) { t ->
                TransactionRow(t = t, onDelete = { vm.delete(t) })
            }
        }
    }
}

@Composable
fun SummaryCard(balance: Long, income: Long, expense: Long) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("موجودی", style = MaterialTheme.typography.titleSmall)
            Text(
                text = "${formatMoney(balance)} تومان",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("درآمد: ${formatMoney(income)}")
                Text("هزینه: ${formatMoney(expense)}")
            }
        }
    }
}

@Composable
fun AddTransactionCard(onAdd: (String, Long, Boolean, String) -> Unit) {
    var isIncome by remember { mutableStateOf(false) }
    val categories = if (isIncome) incomeCategories else expenseCategories
    var category by remember(isIncome) { mutableStateOf(categories.first()) }
    var title by remember { mutableStateOf("") }
    var amountText by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("ثبت تراکنش جدید", style = MaterialTheme.typography.titleMedium)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = !isIncome,
                    onClick = { isIncome = false },
                    label = { Text("هزینه") }
                )
                FilterChip(
                    selected = isIncome,
                    onClick = { isIncome = true },
                    label = { Text("درآمد") }
                )
            }

            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("توضیح") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = amountText,
                onValueChange = { amountText = toLatinDigits(it) },
                label = { Text("مبلغ (تومان)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )

            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                categories.forEach { c ->
                    FilterChip(
                        selected = category == c,
                        onClick = { category = c },
                        label = { Text(c) }
                    )
                }
            }

            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
            }

            Button(
                onClick = {
                    val amount = amountText.filter { it.isDigit() }.toLongOrNull()
                    if (title.isBlank()) {
                        error = "لطفاً توضیح را وارد کنید"
                    } else if (amount == null || amount <= 0L) {
                        error = "مبلغ معتبر وارد کنید"
                    } else {
                        onAdd(title.trim(), amount, isIncome, category)
                        title = ""
                        amountText = ""
                        error = null
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("ثبت")
            }
        }
    }
}

@Composable
fun TransactionRow(t: TransactionEntity, onDelete: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(t.title, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "${t.category} • ${formatDate(t.dateMillis)}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Text(
                text = (if (t.isIncome) "+" else "-") + formatMoney(t.amount),
                color = if (t.isIncome) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.Bold
            )
            TextButton(onClick = onDelete) {
                Text("حذف")
            }
        }
    }
}

// ---------- توابع کمکی ----------

fun formatMoney(value: Long): String = String.format(Locale.US, "%,d", value)

fun formatDate(millis: Long): String =
    SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.US).format(Date(millis))

// تبدیل ارقام فارسی/عربی به انگلیسی تا محاسبه درست انجام شود
fun toLatinDigits(input: String): String = input.map { c ->
    when (c) {
        in '۰'..'۹' -> '0' + (c - '۰')
        in '٠'..'٩' -> '0' + (c - '٠')
        else -> c
    }
}.joinToString("")
