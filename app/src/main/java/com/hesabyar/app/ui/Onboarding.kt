package com.hesabyar.app.ui

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat

private const val GATE_PREFS = "hesabyar_prefs"
private const val GATE_KEY = "lang_chosen"

fun languageChosen(ctx: Context): Boolean =
    ctx.getSharedPreferences(GATE_PREFS, Context.MODE_PRIVATE).getBoolean(GATE_KEY, false)

// اولین صفحه برنامه: انتخاب زبان. همه متن‌های بعدی با همین زبان نمایش داده می‌شوند.
@Composable
fun LanguageGate(onChosen: () -> Unit) {
    val ctx = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("حسابیار", style = MaterialTheme.typography.headlineLarge)
        Spacer(modifier = Modifier.height(16.dp))
        Text("زبان خود را انتخاب کنید", style = MaterialTheme.typography.titleMedium)
        Text("Choose your language", style = MaterialTheme.typography.titleMedium)
        Text("اختر لغتك", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(24.dp))

        listOf("fa" to "فارسی", "en" to "English", "ar" to "العربية").forEach { (tag, name) ->
            Button(
                onClick = {
                    ctx.getSharedPreferences(GATE_PREFS, Context.MODE_PRIVATE)
                        .edit()
                        .putBoolean(GATE_KEY, true)
                        .apply()
                    AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
                    onChosen()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(name)
            }
            Spacer(modifier = Modifier.height(10.dp))
        }
    }
}
