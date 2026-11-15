package com.hesabyar.app.ui

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import com.hesabyar.app.R

@Composable
fun SettingsScreen(vm: MainViewModel, regionType: CalendarType) {
    val fiscalSaved by vm.fiscal.collectAsState()
    val deviceClock by vm.deviceClock.collectAsState()
    val currentFiscal = fiscalSaved ?: regionType
    val currentLang = LocalConfiguration.current.locales[0].language

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // زبان
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(stringResource(R.string.language), style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                OptionRow(label = "فارسی", selected = currentLang == "fa") { setLanguage("fa") }
                OptionRow(label = "English", selected = currentLang == "en") { setLanguage("en") }
                OptionRow(label = "العربية", selected = currentLang == "ar") { setLanguage("ar") }
            }
        }

        // زمان و تاریخ هدر
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(stringResource(R.string.v4_time_source), style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                OptionRow(
                    label = stringResource(R.string.v4_time_auto),
                    selected = !deviceClock
                ) { vm.setDeviceClock(false) }
                OptionRow(
                    label = stringResource(R.string.v4_time_device),
                    selected = deviceClock
                ) { vm.setDeviceClock(true) }
            }
        }

        // تقویم سال مالی
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(stringResource(R.string.fiscal_calendar), style = MaterialTheme.typography.titleMedium)
                Text(
                    text = stringResource(R.string.fiscal_hint),
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.height(8.dp))
                CalendarType.entries.forEach { type ->
                    OptionRow(
                        label = stringResource(type.label),
                        selected = currentFiscal == type
                    ) { vm.setFiscal(type) }
                }
            }
        }
    }
}

private fun setLanguage(tag: String) {
    AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
}

@Composable
private fun OptionRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label)
    }
}
