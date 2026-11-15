package com.hesabyar.app.ui
import androidx.compose.runtime.collectAsState
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
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
                LanguageOption(label = "فارسی", tag = "fa", current = currentLang)
                LanguageOption(label = "English", tag = "en", current = currentLang)
                LanguageOption(label = "العربية", tag = "ar", current = currentLang)
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
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { vm.setFiscal(type) }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = currentFiscal == type, onClick = null)
                        Text(stringResource(type.label))
                    }
                }
            }
        }
    }
}

@Composable
private fun LanguageOption(label: String, tag: String, current: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
            }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = current == tag, onClick = null)
        Text(label)
    }
}
