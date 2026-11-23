package com.papervault.scanner

import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.papervault.scanner.data.ThemeMode
import com.papervault.scanner.ui.AppNavHost
import com.papervault.scanner.ui.settings.OnboardingScreen
import com.papervault.scanner.ui.theme.PaperVaultTheme

/** FragmentActivity (not ComponentActivity) because BiometricPrompt requires it. */
class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val settingsRepo = (application as PaperVaultApp).container.settings
        setContent {
            val settings by settingsRepo.settings.collectAsStateWithLifecycle()
            val dark = when (settings.theme) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            PaperVaultTheme(dark = dark) {
                if (settings.onboarded) AppNavHost()
                else OnboardingScreen(onDone = { settingsRepo.update { it.copy(onboarded = true) } })
            }
        }
    }
}
