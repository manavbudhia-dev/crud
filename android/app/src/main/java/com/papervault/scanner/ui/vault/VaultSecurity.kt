package com.papervault.scanner.ui.vault

import android.view.WindowManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.papervault.scanner.ui.findActivity
import javax.crypto.Cipher

/** Block screenshots/screen recording and hide content in the recents switcher while shown. */
@Composable
fun SecureWindow(enabled: Boolean = true) {
    val activity = LocalContext.current.findActivity()
    DisposableEffect(enabled) {
        if (enabled) activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { if (enabled) activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
}

/** Show the system biometric prompt bound to [cipher]; returns the authenticated cipher. */
fun showBiometricPrompt(
    activity: FragmentActivity,
    title: String,
    subtitle: String,
    cipher: Cipher,
    onSuccess: (Cipher) -> Unit,
    onError: (String) -> Unit,
) {
    val prompt = BiometricPrompt(
        activity,
        ContextCompat.getMainExecutor(activity),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                val c = result.cryptoObject?.cipher
                if (c != null) onSuccess(c) else onError("Biometric authentication failed")
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                if (errorCode != BiometricPrompt.ERROR_USER_CANCELED &&
                    errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON &&
                    errorCode != BiometricPrompt.ERROR_CANCELED
                ) onError(errString.toString())
            }
        },
    )
    val info = BiometricPrompt.PromptInfo.Builder()
        .setTitle(title)
        .setSubtitle(subtitle)
        .setNegativeButtonText("Use password")
        .setAllowedAuthenticators(BIOMETRIC_STRONG)
        .build()
    prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
}
