package com.example.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.example.vault.VaultActivity
import com.example.core.config.SettingsStore

@Composable
fun VaultScreen() {
    val context = LocalContext.current
    val settings by SettingsStore.state.collectAsState()
    var result by remember { mutableStateOf("") }
    fun launch(intent: Intent) {
        runCatching { context.startActivity(intent); result = "" }.onFailure { result = "This device does not provide that settings screen. Open Android Settings to choose a service." }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SettingsSection("Passwords & cards", "An encrypted local vault, protected by your device screen lock.") {
            ActionRow("Open vault", "Unlock, add, edit or import logins and payment-card details", onClick = {
                launch(Intent(context, VaultActivity::class.java))
            })
            InfoRow("Locks when you leave and after ${settings.vaultSessionSeconds.coerceIn(15, 60)} seconds (configurable in expert settings). Password/card fields block Copy and Cut even when revealed. Use Create encrypted backup in the unlocked vault, then test Restore before relying on it. Recovery on another phone requires that file and its separate passphrase. No CVV storage, automatic cloud sync or plaintext export.")
        }
        SettingsSection("Choose your password manager") {
            if (Build.VERSION.SDK_INT >= 26) {
                ActionRow("Use IO Matrix Autofill", "Optional: Android will ask you to choose a service", onClick = {
                    launch(Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE, Uri.parse("package:${context.packageName}")))
                })
                ActionRow("Open Android settings", "Keep or switch to your existing password manager in Autofill settings", onClick = {
                    launch(Intent(Settings.ACTION_SETTINGS))
                })
            }
            InfoRow("CSV import supports Chrome, Firefox and Bitwarden login exports. Use system Autofill settings to keep or switch to your existing manager. Other managers' databases and browser secrets are never read. Android selects one conventional Autofill service at a time.")
        }
        SettingsSection("Fill policy") {
            InfoRow("Every fill requires unlocking and selecting an entry. Each entry is bound explicitly to the installed app and its signing certificate; web forms also require the exact HTTPS origin and a browser you choose to trust. Unbound imports stay in the vault until you edit their target. Only labelled login and card text fields are supported. CVV, dropdown expiry fields, passkeys, cross-device sync and payment processing are not implemented.")
            InfoRow("For payments, use your bank or wallet app. Saving a card here does not provision NFC payments or authorize a charge.")
            ActionRow("Choose contactless payment app", "Open Android's wallet/payment settings", onClick = {
                launch(Intent(Settings.ACTION_NFC_PAYMENT_SETTINGS))
            })
        }
        if (result.isNotBlank()) InfoRow(result)
    }
}
