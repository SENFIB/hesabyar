package com.hesabyar.app

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import com.hesabyar.app.ui.HesabyarScreen
import com.hesabyar.app.ui.LockScreen
import com.hesabyar.app.ui.appTypography

class MainActivity : AppCompatActivity() {

    companion object {
        // در companion نگه داشته می‌شود تا با تغییر زبان (ساخت دوباره اکتیویتی) رمز دوباره پرسیده نشود
        val locked = mutableStateOf(true)
    }

    override fun onStop() {
        super.onStop()
        // وقتی برنامه به پس‌زمینه می‌رود قفل می‌شود (اما با چرخش صفحه یا تغییر زبان نه)
        if (!isChangingConfigurations) {
            locked.value = true
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Color(0xFF0F766E),
                    primaryContainer = Color(0xFFCCEBE6),
                    onPrimaryContainer = Color(0xFF0B3B36)
                ),
                typography = appTypography()
            ) {
                if (locked.value) {
                    LockScreen(onUnlocked = { locked.value = false })
                } else {
                    HesabyarScreen()
                }
            }
        }
    }
}
