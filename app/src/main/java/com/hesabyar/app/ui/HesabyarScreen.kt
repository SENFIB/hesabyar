package com.hesabyar.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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

private fun moneyText(v: Long, lang: String): String = langDigits(formatMoney(v), lang)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HesabyarScreen(vm: MainViewModel = viewModel()) {
    val context = LocalContext.current
    val lang = currentLang()
    val regionType = CalendarType.forLanguage(lang)
    val deviceClock by vm.deviceClock.collectAsState()
    val tabsTop = ThemeStore.settings.tabsTop

    // ۶ صفحه به ترتیب: ۰ حسابداری، ۱ آب‌وهوا، ۲ یادداشت‌ها، ۳ مرورگر، ۴ دانلود، ۵ تنظیمات
    val pagerState = rememberPagerState(pageCount = { 6 })
    val scope = rememberCoroutineScope()
    var showManualCity by rememberSaveable { mutableStateOf(false) }

    // تقویم سال مالی: تا وقتی کاربر دستی انتخاب نکرده، با زبان برنامه عوض می‌شود
    LaunchedEffect(lang) {
        if (fiscalAutoOn(context)) vm.setFiscal(CalendarType.forLanguage(lang))
    }

    LaunchedEffect(Unit) {
        if (WeatherStore.hasLocation(context)) WeatherStore.refresh(context)
        WeatherAuto.ensure(context)
    }

    // درخواست GPS هنگام ورود؛ در صورت نبودن موقعیت، ورود دستی شهر
    StartupLocationRequest(onNeedManual = { showManualCity = true })

    val onTab: (Int) -> Unit = { page ->
        scope.launch { pagerState.animateScrollToPage(page) }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        ClockHeader(regionType = regionType, deviceClock = deviceClock)
        if (tabsTop) {
            ThemePartScope(ThemePart.TABS) {
                AppTabBar(selected = pagerState.currentPage, onSelect = onTab)
            }
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            ThemePartScope(ThemePart.TEXT) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize()
                ) { page ->
                    when (page) {
                        0 -> AccountingScreen(vm, regionType)
                        1 -> WeatherScreen(vm, regionType)
                        2 -> RemindersScreen(regionType)
                        3 -> BrowserScreen()
                        4 -> DownloadsScreen()
                        else -> SettingsScreen(vm, regionType)
                    }
                }
            }
        }
        if (!tabsTop) {
            ThemePartScope(ThemePart.TABS) {
                AppTabBar(selected = pagerState.currentPage, onSelect = onTab)
            }
        }
    }

    if (showManualCity && !WeatherStore.hasLocation(context)) {
        ManualCityDialog(
            onDismiss = { showManualCity = false },
            onSaved = { showManualCity = false }
        )
    }
}

// ---------- نوار تب‌ها (شیشه‌ای، بالا یا پایین) ----------
private class BarTab(val label: String, val icon: ImageVector?, val glyph: String?)

@Composable
fun AppTabBar(selected: Int, onSelect: (Int) -> Unit) {
    val base = partColor(ThemePart.TABS, MaterialTheme.colorScheme.surface)
    val on = onColorFor(base)
    val tabs = listOf(
        BarTab(stringResource(R.string.tab_ledger), Icons.Filled.Create, null),
        BarTab(stringResource(R.string.tab_weather), Icons.Filled.LocationOn, null),
        BarTab(tr("یادداشت‌ها", "Notes", "ملاحظات"), null, "🔔"),
        BarTab(tr("مرورگر", "Browser", "المتصفح"), null, "🌐"),
        BarTab(tr("دانلود", "Downloads", "التنزيلات"), null, "⬇"),
        BarTab(stringResource(R.string.tab_settings), Icons.Filled.Settings, null)
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .glass(base, RoundedCornerShape(22.dp))
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        tabs.forEachIndexed { i, tab ->
            val isSel = selected == i
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (isSel) on.copy(alpha = 0.16f) else Color.Transparent)
                    .clickable { onSelect(i) }
                    .padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val icon = tab.icon
                if (icon != null) {
                    Icon(icon, contentDescription = null, tint = on)
                } else {
                    Text(
                        text = tab.glyph ?: "",
                        style = MaterialTheme.typography.titleLarge,
                        color = on
                    )
                }
                Text(
                    text = tab.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = on,
                    fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

// ---------- هدر شیشه‌ای: دکمه تم، تاریخ و ساعت، آب‌وهوا ----------
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
    val dateText = langDigits(formatWith(now, "EEEE, yyyy/MM/dd", locale, zone), lang)
    val timeText = langDigits(formatWith(now, ClockFormat.timePattern(is24), locale, zone), lang)
    val dark = isDarkNow()
    val weather = WeatherStore.state
    val glyphCode = (weather as? WeatherState.Ok)?.info?.code ?: -1

    val tempLabel = when (weather) {
        is WeatherState.Ok -> {
            val base = weather.info.current.toDouble()
            val fahrenheit = WeatherStore.fahrenheit
            val shown = if (fahrenheit) base * 9.0 / 5.0 + 32.0 else base
            langDigits("${shown.roundToInt()}°" + if (fahrenheit) "F" else "", lang)
        }
        WeatherState.Loading -> "…"
        WeatherState.Error -> "--°"
        WeatherState.None -> "--°"
    }
    val placeText = when (weather) {
        is WeatherState.Ok -> listOf(weather.place, conditionWord(weather.info.code, lang))
            .filter { it.isNotBlank() }
            .joinToString(" · ")
        WeatherState.Loading -> tr("در حال دریافت", "Loading", "جارٍ التحميل")
        WeatherState.Error -> tr("خطا در دریافت", "Fetch error", "خطأ في الجلب")
        WeatherState.None -> tr("موقعیت تنظیم نشده", "Location not set", "الموقع غير محدد")
    }

    val headerColor = partColor(ThemePart.HEADER, currentModeColors().primary)
    val onHeader = onColorFor(headerColor)
    ThemePartScope(ThemePart.HEADER) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp)
                .glass(headerColor, RoundedCornerShape(24.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = {
                        ThemeStore.save(
                            context,
                            ThemeStore.settings.copy(mode = if (dark) 1 else 2)
                        )
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = onHeader),
                    contentPadding = PaddingValues(horizontal = 4.dp)
                ) {
                    Text(if (dark) "☀" else "☾", style = MaterialTheme.typography.titleLarge)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = dateText,
                        style = MaterialTheme.typography.titleMedium,
                        color = onHeader,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = timeText,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = onHeader,
                        maxLines = 1
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = tempLabel,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = onHeader
                        )
                        Text(
                            text = placeText,
                            style = MaterialTheme.typography.bodySmall,
                            color = onHeader,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 150.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    WeatherGlyph(code = glyphCode, size = 40.dp)
                }
            }
        }
    }
}

// ---------- تب حسابداری (بخش تراکنش‌ها؛ زیرتب‌ها در AccountingScreen هستند) ----------
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
    val lang = currentLang()
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(stringResource(R.string.balance), style = MaterialTheme.typography.titleSmall)
            Text(
                text = moneyText(balance, lang) + " " + stringResource(R.string.currency),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(stringResource(R.string.income) + ": " + moneyText(income, lang))
                Text(stringResource(R.string.expense) + ": " + moneyText(expense, lang))
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
    val lang = currentLang()
    val categoryText = categoryName(t.category)
    val dateText = langDigits(
        formatWith(
            t.dateMillis,
            "yyyy/MM/dd " + ClockFormat.timePattern(is24),
            type.locale,
            type.timeZone
        ),
        lang
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
                text = (if (t.isIncome) "+" else "-") + moneyText(t.amount, lang),
                color = if (t.isIncome) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.Bold
            )
            TextButton(onClick = onDelete) {
                Text(stringResource(R.string.delete))
            }
        }
    }
}
