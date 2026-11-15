package com.hesabyar.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.hesabyar.app.R
import com.hesabyar.app.data.Category
import com.hesabyar.app.data.ProductEntity

private const val LOW_STOCK = 5

@Composable
fun StoreScreen(vm: MainViewModel, regionType: CalendarType) {
    val fiscalSaved by vm.fiscal.collectAsState()
    val fiscal = fiscalSaved ?: regionType
    var section by rememberSaveable { mutableStateOf(0) }

    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = section) {
            Tab(
                selected = section == 0,
                onClick = { section = 0 },
                text = { Text(stringResource(R.string.products)) }
            )
            Tab(
                selected = section == 1,
                onClick = { section = 1 },
                text = { Text(stringResource(R.string.reports)) }
            )
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when (section) {
                0 -> ProductsSection(vm)
                else -> ReportsSection(vm, regionType, fiscal)
            }
        }
    }
}

// ---------- بخش لیست کالاها ----------

@Composable
fun ProductsSection(vm: MainViewModel) {
    val products by vm.products.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var showDialog by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<ProductEntity?>(null) }
    var selling by remember { mutableStateOf<ProductEntity?>(null) }

    val shown = products.filter {
        query.isBlank() ||
            it.name.contains(query, ignoreCase = true) ||
            it.code.contains(query, ignoreCase = true)
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text(stringResource(R.string.search)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Button(
            onClick = {
                editing = null
                showDialog = true
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.add_product))
        }

        if (shown.isEmpty()) {
            Text(stringResource(R.string.no_products))
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(shown, key = { it.id }) { p ->
                ProductRow(
                    p = p,
                    onSell = { selling = p },
                    onEdit = {
                        editing = p
                        showDialog = true
                    },
                    onDelete = { vm.deleteProduct(p) }
                )
            }
        }
    }

    if (showDialog) {
        ProductDialog(
            initial = editing,
            onDismiss = { showDialog = false },
            onSave = { name, code, price, stock ->
                vm.saveProduct(editing?.id, name, code, price, stock)
                showDialog = false
            }
        )
    }

    selling?.let { product ->
        SellDialog(
            product = product,
            onDismiss = { selling = null },
            onConfirm = { qty ->
                vm.sell(product, qty) { }
                selling = null
            }
        )
    }
}

@Composable
fun ProductRow(
    p: ProductEntity,
    onSell: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val low = p.stock <= LOW_STOCK
    val stockText = "${stringResource(R.string.stock)}: ${p.stock}" +
        if (low) "  ⚠ ${stringResource(R.string.low_stock)}" else ""

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(p.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    if (p.code.isNotBlank()) {
                        Text("#${p.code}", style = MaterialTheme.typography.bodySmall)
                    }
                }
                Text(
                    text = "${formatMoney(p.price)} ${stringResource(R.string.currency)}",
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = stockText,
                color = if (low) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(onClick = onSell, enabled = p.stock > 0) {
                    Text(stringResource(R.string.sell))
                }
                Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = onEdit) {
                    Text(stringResource(R.string.edit))
                }
                TextButton(onClick = onDelete) {
                    Text(stringResource(R.string.delete))
                }
            }
        }
    }
}

@Composable
fun ProductDialog(
    initial: ProductEntity?,
    onDismiss: () -> Unit,
    onSave: (String, String, Long, Int) -> Unit
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var code by remember { mutableStateOf(initial?.code ?: "") }
    var price by remember { mutableStateOf(initial?.price?.toString() ?: "") }
    var stock by remember { mutableStateOf(initial?.stock?.toString() ?: "") }
    var error by remember { mutableStateOf<Int?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(if (initial == null) R.string.product_new else R.string.product_edit))
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.product_name)) },
                    singleLine = true
                )
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = toLatinDigits(it) },
                    label = { Text(stringResource(R.string.product_code)) },
                    singleLine = true
                )
                OutlinedTextField(
                    value = price,
                    onValueChange = { price = toLatinDigits(it) },
                    label = { Text(stringResource(R.string.product_price)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                OutlinedTextField(
                    value = stock,
                    onValueChange = { stock = toLatinDigits(it) },
                    label = { Text(stringResource(R.string.product_stock)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                error?.let {
                    Text(stringResource(it), color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val p = price.filter { it.isDigit() }.toLongOrNull() ?: -1L
                val s = stock.filter { it.isDigit() }.toIntOrNull() ?: -1
                when {
                    name.isBlank() -> error = R.string.err_name
                    p < 0L -> error = R.string.err_amount
                    s < 0 -> error = R.string.err_qty
                    else -> onSave(name.trim(), code.trim(), p, s)
                }
            }) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
fun SellDialog(
    product: ProductEntity,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    var qtyText by remember { mutableStateOf("1") }
    var error by remember { mutableStateOf<Int?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${stringResource(R.string.sell)}: ${product.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${stringResource(R.string.stock)}: ${product.stock}")
                OutlinedTextField(
                    value = qtyText,
                    onValueChange = { qtyText = toLatinDigits(it) },
                    label = { Text(stringResource(R.string.sell_qty)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                error?.let {
                    Text(stringResource(it), color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val q = qtyText.filter { it.isDigit() }.toIntOrNull() ?: 0
                when {
                    q <= 0 -> error = R.string.err_qty
                    q > product.stock -> error = R.string.err_stock
                    else -> onConfirm(q)
                }
            }) {
                Text(stringResource(R.string.sell))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

// ---------- بخش گزارش‌ها ----------

@Composable
fun ReportsSection(vm: MainViewModel, regionType: CalendarType, fiscal: CalendarType) {
    val transactions by vm.transactions.collectAsState()
    var period by remember { mutableStateOf(ReportPeriod.DAILY) }

    val range = remember(period, regionType, fiscal) {
        periodRange(period, System.currentTimeMillis(), regionType, fiscal)
    }

    val inRange = transactions.filter { it.dateMillis >= range.start && it.dateMillis < range.end }
    val income = inRange.filter { it.isIncome }.sumOf { it.amount }
    val expense = inRange.filter { !it.isIncome }.sumOf { it.amount }
    val salesCount = inRange.count { it.category == Category.SALE.key }

    val startText = formatWith(range.start, "yyyy/MM/dd", range.type.locale, range.type.timeZone)
    val endText = formatWith(range.end - 1, "yyyy/MM/dd", range.type.locale, range.type.timeZone)
    val rangeText = stringResource(R.string.range_format, startText, endText)

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ReportPeriod.entries.forEach { p ->
                FilterChip(
                    selected = period == p,
                    onClick = { period = p },
                    label = { Text(stringResource(p.label)) }
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "$rangeText  (${stringResource(range.type.label)})",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text("${stringResource(R.string.report_income)}: ${formatMoney(income)} ${stringResource(R.string.currency)}")
                Text("${stringResource(R.string.report_expense)}: ${formatMoney(expense)} ${stringResource(R.string.currency)}")
                Text(
                    text = "${stringResource(R.string.report_net)}: ${formatMoney(income - expense)} ${stringResource(R.string.currency)}",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text("${stringResource(R.string.report_sales)}: $salesCount")
            }
        }
    }
}
