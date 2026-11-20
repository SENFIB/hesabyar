package com.hesabyar.app.ui

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private class FooterText(
    val app: String,
    val version: String,
    val code: String,
    val device: String,
    val android: String,
    val note: String
)

private fun footerText(lang: String): FooterText = when (lang) {
    "fa" -> FooterText("حسابیار", "نسخه", "کد", "دستگاه", "اندروید", "داده‌های شما فقط روی همین گوشی ذخیره می‌شوند.")
    "ar" -> FooterText("حسابيار", "الإصدار", "رقم البناء", "الجهاز", "أندرويد", "بياناتك محفوظة على هذا الهاتف فقط.")
    else -> FooterText("Hesabyar", "Version", "Build", "Device", "Android", "Your data is stored only on this phone.")
}

private fun localDigits(text: String, lang: String): String {
    val digits = when (lang) {
        "fa" -> "۰۱۲۳۴۵۶۷۸۹"
        "ar" -> "٠١٢٣٤٥٦٧٨٩"
        else -> "0123456789"
    }
    val sb = StringBuilder()
    for (ch in text) {
        if (ch in '0'..'9') sb.append(digits[ch - '0']) else sb.append(ch)
    }
    return sb.toString()
}

@Suppress("DEPRECATION")
private fun readVersion(ctx: Context): Pair<String, String> {
    return try {
        val info = if (Build.VERSION.SDK_INT >= 33) {
            ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.PackageInfoFlags.of(0L))
        } else {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        }
        val code = if (Build.VERSION.SDK_INT >= 28) {
            info.longVersionCode.toString()
        } else {
            info.versionCode.toString()
        }
        Pair(info.versionName ?: "?", code)
    } catch (e: Exception) {
        Pair("?", "?")
    }
}

@Composable
fun AppVersionFooter(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val lang = currentLang()
    val version = remember { readVersion(ctx) }
    val t = footerText(lang)

    val line1 = t.app + "  ·  " + t.version + " " + localDigits(version.first, lang) +
            "  (" + t.code + " " + localDigits(version.second, lang) + ")"
    val line2 = t.android + ": " + localDigits(Build.VERSION.RELEASE ?: "?", lang) +
            "  ·  " + t.device + ": " + Build.MANUFACTURER + " " + Build.MODEL

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 20.dp, bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        HorizontalDivider()
        Spacer(Modifier.height(10.dp))
        Text(
            text = line1,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )
        Text(
            text = line2,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Text(
            text = t.note,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}
