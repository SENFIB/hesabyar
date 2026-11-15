package com.hesabyar.app.ui

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
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

fun fingerprintSupported(context: Context): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT)

// نمایش پنجره اثر انگشت (اندروید ۹ به بالا)
fun startFingerprint(
    context: Context,
    title: String,
    cancel: String,
    onSuccess: () -> Unit,
    onFail: (String) -> Unit
): CancellationSignal? {
    if (!fingerprintSupported(context)) return null
    val executor = ContextCompat.getMainExecutor(context)
    val prompt = BiometricPrompt.Builder(context)
        .setTitle(title)
        .setNegativeButton(cancel, executor) { _, _ -> onFail("") }
        .build()
    val signal = CancellationSignal()
    prompt.authenticate(signal, executor, object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
            onSuccess()
        }

        override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
            onFail(errString?.toString() ?: "")
        }
    })
    return signal
}

@Composable
fun LockScreen(onUnlocked: () -> Unit) {
    val context = LocalContext.current
    val isSetup = remember { !PinStore.hasPin(context) }

    var pin by remember { mutableStateOf("") }
    var pinRepeat by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    val titleSetup = tr("یک رمز عبور بسازید", "Create a passcode", "أنشئ رمز المرور")
    val titleEnter = tr("رمز عبور را وارد کنید", "Enter your passcode", "أدخل رمز المرور")
    val labelPin = tr("رمز عبور (حداقل ۴ رقم)", "Passcode (at least 4 digits)", "رمز المرور (4 أرقام على الأقل)")
    val labelRepeat = tr("تکرار رمز عبور", "Repeat passcode", "تكرار رمز المرور")
    val btnSave = tr("ثبت رمز عبور", "Save passcode", "حفظ رمز المرور")
    val btnEnter = tr("ورود", "Unlock", "دخول")
    val btnFinger = tr("ورود با اثر انگشت", "Unlock with fingerprint", "الدخول ببصمة الإصبع")
    val errShort = tr("حداقل ۴ رقم وارد کنید", "Enter at least 4 digits", "أدخل 4 أرقام على الأقل")
    val errMismatch = tr("رمزها یکسان نیستند. دوباره وارد کنید.", "Passcodes do not match. Try again.", "رمزا المرور غير متطابقين. حاول مجدداً.")
    val errWrong = tr("رمز عبور اشتباه است", "Wrong passcode", "رمز المرور غير صحيح")
    val fingerTitle = tr("ورود به حسابیار", "Unlock Hesabyar", "الدخول إلى حسابيار")
    val cancelText = tr("انصراف", "Cancel", "إلغاء")

    val fingerprintOk = fingerprintSupported(context)

    fun tryFingerprint() {
        startFingerprint(
            context = context,
            title = fingerTitle,
            cancel = cancelText,
            onSuccess = onUnlocked,
            onFail = { msg -> if (msg.isNotEmpty()) error = msg }
        )
    }

    // اگر اثر انگشت فعال باشد، بلافاصله پنجره آن باز می‌شود
    LaunchedEffect(Unit) {
        if (!isSetup && fingerprintOk) tryFingerprint()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = if (isSetup) titleSetup else titleEnter,
            style = MaterialTheme.typography.titleLarge
        )
        Spacer(Modifier.height(24.dp))

        OutlinedTextField(
            value = pin,
            onValueChange = { v ->
                val latin = toLatinDigits(v)
                if (latin.length <= 8 && latin.all { it.isDigit() }) pin = latin
            },
            label = { Text(labelPin) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )

        if (isSetup) {
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = pinRepeat,
                onValueChange = { v ->
                    val latin = toLatinDigits(v)
                    if (latin.length <= 8 && latin.all { it.isDigit() }) pinRepeat = latin
                },
                label = { Text(labelRepeat) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
        }

        error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error)
        }

        Spacer(Modifier.height(24.dp))

        Button(
            onClick = {
                if (isSetup) {
                    when {
                        pin.length < 4 -> error = errShort
                        pin != pinRepeat -> {
                            error = errMismatch
                            pinRepeat = ""
                        }
                        else -> {
                            PinStore.savePin(context, pin)
                            onUnlocked()
                        }
                    }
                } else {
                    if (PinStore.checkPin(context, pin)) {
                        onUnlocked()
                    } else {
                        error = errWrong
                        pin = ""
                    }
                }
            },
            enabled = pin.length >= 4 && (!isSetup || pinRepeat.isNotEmpty()),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (isSetup) btnSave else btnEnter)
        }

        if (!isSetup && fingerprintOk) {
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = { tryFingerprint() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(btnFinger)
            }
        }
    }
}
