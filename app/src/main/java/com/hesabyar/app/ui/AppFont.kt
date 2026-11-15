package com.hesabyar.app.ui

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.hesabyar.app.R

val AppFontFamily = FontFamily(
    Font(R.font.vazirmatn_regular, FontWeight.Normal),
    Font(R.font.vazirmatn_bold, FontWeight.Bold)
)

fun appTypography(): Typography {
    val base = Typography()
    fun TextStyle.app(): TextStyle = copy(fontFamily = AppFontFamily)
    return Typography(
        displayLarge = base.displayLarge.app(),
        displayMedium = base.displayMedium.app(),
        displaySmall = base.displaySmall.app(),
        headlineLarge = base.headlineLarge.app(),
        headlineMedium = base.headlineMedium.app(),
        headlineSmall = base.headlineSmall.app(),
        titleLarge = base.titleLarge.app(),
        titleMedium = base.titleMedium.app(),
        titleSmall = base.titleSmall.app(),
        bodyLarge = base.bodyLarge.app(),
        bodyMedium = base.bodyMedium.app(),
        bodySmall = base.bodySmall.app(),
        labelLarge = base.labelLarge.app(),
        labelMedium = base.labelMedium.app(),
        labelSmall = base.labelSmall.app()
    )
}
