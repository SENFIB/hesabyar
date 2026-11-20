package com.hesabyar.app.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.hesabyar.app.MainActivity

private const val WEATHER_PREFS = "hesabyar_weather"
private const val LOC_ASKED_KEY = "loc_asked"

private fun myLocationLabel(lang: String): String = when (lang) {
    "fa" -> "موقعیت من"
    "ar" -> "موقعي"
    else -> "My location"
}

/**
 * یک بار داخل HesabyarScreen صدا زده می‌شود.
 * onNeedManual: وقتی موقعیت گرفته نشد یا اجازه داده نشد، باید پنجرهٔ ورود دستی شهر باز شود.
 */
@Composable
fun StartupLocationRequest(onNeedManual: () -> Unit) {
    val ctx = LocalContext.current
    val lang = currentLang()
    val label = myLocationLabel(lang)
    val manualNow by rememberUpdatedState(onNeedManual)

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        MainActivity.skipAutoLock = false
        val granted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            WeatherStore.locateFromGps(ctx, label, lang) { fail ->
                if (fail != null) manualNow()
            }
        } else {
            manualNow()
        }
    }

    LaunchedEffect(Unit) {
        val prefs = ctx.getSharedPreferences(WEATHER_PREFS, Context.MODE_PRIVATE)
        if (prefs.contains("geo_lat")) return@LaunchedEffect

        val granted =
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED
        val asked = prefs.getBoolean(LOC_ASKED_KEY, false)

        if (granted) {
            WeatherStore.locateFromGps(ctx, label, lang) { fail ->
                if (fail != null) manualNow()
            }
        } else if (!asked) {
            prefs.edit().putBoolean(LOC_ASKED_KEY, true).apply()
            MainActivity.skipAutoLock = true
            launcher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        } else {
            manualNow()
        }
    }
}
