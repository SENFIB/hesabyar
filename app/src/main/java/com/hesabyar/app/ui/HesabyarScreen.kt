package com.hesabyar.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hesabyar.app.R
import com.hesabyar.app.data.Category
import com.hesabyar.app.data.TransactionEntity
import kotlinx.coroutines.delay

@Composable
fun HesabyarScreen(vm: MainViewModel = viewModel()) {
    var tab by rememberSaveable { mutableStateOf(0) }
    val lang = LocalConfiguration.current.locales[0].language
    val regionType = CalendarType.forLanguage(lang)
    val deviceDate by vm.deviceDate.collectAsState()
    val deviceTime by vm.deviceTime.collectAsState()

    Scaffold(
        topBar = {
            ClockHeader(
                regionType = regionType,
                deviceDate = deviceDate,
                deviceTime = deviceTime,
                onToggleDate = { vm.toggleDeviceDate() },
                onToggleTime = { vm.toggleDeviceTime() }
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    icon = { Icon(Icons.Filled.Create, contentDescription = null) },
                    label = { Text(stringResource(R.string.tab_ledger)) }
                )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    icon = { Icon(Icons.Filled.Home, contentDescription = null) },
                    label = { Text(stringResource(R.string.tab_store)) }
                )
                NavigationBarItem(
                    selected = tab == 2,
                    onClick = { tab = 2 },
                    icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                    label = { Text(stringResource(R.string.tab_settings)) }
                )
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                0 -> LedgerScreen(vm, regionType)
                1 -> StoreScreen(vm, regionType)
                else -> SettingsScreen(vm, regionType)
            }
        }
    }
}

// ---------- هدر ثابت: ساعت و تاریخ ----------

@Composable
fun ClockHeader(
    regionType: CalendarType,
    deviceDate: Boolean,
    deviceTime: Boolean,
    onToggleDate: () -> Unit,
    onToggleTime: () -> Unit
) {
    val now by produceState(initialValue = System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1000)
        }
    }

    val dateLocale = if (deviceDate) deviceLocale() else regionType.locale
    val dateZone = if (deviceDate) deviceTimeZone() else regionType.timeZone
    val timeLocale = if (deviceTime) deviceLocale() else regionType.locale
    val timeZone = if (deviceTime) deviceTimeZone() else regionType.timeZone

    val dateText = formatWith(now, "EEEE, yyyy/MM/dd", dateLocale, dateZone)
    val timeText = formatWith(now, "HH:mm:ss", timeLocale, timeZone)
    val sourceText = stringResource(if (deviceDate) R.string.source_device else regionType.label)

    Surface(
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        shadowElevation = 4.dp
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Text(sourceText, style = MaterialTheme.typography.labelLarge)
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = dateText,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    onClick = onToggleDate,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Text(stringResource(if (deviceDate) R.string.btn_to_region else R.string.btn_to_device))
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = timeText,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    onClick = onToggleTime,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Text(stringResource(if (deviceTime) R.string.btn_to_region else R.string.btn_to_device))
                }
            }
        }
    }
}

// ---------- تب حسابداری ----------

@Composable
fun LedgerScreen(vm: MainViewModel, regionType: CalendarType) {
    val transactions by vm.transactions.collectAsState()

    val totalIncome = transactions.filter { it.isIncome }.sumOf { it.amount }
    val totalExpense = transactions.filter { !it.isIncome }.sumOf { it.amount }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
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
            AddTransactionCard(onAdd = { title, amount, category ->
                vm.addTransaction(title, amount, category)
            })
        }

        item {
            Text(stringResource(R.string.transactions), style = MaterialTheme.typography.titleMedium)
        }

        if (transactions.isEmpty()) {
            item {
                Text(stringResource(R.string.no_transactions))
            }
        }

        items(transactions, key = { it.id }) { t ->
            TransactionRow(t = t, type = regionType, onDelete = { vm.deleteTransaction(t) })
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
            Text(stringResource(R.string.balance), style = MaterialTheme.typography.titleSmall)
            Text(
                text = "${formatMoney(balance)} ${stringResource(R.string.currency)}",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("${stringResource(R.string.income)}: ${formatMoney(income)}")
                Text("${stringResource(R.string.expense)}: ${formatMoney(expense)}")
            }
        }
    }
}

@Composable
fun AddTransactionCard(onAdd: (String, Long, Category) -> Unit) {
    var isIncome by remember { mutableStateOf(false) }
    val options = Category.entries.filter { it.isIncome == isIncome && it != Category.SALE }
    var category by remember(isIncome) { mutableStateOf(options.first()) }
    var title by remember { mutableStateOf("") }
    var amountText by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<Int?>(null) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(stringResource(R.string.add_transaction), style = MaterialTheme.typography.titleMedium)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = !isIncome,
                    onClick = { isIncome = false },
                    label = { Text(stringResource(R.string.expense)) }
                )
                FilterChip(
                    selected = isIncome,
                    onClick = { isIncome = true },
                    label = { Text(stringResource(R.string.income)) }
                )
            }

            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text(stringResource(R.string.description)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = amountText,
                onValueChange = { amountText = toLatinDigits(it) },
                label = { Text("${stringResource(R.string.amount)} (${stringResource(R.string.currency)})") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )

            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                options.forEach { c ->
                    FilterChip(
                        selected = category == c,
                        onClick = { category = c },
                        label = { Text(stringResource(c.label)) }
                    )
                }
            }

            error?.let {
                Text(stringResource(it), color = MaterialTheme.colorScheme.error)
            }

            Button(
                onClick = {
                    val amount = amountText.filter { it.isDigit() }.toLongOrNull()
                    when {
                        title.isBlank() -> error = R.string.err_title
                        amount == null || amount <= 0L -> error = R.string.err_amount
                        else -> {
                            onAdd(title.trim(), amount, category)
                            title = ""
                            amountText = ""
                            error = null
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.add))
            }
        }
    }
}

@Composable
fun TransactionRow(t: TransactionEntity, type: CalendarType, onDelete: () -> Unit) {
    val categoryName = Category.fromKey(t.category)?.let { stringResource(it.label) } ?: t.category
    val dateText = formatWith(t.dateMillis, "yyyy/MM/dd HH:mm", type.locale, type.timeZone)

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
                    text = "$categoryName • $dateText",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Text(
                text = (if (t.isIncome) "+" else "-") + formatMoney(t.amount),
                color = if (t.isIncome) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.Bold
            )
            TextButton(onClick = onDelete) {
                Text(stringResource(R.string.delete))
            }
        }
    }
}
