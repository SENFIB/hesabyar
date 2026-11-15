package com.hesabyar.app.ui

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.hesabyar.app.R
import java.security.MessageDigest

object PinStore {
    private const val PREFS = "hesabyar_lock"
    private const val KEY = "pin_hash"

    fun hasPin(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains(KEY)

    fun savePin(context: Context, pin: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, hash(pin)).apply()
    }

    fun checkPin(context: Context, pin: String): Boolean {
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null)
        return saved != null && saved == hash(pin)
    }

    private fun hash(pin: String): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(("hesabyar::" + pin).toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}

@Composable
fun LockScreen(onUnlocked: () -> Unit) {
    val context = LocalContext.current
    val isSetup = remember { !PinStore.hasPin(context) }
    var firstPin by remember { mutableStateOf<String?>(null) }
    var input by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    val wrongText = stringResource(R.string.v3_lock_wrong)
    val mismatchText = stringResource(R.string.v3_lock_mismatch)

    val title = when {
        !isSetup -> stringResource(R.string.v3_lock_enter)
        firstPin == null -> stringResource(R.string.v3_lock_set)
        else -> stringResource(R.string.v3_lock_confirm)
    }
    val buttonText = when {
        !isSetup -> stringResource(R.string.v3_lock_unlock)
        firstPin == null -> stringResource(R.string.v3_lock_next)
        else -> stringResource(R.string.v3_lock_save)
    }

    fun submit() {
        if (!isSetup) {
            if (PinStore.checkPin(context, input)) {
                onUnlocked()
            } else {
                error = wrongText
                input = ""
            }
        } else if (firstPin == null) {
            firstPin = input
            input = ""
            error = null
        } else if (input == firstPin) {
            PinStore.savePin(context, input)
            onUnlocked()
        } else {
            error = mismatchText
            input = ""
            firstPin = null
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = input,
            onValueChange = { v ->
                if (v.length <= 8 && v.all { it.isDigit() }) input = v
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )
        error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = { submit() },
            enabled = input.length >= 4,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(buttonText)
        }
    }
}
