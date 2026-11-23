package com.papervault.scanner

import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.papervault.scanner.ui.AppNavHost
import com.papervault.scanner.ui.theme.PaperVaultTheme

/** FragmentActivity (not ComponentActivity) because BiometricPrompt requires it. */
class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PaperVaultTheme {
                AppNavHost()
            }
        }
    }
}
