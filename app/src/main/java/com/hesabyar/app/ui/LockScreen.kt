package com.hesabyar.app.ui

import android.content.Context
import android.content.DialogInterface
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import java.security.MessageDigest

// کد خطاهای BiometricPrompt (به صورت عدد تا به نسخه‌های مختلف Android وابسته نباشد)
private const val ERR_NEGATIVE = 13
private const val ERR_LOCKOUT = 7
private const val ERR_LOCKOUT_PERMANENT = 9

object PinStore {
    private const val PREFS = "hesabyar_lock"
    private const val KEY = "pin_hash"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_ASKED = "asked"
    private const val KEY_DELAY = "delay_index"

    // مدت تا قفل شدن بعد از رفتن به پس‌زمینه (میلی‌ثانیه)؛ صفر یعنی بلافاصله
    val DELAYS_MS = longArrayOf(0L, 60_000L, 300_000L, 900_000L, 3_600_000L)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun hash(pin: String): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(("hesabyar::$pin").toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun hasPin(ctx: Context): Boolean = prefs(ctx).getString(KEY, null) != null

    // نسخه‌های قبلی رمز داشتند ولی کلید فعال بودن نداشتند؛ آنها فعال حساب می‌شوند
    fun isEnabled(ctx: Context): Boolean {
        val p = prefs(ctx)
        if (!hasPin(ctx)) return false
        return if (p.contains(KEY_ENABLED)) p.getBoolean(KEY_ENABLED, false) else true
    }

    fun setEnabled(ctx: Context, value: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_ENABLED, value && hasPin(ctx)).apply()
    }

    fun wasAsked(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_ASKED, hasPin(ctx))

    fun markAsked(ctx: Context) {
        prefs(ctx).edit().putBoolean(KEY_ASKED, true).apply()
    }

    fun delayIndex(ctx: Context): Int =
        prefs(ctx).getInt(KEY_DELAY, 0).coerceIn(0, DELAYS_MS.lastIndex)

    fun setDelayIndex(ctx: Context, index: Int) {
        prefs(ctx).edit().putInt(KEY_DELAY, index.coerceIn(0, DELAYS_MS.lastIndex)).apply()
    }

    fun delayMs(ctx: Context): Long = DELAYS_MS[delayIndex(ctx)]

    fun setPin(ctx: Context, pin: String) {
        prefs(ctx).edit().putString(KEY, hash(pin)).apply()
    }

    fun check(ctx: Context, pin: String): Boolean =
        prefs(ctx).getString(KEY, null) == hash(pin)
}

fun fingerprintAvailable(ctx: Context): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
        ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT)

// نمایش پنجره اثر انگشت
@Suppress("NewApi")
private fun showFingerprint(
    ctx: Context,
    title: String,
    cancelText: String,
    onSuccess: () -> Unit,
    onNegative: () -> Unit,
    onError: (Int) -> Unit
) {
    val executor = ContextCompat.getMainExecutor(ctx)
    val prompt = BiometricPrompt.Builder(ctx)
        .setTitle(title)
        .setNegativeButton(cancelText, executor, DialogInterface.OnClickListener { _, _ -> onNegative() })
        .build()
    prompt.authenticate(
        CancellationSignal(),
        executor,
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                onSuccess()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                if (errorCode != ERR_NEGATIVE) onError(errorCode)
            }
        }
    )
}

// قفل برنامه: فقط وقتی رمز فعال است نشان داده می‌شود. همیشه اول اثر انگشت خواسته می‌شود.
@Composable
fun LockScreen(onUnlocked: () -> Unit) {
    val ctx = LocalContext.current
    if (!PinStore.isEnabled(ctx)) {
        LaunchedEffect(Unit) { onUnlocked() }
        return
    }

    val canFp = remember { fingerprintAvailable(ctx) }
    var usePin by remember { mutableStateOf(!canFp) }
    var attempt by remember { mutableStateOf(0) }
    var fpMessage by remember { mutableStateOf<String?>(null) }
    var pin by remember { mutableStateOf("") }
    var pinError by remember { mutableStateOf<String?>(null) }

    // متن‌ها باید داخل بدنه Compose ساخته شوند
    val fpTitle = tr("ورود با اثر انگشت", "Sign in with fingerprint", "تسجيل الدخول بالبصمة")
    val fpCancel = tr("ورود با رمز عبور", "Use password", "استخدام كلمة المرور")
    val fpHint = tr("اثر انگشت خود را روی حسگر بگذارید", "Place your finger on the sensor", "ضع إصبعك على الحساس")
    val fpFailText = tr("اثر انگشت شناسایی نشد. دوباره امتحان کنید.", "Fingerprint not recognised. Try again.", "لم يتم التعرف على البصمة. حاول مرة أخرى.")
    val lockoutText = tr("اثر انگشت موقتاً قفل شده است. با رمز عبور وارد شوید.", "Fingerprint is temporarily locked. Use your password.", "البصمة مقفلة مؤقتاً. استخدم كلمة المرور.")
    val fpRetry = tr("تلاش دوباره با اثر انگشت", "Try fingerprint again", "حاول بالبصمة مرة أخرى")
    val pinLabel = tr("رمز عبور", "Password", "كلمة المرور")
    val enterText = tr("ورود", "Enter", "دخول")
    val wrongText = tr("رمز عبور اشتباه است", "Wrong password", "كلمة المرور غير صحيحة")
    val appLockedText = tr("برنامه قفل است", "App is locked", "التطبيق مقفل")
    val backToFpText = tr("ورود با اثر انگشت", "Sign in with fingerprint", "تسجيل الدخول بالبصمة")

    LaunchedEffect(attempt) {
        if (canFp && !usePin) {
            delay(350)
            showFingerprint(
                ctx = ctx,
                title = fpTitle,
                cancelText = fpCancel,
                onSuccess = onUnlocked,
                onNegative = {
                    usePin = true
                    fpMessage = null
                },
                onError = { code ->
                    fpMessage = if (code == ERR_LOCKOUT || code == ERR_LOCKOUT_PERMANENT) lockoutText else fpFailText
                }
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(appLockedText, style = MaterialTheme.typography.headlineSmall)
        Spacer(modifier = Modifier.height(20.dp))

        if (usePin) {
            OutlinedTextField(
                value = pin,
                onValueChange = { pin = digitsOnly(it).take(8) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
                label = { Text(pinLabel) }
            )
            pinError?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
            }
            Spacer(modifier = Modifier.height(10.dp))
            Button(
                onClick = {
                    if (PinStore.check(ctx, pin)) {
                        onUnlocked()
                    } else {
                        pinError = wrongText
                        pin = ""
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(enterText)
            }
            if (canFp) {
                TextButton(onClick = {
                    pinError = null
                    fpMessage = null
                    usePin = false
                    attempt++
                }) {
                    Text(backToFpText)
                }
            }
        } else {
            Text(fpHint, style = MaterialTheme.typography.bodyLarge)
            fpMessage?.let {
                Spacer(modifier = Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error)
            }
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = {
                    fpMessage = null
                    attempt++
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(fpRetry)
            }
            TextButton(onClick = {
                pinError = null
                usePin = true
            }) {
                Text(fpCancel)
            }
        }
    }
}

// پیشنهاد رمز در اولین اجرا: بله یا نه
@Composable
fun PasscodeOffer(onDone: () -> Unit) {
    val ctx = LocalContext.current
    var setting by remember { mutableStateOf(false) }

    if (setting) {
        PinSetup(
            onDone = {
                PinStore.setEnabled(ctx, true)
                PinStore.markAsked(ctx)
                onDone()
            },
            onCancel = {
                PinStore.markAsked(ctx)
                onDone()
            }
        )
        return
    }

    val titleText = tr("رمز عبور برنامه", "App password", "كلمة مرور التطبيق")
    val bodyText = tr(
        "می‌خواهید برنامه با رمز یا اثر انگشت قفل شود؟ اگر الان نمی‌خواهید، بعداً از تنظیمات می‌توانید فعالش کنید.",
        "Do you want the app to be locked with a password or fingerprint? If not now, you can turn it on later in Settings.",
        "هل تريد قفل التطبيق بكلمة مرور أو بصمة؟ إذا لم ترد الآن، يمكنك تفعيله لاحقًا من الإعدادات."
    )
    val yesText = tr("بله، رمز بگذار", "Yes, set a password", "نعم، ضع كلمة مرور")
    val noText = tr("نه، فعلاً نه", "No, not now", "لا، ليس الآن")

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(titleText, style = MaterialTheme.typography.headlineSmall)
        Spacer(modifier = Modifier.height(16.dp))
        Text(bodyText, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        Spacer(modifier = Modifier.height(28.dp))
        Button(
            onClick = { setting = true },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(yesText)
        }
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(
            onClick = {
                PinStore.markAsked(ctx)
                onDone()
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(noText)
        }
    }
}

// ساخت یا تغییر رمز: دو کادر، یکی برای رمز و یکی برای تکرار آن
@Composable
fun PinSetup(onDone: () -> Unit, onCancel: (() -> Unit)? = null) {
    val ctx = LocalContext.current
    var p1 by remember { mutableStateOf("") }
    var p2 by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }

    val shortErr = tr("حداقل ۴ رقم وارد کنید", "Enter at least 4 digits", "أدخل 4 أرقام على الأقل")
    val mismatchErr = tr("دو رمز یکسان نیستند", "The passwords do not match", "كلمتا المرور غير متطابقتين")
    val cancelText = tr("انصراف", "Cancel", "إلغاء")
    val titleText = tr("رمز عبور بسازید", "Create a password", "أنشئ كلمة مرور")
    val pinLabel = tr("رمز عبور (حداقل ۴ رقم)", "Password (at least 4 digits)", "كلمة المرور (4 أرقام على الأقل)")
    val repeatLabel = tr("تکرار رمز عبور", "Repeat password", "تكرار كلمة المرور")
    val saveText = tr("ذخیره", "Save", "حفظ")

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(titleText, style = MaterialTheme.typography.headlineSmall)
        Spacer(modifier = Modifier.height(20.dp))

        OutlinedTextField(
            value = p1,
            onValueChange = { p1 = digitsOnly(it).take(8) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
            label = { Text(pinLabel) }
        )
        Spacer(modifier = Modifier.height(10.dp))
        OutlinedTextField(
            value = p2,
            onValueChange = { p2 = digitsOnly(it).take(8) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
            label = { Text(repeatLabel) }
        )

        err?.let {
            Spacer(modifier = Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error)
        }

        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = {
                val a = p1
                val b = p2
                when {
                    a.length < 4 -> err = shortErr
                    a != b -> err = mismatchErr
                    else -> {
                        PinStore.setPin(ctx, a)
                        onDone()
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(saveText)
        }
        if (onCancel != null) {
            TextButton(
                onClick = onCancel,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(cancelText)
            }
        }
    }
}

// پرسیدن رمز فعلی (برای خاموش کردن قفل)
@Composable
fun PinCheckDialog(title: String, onDismiss: () -> Unit, onOk: () -> Unit) {
    val ctx = LocalContext.current
    var pin by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }

    val wrongText = tr("رمز عبور اشتباه است", "Wrong password", "كلمة المرور غير صحيحة")
    val pinLabel = tr("رمز عبور", "Password", "كلمة المرور")
    val okText = tr("تأیید", "Confirm", "تأكيد")
    val cancelText = tr("انصراف", "Cancel", "إلغاء")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = pin,
                    onValueChange = {
                        pin = digitsOnly(it).take(8)
                        wrong = false
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    visualTransformation = PasswordVisualTransformation(),
                    label = { Text(pinLabel) },
                    modifier = Modifier.fillMaxWidth()
                )
                if (wrong) {
                    Text(wrongText, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (PinStore.check(ctx, pin)) {
                    onOk()
                } else {
                    wrong = true
                    pin = ""
                }
            }) {
                Text(okText)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(cancelText)
            }
        }
    )
}
