package com.hesabyar.app

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import com.hesabyar.app.ui.HesabyarScreen

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Color(0xFF0F766E),
                    primaryContainer = Color(0xFFCCEBE6),
                    onPrimaryContainer = Color(0xFF0B3B36)
                )
            ) {
                HesabyarScreen()
            }
        }
    }
}
