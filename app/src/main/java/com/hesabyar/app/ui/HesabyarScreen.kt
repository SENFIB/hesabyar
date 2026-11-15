package com.hesabyar.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hesabyar.app.R
import com.hesabyar.app.data.Category
import com.hesabyar.app.data.TransactionEntity
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private const val CUSTOM_PREFIX = "custom:"
private const val OTHER_EXPENSE = "other_expense"
private const val OTHER_INCOME = "other_income"

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HesabyarScreen(vm: MainViewModel = viewModel()) {
    val context = LocalContext.current
    val lang = currentLang()
    val regionType = CalendarType.forLanguage(lang)
    val deviceClock by vm.deviceClock.collectAsState()
    val pagerState = rememberPagerState(pageCount = { 4 })
    val scope = rememberCoroutineScope()
    var showLocationSetup by rememberSaveable { mutableStateOf(!WeatherStore.hasLocation(context)) }

    LaunchedEffect(Unit) {
        if (WeatherStore.hasLocation(context)) WeatherStore.refresh(context)
    }

    fun goTo(page: Int) {
        scope.launch { pagerState.animateScrollToPage(page) }
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            ClockHeader(regionType = regionType, deviceClock = deviceClock)
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = pagerState.currentPage == 0,
                    onClick = { goTo(0) },
                    icon = { Icon(Icons.Filled.Create, contentDescription = null) },
                    label = { Text(stringResource(R.string.tab_ledger)) }
                )
                NavigationBarItem(
                    selected = pagerState.currentPage == 1,
                    onClick = { goTo(1) },
                    icon = { Icon(Icons.Filled.Home, contentDescription = null) },
                    label = { Text(stringResource(R.string.tab_store)) }
                )
                NavigationBarItem(
                    selected = pagerState.currentPage == 2,
                    onClick = { goTo(2) },
                    icon = { Icon(Icons.Filled.LocationOn, contentDescription = null) },
                    label = { Text(stringResource(R.string.tab_weather)) }
                )
                NavigationBarItem(
                    selected = pagerState.currentPage == 3,
                    onClick = { goTo(3) },
                    icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                    label = { Text(stringResource(R.string.tab_settings)) }
                )
            }
        }
    ) { padding ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) { page ->
            when (page) {
                0 -> AccountingScreen(vm, regionType)
                1 -> StoreScreen(vm, regionType)
                2 -> WeatherScreen(vm, regionType)
                else -> SettingsScreen(vm, regionType)
            }
        }
    }

    if (showLocationSetup) {
        LocationSetupDialog(onDone = { showLocationSetup = false })
    }
}

// ---------- هدر: تاریخ و ساعت (دو خط) + شهر، دما و وضعیت هوا + دکمه تم ----------

@Composable
fun ClockHeader(regionType: CalendarType, deviceClock: Boolean) {
    val context = LocalContext.current
    val lang = currentLang()
    val is24 by ClockFormat.is24h.collectAsState()

    val now by produceState(initialValue = System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1000)
        }
    }

    val locale = if (deviceClock) deviceLocale() else regionType.locale
    val zone = if (deviceClock) deviceTimeZone() else regionType.timeZone

    val dateText = formatWith(now, "EEEE, yyyy/MM/dd", locale, zone)
    val timeText = formatWith(now, ClockFormat.timePattern(is24), locale, zone)

    val dark = isDarkNow()
    val weather = WeatherStore.state

    val tempText = when (weather) {
        is WeatherState.Ok -> "${weather.info.current.roundToInt()}°"
        WeatherState.Loading -> "…"
        WeatherState.Error -> "--°"
        WeatherState.None -> "--°"
    }
    val condText = when (weather) {
        is WeatherState.Ok -> listOf(weather.place, conditionText(weather.info.code, lang))
            .filter { it.isNotBlank() }
            .joinToString(" · ")
        WeatherState.Loading -> tr("در حال دریافت", "Loading", "جارٍ التحميل")
        WeatherState.Error -> tr("خطا در دریافت", "Fetch error", "خطأ في الجلب")
        WeatherState.None -> tr("موقعیت تنظیم نشده", "Location not set", "الموقع غير محدد")
    }

    Surface(
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        shadowElevation = 4.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = dateText,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = timeText,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
            }

            Column(horizontalAlignment = Alignment.End) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = tempText,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                    TextButton(
                        onClick = {
                            ThemeStore.save(
                                context,
                                ThemeStore.settings.copy(mode = if (dark) 1 else 2)
                            )
                        },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        contentPadding = PaddingValues(horizontal = 6.dp)
                    ) {
                        Text(if (dark) "☀" else "☾", style = MaterialTheme.typography.titleLarge)
                    }
                }
                Text(
                    text = condText,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 220.dp)
                )
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
            AddTransactionCard(vm = vm)
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

// نام نمایشی یک دسته‌بندی (کلید آماده یا نام دلخواه)
@Composable
fun categoryName(key: String): String {
    if (key.startsWith(CUSTOM_PREFIX)) return key.removePrefix(CUSTOM_PREFIX)
    val cat = Category.fromKey(key) ?: return key
    return stringResource(cat.label)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddTransactionCard(vm: MainViewModel) {
    val customIncome by vm.customIncome.collectAsState()
    val customExpense by vm.customExpense.collectAsState()

    var isIncome by remember { mutableStateOf(false) }
    val predefined = Category.entries.filter { it.isIncome == isIncome && it != Category.SALE }
    val customs = if (isIncome) customIncome else customExpense
    val options = predefined.map { it.key } + customs.map { CUSTOM_PREFIX + it }

    var selected by remember(isIncome) { mutableStateOf(predefined.first().key) }
    var customName by remember(isIncome) { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf("") }
    // فقط ارقام انگلیسی ذخیره می‌شوند؛ جداکننده فقط در نمایش اضافه می‌شود
    var amountText by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<Int?>(null) }

    val isOther = selected == OTHER_EXPENSE || selected == OTHER_INCOME

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
                onValueChange = { amountText = digitsOnly(it) },
                label = { Text("${stringResource(R.string.amount)} (${stringResource(R.string.currency)})") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                visualTransformation = ThousandsVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )

            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = it }
            ) {
                OutlinedTextField(
                    value = categoryName(selected),
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.v4_category)) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    modifier = Modifier
                        .menuAnchor()
                        .fillMaxWidth()
                )
                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    options.forEach { key ->
                        DropdownMenuItem(
                            text = { Text(categoryName(key)) },
                            onClick = {
                                selected = key
                                expanded = false
                            }
                        )
                    }
                }
            }

            if (isOther) {
                OutlinedTextField(
                    value = customName,
                    onValueChange = { customName = it },
                    label = { Text(stringResource(R.string.v4_custom_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            error?.let {
                Text(stringResource(it), color = MaterialTheme.colorScheme.error)
            }

            Button(
                onClick = {
                    val amount = amountText.toLongOrNull()
                    when {
                        title.isBlank() -> error = R.string.err_title
                        amount == null || amount <= 0L -> error = R.string.err_amount
                        else -> {
                            val category = if (isOther && customName.isNotBlank()) {
                                vm.saveCategory(customName, isIncome)
                                CUSTOM_PREFIX + customName.trim()
                            } else {
                                selected
                            }
                            vm.addTransaction(title.trim(), amount, isIncome, category)
                            title = ""
                            amountText = ""
                            customName = ""
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
    val is24 by ClockFormat.is24h.collectAsState()
    val categoryText = categoryName(t.category)
    val dateText = formatWith(
        t.dateMillis,
        "yyyy/MM/dd " + ClockFormat.timePattern(is24),
        type.locale,
        type.timeZone
    )

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
                    text = "$categoryText • $dateText",
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
