package com.example.vault

import android.app.Activity
import android.app.KeyguardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.autofill.Dataset
import android.service.autofill.FillResponse
import android.view.WindowManager
import android.view.MotionEvent
import android.view.View
import android.view.autofill.AutofillManager
import android.view.autofill.AutofillValue
import android.widget.RemoteViews
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.BasicSecureTextField
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.foundation.text.input.maxLength
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PlatformImeOptions
import androidx.lifecycle.lifecycleScope
import androidx.compose.ui.unit.dp
import com.example.core.vault.VaultCsv
import com.example.core.vault.VaultEntry
import com.example.core.vault.VaultKind
import com.example.core.vault.VaultOrigin
import com.example.core.vault.VaultSession
import com.example.core.vault.VaultStore
import com.example.core.vault.VaultTargets
import com.example.core.vault.VaultBackup
import com.example.core.vault.VaultImports
import com.example.core.vault.VaultMergeMode
import com.example.core.vault.readBytesBounded
import com.example.core.security.PrivateInputContract
import com.example.core.config.SettingsStore
import com.example.ui.theme.MyApplicationTheme
import java.security.SecureRandom
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive

/** Intentionally separate from the general settings activity and its saved state/logger. */
class VaultActivity : ComponentActivity() {
    private lateinit var store: VaultStore
    private var session by mutableStateOf<VaultSession?>(null)
    private var entries by mutableStateOf<List<VaultEntry>>(emptyList())
    private var importPreview by mutableStateOf<List<VaultEntry>>(emptyList())
    private var message by mutableStateOf("Unlock with your device screen lock.")
    private enum class FileMode { CSV_IMPORT, BACKUP_IMPORT, BACKUP_EXPORT }
    private data class FileRequest(val mode: FileMode, val uri: Uri)
    private var pendingFile: FileRequest? = null
    private var transfer by mutableStateOf<FileRequest?>(null)
    private var transferBusy by mutableStateOf(false)
    private var importJob: Job? = null
    private var fillRequest: VaultFillRequest? = null
    private var invalidFill = false
    private val handler = Handler(Looper.getMainLooper())
    private val expiry = Runnable { lock(); message = "Session expired. Unlock again." }

    private val credential = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            try {
                val seconds = SettingsStore.current.vaultSessionSeconds.coerceIn(15, VaultStore.SESSION_SECONDS)
                val opened = store.unlock(seconds)
                session = opened
                entries = opened.entries()
                message = "Unlocked for $seconds seconds; leaving this screen locks it."
                handler.removeCallbacks(expiry)
                handler.postDelayed(expiry, (opened.expiresAt - SystemClock.elapsedRealtime()).coerceAtLeast(0))
                pendingFile?.let { request ->
                    pendingFile = null
                    if (request.mode == FileMode.CSV_IMPORT) readImport(request.uri, opened)
                    else transfer = request
                }
            } catch (_: Exception) {
                pendingFile = null
                lock()
                message = "Could not unlock or read the selected file. Existing vault data is preserved. Check the CSV format, device lock and Keystore availability."
            }
        } else {
            pendingFile = null
            lock()
            message = "Authentication cancelled."
        }
    }

    private val importFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) requestFile(FileMode.CSV_IMPORT, uri)
    }
    private val importBackup = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) requestFile(FileMode.BACKUP_IMPORT, uri)
    }
    private val exportBackup = registerForActivityResult(ActivityResultContracts.CreateDocument("application/jose")) { uri ->
        if (uri != null) requestFile(FileMode.BACKUP_EXPORT, uri)
    }

    private fun requestFile(mode: FileMode, uri: Uri) {
        pendingFile = FileRequest(mode, uri)
        authenticate()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (Build.VERSION.SDK_INT >= 31) window.setHideOverlayWindows(true)
        if (Build.VERSION.SDK_INT >= 26) window.decorView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        // FLAG_SECURE already disables content capture on API 29; this view-level
        // defence is available only from API 30.
        if (Build.VERSION.SDK_INT >= 30) window.decorView.importantForContentCapture = View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS
        // Never save form contents or request secrets through Activity saved-state restoration.
        SettingsStore.init(this)
        store = VaultStore(this)
        if (Build.VERSION.SDK_INT >= 26) {
            intent.getStringExtra(EXTRA_FILL_TOKEN)?.let { token ->
                fillRequest = VaultFillRequests.take(token)
                invalidFill = fillRequest == null
            }
        }
        setContent {
            MyApplicationTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("IO Matrix vault", style = MaterialTheme.typography.headlineSmall)
                    Text(message)
                    if (invalidFill) {
                        Text("This Autofill request expired. Return to the form and request Autofill again.")
                        Button(onClick = { finish() }) { Text("Close") }
                    } else if (session == null) {
                        Text("Protected by Android Keystore and excluded from automatic device backups. Create an encrypted backup with a separate passphrase before relying on this vault. That file and passphrase let you restore it on another phone; neither can be recovered for you.")
                        Button(onClick = ::authenticate) { Text("Unlock") }
                        OutlinedButton(onClick = { finish() }) { Text("Close") }
                    } else {
                        key(session) {
                            val request = fillRequest
                            if (Build.VERSION.SDK_INT >= 26 && request != null) {
                                FillChoices(request)
                            } else {
                                VaultEditorList()
                            }
                        }
                        OutlinedButton(onClick = { lock(); message = "Vault locked." }) { Text("Lock now") }
                    }
                }
            }
        }
    }

    private fun authenticate() {
        try {
            lock()
            store.prepareAuthentication()
            @Suppress("DEPRECATION")
            val intent = getSystemService(KeyguardManager::class.java).createConfirmDeviceCredentialIntent(
                "Unlock IO Matrix vault", "Confirm your device PIN, pattern or password.")
            if (intent == null) { message = "Set a device screen lock before using the vault."; return }
            credential.launch(intent)
        } catch (_: Exception) {
            message = "A secure device screen lock and an available Android Keystore are required. Existing encrypted data has been preserved."
        }
    }

    private fun lock() {
        handler.removeCallbacks(expiry)
        importJob?.cancel()
        importJob = null
        session?.close()
        session = null
        entries = emptyList()
        importPreview = emptyList()
        transfer = null
        transferBusy = false
    }

    private fun readImport(uri: Uri, opened: VaultSession) {
        message = "Reading selected CSV…"
        importJob = lifecycleScope.launch {
            try {
                val imported = withContext(Dispatchers.IO) {
                    val bytes = contentResolver.openInputStream(uri)?.use { it.readBytesBounded(VaultCsv.MAX_CHARS) }
                        ?: error("Cannot read selected file.")
                    try { VaultCsv.parse(String(bytes, Charsets.UTF_8)) } finally { bytes.fill(0) }
                }
                if (session !== opened) return@launch
                opened.checkActive()
                importPreview = imported
                message = "Review ${importPreview.size} imported login(s), then confirm. None are bound for Autofill."
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (session === opened) message = "Cannot import this CSV. Check the format and 1 MiB / 1000-entry limit; no entries were saved."
            }
        }
    }

    override fun onStop() { lock(); super.onStop() }
    override fun onDestroy() { handler.removeCallbacksAndMessages(null); lock(); super.onDestroy() }
    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); outState.clear() }
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        // Also reject obscured touches on Android versions without hideOverlayWindows.
        if (event.flags and (MotionEvent.FLAG_WINDOW_IS_OBSCURED or MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED) != 0) return false
        return super.dispatchTouchEvent(event)
    }

    private fun persist(next: List<VaultEntry>) {
        val active = session ?: error("Unlock the vault first.")
        active.replace(next)
        entries = active.entries()
    }

    @Composable
    private fun VaultEditorList() {
        transfer?.let { request -> BackupForm(request); return }
        var editing by remember { mutableStateOf<VaultEntry?>(null) }
        var delete by remember { mutableStateOf<VaultEntry?>(null) }
        var confirmBinding by remember { mutableStateOf<VaultEntry?>(null) }
        val draft = editing
        if (draft != null) {
            EntryEditor(draft, onCancel = { editing = null }, onSave = { entry ->
                if (entry.origin != null && entry.origin != entries.firstOrNull { it.id == entry.id }?.origin) {
                    confirmBinding = entry
                } else {
                    persist(entries.filterNot { it.id == entry.id } + entry)
                    editing = null
                    message = "Entry saved."
                }
            })
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { editing = VaultEntry(kind = VaultKind.LOGIN, label = "") }) { Text("Add login") }
                OutlinedButton(onClick = { editing = VaultEntry(kind = VaultKind.PAYMENT_CARD, label = "") }) { Text("Add card") }
            }
            OutlinedButton(onClick = { importFile.launch(arrayOf("text/*", "application/csv", "application/octet-stream")) }) { Text("Import login CSV") }
            Text("CSV imports: Chrome, Firefox or Bitwarden. Import reads the file once and does not retain permission. Delete plaintext exports yourself after checking the result.", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { exportBackup.launch("io-matrix-vault.jwe") }) { Text("Create encrypted backup") }
            OutlinedButton(onClick = { importBackup.launch(arrayOf("application/jose", "application/octet-stream", "text/plain")) }) { Text("Restore encrypted backup") }
            if (importPreview.isNotEmpty()) {
                var mergeMode by remember(importPreview) { mutableStateOf(VaultMergeMode.KEEP_EXISTING) }
                val plan = remember(entries, importPreview, mergeMode) { runCatching { VaultImports.merge(entries, importPreview, mergeMode) } }
                Text("Import preview (${importPreview.size})", style = MaterialTheme.typography.titleMedium)
                importPreview.take(10).forEach { Text(it.label) }
                if (importPreview.size > 10) Text("…and ${importPreview.size - 10} more")
                com.example.ui.settings.ChoiceRow("Matching backup IDs", options = VaultMergeMode.entries.toList(), selected = mergeMode,
                    optionLabel = { it.label }, onSelect = { mergeMode = it })
                plan.fold(onSuccess = { Text("${it.added} new · ${it.replaced} replaced · ${it.skipped} kept unchanged. Imported entries need a fresh Autofill target binding.") },
                    onFailure = { Text("Import exceeds capacity or contains invalid data. Nothing will be changed.") })
                Button(enabled = plan.isSuccess, onClick = {
                    try {
                        persist(VaultImports.merge(entries, importPreview, mergeMode).entries)
                        importPreview = emptyList()
                        message = "Imported. Edit each login to bind it to an app/browser before Autofill."
                    } catch (_: Exception) { message = "Import could not be saved. Unlock again or check the vault capacity. No entries were imported." }
                }) { Text("Confirm import") }
                TextButton(onClick = { importPreview = emptyList() }) { Text("Discard import") }
            }
            if (entries.isEmpty()) Text("No entries yet.")
            entries.forEach { entry ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(entry.label, style = MaterialTheme.typography.titleMedium)
                        Text(entry.summary)
                        Text(entry.origin?.let {
                            "${it.httpsOrigin ?: "Native app"} · ${it.packageName}"
                        } ?: "Vault only · not bound for Autofill", style = MaterialTheme.typography.bodySmall)
                        Row {
                            TextButton(onClick = { editing = entry }) { Text("Edit") }
                            TextButton(onClick = { delete = entry }) { Text("Delete") }
                        }
                    }
                }
            }
            Text("Card Autofill requires an exact merchant/app binding and your confirmation. CVV is never stored or filled. Use your bank/wallet app to authorize payments.", style = MaterialTheme.typography.bodySmall)
        }
        confirmBinding?.let { entry ->
            val origin = entry.origin!!
            AlertDialog(onDismissRequest = { confirmBinding = null }, title = { Text("Trust this Autofill target?") },
                text = { Column { Text("${origin.packageName}\n${origin.httpsOrigin ?: "Native app only"}")
                    Text("Signing certificate SHA-256:\n${origin.certificateSha256.joinToString("\n")}", style = MaterialTheme.typography.bodySmall)
                    Text(if (origin.httpsOrigin != null) "You are trusting this installed browser to report the correct HTTPS origin. Do not bind a website to an app you do not trust." else "This credential will be offered only to this exact package and signer.") } },
                confirmButton = { TextButton(onClick = {
                    try { persist(entries.filterNot { it.id == entry.id } + entry); editing = null; confirmBinding = null; message = "Entry and target saved." }
                    catch (_: Exception) { confirmBinding = null; message = "Could not save. Unlock again; existing vault data is preserved." }
                }) { Text("Trust and save") } },
                dismissButton = { TextButton(onClick = { confirmBinding = null }) { Text("Cancel") } })
        }
        delete?.let { entry ->
            AlertDialog(onDismissRequest = { delete = null }, title = { Text("Delete ${entry.label}?") },
                text = { Text("There is no undo. An encrypted backup made before this deletion can restore the entry.") },
                confirmButton = { TextButton(onClick = {
                    try { persist(entries.filterNot { it.id == entry.id }); delete = null; message = "Entry deleted." }
                    catch (_: Exception) { delete = null; message = "Could not delete. Unlock again." }
                }) { Text("Delete") } }, dismissButton = { TextButton(onClick = { delete = null }) { Text("Cancel") } })
        }
    }

    @Composable
    private fun BackupForm(request: FileRequest) {
        var passphrase by remember(request) { mutableStateOf("") }
        var confirmation by remember(request) { mutableStateOf("") }
        val exporting = request.mode == FileMode.BACKUP_EXPORT
        Text(if (exporting) "Encrypt a portable backup" else "Unlock the selected backup", style = MaterialTheme.typography.titleMedium)
        Text("Use a long, unique backup passphrase, separate from your device PIN. Store it independently of the backup file. Losing it prevents recovery. App/browser Autofill bindings are not transferred.")
        com.example.ui.PrivateTextField("Backup passphrase", passphrase, { passphrase = it }, secret = true, limit = 1024)
        if (exporting) com.example.ui.PrivateTextField("Repeat backup passphrase", confirmation, { confirmation = it }, secret = true, limit = 1024)
        Button(enabled = !transferBusy && passphrase.length >= 12 && (!exporting || passphrase == confirmation), onClick = {
            val secret = passphrase.toCharArray()
            passphrase = ""; confirmation = ""
            runBackup(request, secret)
        }) { Text(if (transferBusy) "Working…" else if (exporting) "Encrypt and save backup" else "Decrypt and review entries") }
        TextButton(onClick = { importJob?.cancel(); transfer = null; transferBusy = false }) { Text("Cancel") }
    }

    private fun runBackup(request: FileRequest, passphrase: CharArray) {
        val opened = session ?: run { passphrase.fill('\u0000'); return }
        transferBusy = true
        importJob = lifecycleScope.launch {
            try {
                if (request.mode == FileMode.BACKUP_EXPORT) {
                    val snapshot = opened.entries()
                    withContext(Dispatchers.IO) {
                        val archive = VaultBackup.encrypt(snapshot, passphrase)
                        try {
                            ensureActive(); opened.checkActive()
                            contentResolver.openOutputStream(request.uri, "wt")?.use { it.write(archive); it.flush() }
                                ?: error("Cannot write selected document")
                        } finally { archive.fill(0) }
                    }
                    if (session === opened) {
                        opened.checkActive()
                        transfer = null
                        message = "Encrypted backup written. Test Restore with this file and passphrase before removing older copies; cancel after reviewing the preview."
                    }
                } else {
                    val restored = withContext(Dispatchers.IO) {
                        val archive = contentResolver.openInputStream(request.uri)?.use { it.readBytesBounded(VaultBackup.MAX_BYTES) }
                            ?: error("Cannot read selected document")
                        try { ensureActive(); VaultBackup.decrypt(archive, passphrase) }
                        finally { archive.fill(0) }
                    }
                    if (session === opened) {
                        opened.checkActive()
                        transfer = null
                        importPreview = restored
                        message = "Backup authenticated: ${restored.size} entries. Review the merge before confirming; existing entries are unchanged."
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (session === opened) message = "Backup operation did not finish. Check the passphrase, selected file, available storage and session timeout. Existing vault entries are unchanged; an incomplete export may need to be recreated."
            } finally { passphrase.fill('\u0000'); if (session === opened) transferBusy = false }
        }.also { job -> job.invokeOnCompletion { passphrase.fill('\u0000') } }
    }

    @Composable
    private fun EntryEditor(entry: VaultEntry, onCancel: () -> Unit, onSave: (VaultEntry) -> Unit) {
        var label by remember { mutableStateOf(entry.label) }
        var username by remember { mutableStateOf(entry.username) }
        var password by remember { mutableStateOf(entry.password) }
        var website by remember { mutableStateOf(entry.website) }
        var target by remember { mutableStateOf(entry.origin?.packageName.orEmpty()) }
        var holder by remember { mutableStateOf(entry.cardholder) }
        var number by remember { mutableStateOf(entry.cardNumber) }
        var month by remember { mutableStateOf(entry.expiryMonth) }
        var year by remember { mutableStateOf(entry.expiryYear) }
        var error by remember { mutableStateOf("") }
        var chooseTarget by remember { mutableStateOf(false) }
        var targetQuery by remember { mutableStateOf("") }
        val installedApps = remember { runCatching { VaultTargets.installedApps(this) }.getOrDefault(emptyList()) }
        com.example.ui.PrivateTextField("Label", label, { label = it }, limit = 200)
        if (entry.kind == VaultKind.LOGIN) {
            com.example.ui.PrivateTextField("Username", username, { username = it }, limit = 1000)
            com.example.ui.PrivateTextField("Password", password, { password = it }, secret = true, limit = 4096)
            TextButton(onClick = {
                val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!@#$%&*-_=+"
                val random = SecureRandom()
                password = buildString { repeat(24) { append(alphabet[random.nextInt(alphabet.length)]) } }
            }) { Text("Generate 24-character password") }
        } else {
            com.example.ui.PrivateTextField("Cardholder", holder, { holder = it }, limit = 200)
            com.example.ui.PrivateTextField("Card number", number, { number = it.filter(Char::isDigit) }, secret = true, limit = 23)
            com.example.ui.PrivateTextField("Expiry month (1–12)", month, { month = it.filter(Char::isDigit) }, limit = 2)
            com.example.ui.PrivateTextField("Expiry year (YYYY)", year, { year = it.filter(Char::isDigit) }, limit = 4)
            Text("CVV is never collected or stored. This does not provision a payment method in Android.")
        }
        com.example.ui.PrivateTextField("Exact HTTPS origin (blank for native app)", website, { website = it }, limit = 300)
        OutlinedButton(onClick = { chooseTarget = true }) { Text(installedApps.firstOrNull { it.packageName == target }?.let { "Target: ${it.label}" } ?: "Choose target app or browser") }
        com.example.ui.PrivateTextField("Target package (expert; optional)", target, { target = it }, limit = 200)
        Text("Example package: com.android.chrome. An empty target keeps the entry vault-only. Website entries require you to trust the target browser. A signing-key change requires an explicit new binding.", style = MaterialTheme.typography.bodySmall)
        if (chooseTarget) AlertDialog(onDismissRequest = { chooseTarget = false }, title = { Text("Choose installed app / browser") },
            text = { Column {
                OutlinedTextField(value = targetQuery, onValueChange = { targetQuery = it }, label = { Text("Search apps") }, singleLine = true)
                Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
                    installedApps.filter { it.label.contains(targetQuery, true) || it.packageName.contains(targetQuery, true) }.forEach { app ->
                        TextButton(onClick = { target = app.packageName; chooseTarget = false }) { Column {
                            Text(app.label); Text(app.packageName, style = MaterialTheme.typography.bodySmall)
                        } }
                    }
                }
            } }, confirmButton = { TextButton(onClick = { target = ""; chooseTarget = false }) { Text("Vault only") } },
            dismissButton = { TextButton(onClick = { chooseTarget = false }) { Text("Cancel") } })
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        Row {
            Button(onClick = {
                try {
                    session?.checkActive() ?: error("Unlock again.")
                    val normalized = if (website.isBlank()) "" else requireNotNull(VaultOrigin.https(website)) { "Enter an exact HTTPS origin without a path." }
                    val origin = if (target.isNotBlank()) VaultTargets.origin(this@VaultActivity, target.trim(), normalized.takeIf(String::isNotBlank)) else null
                    val next = entry.copy(label = label, username = username, password = password, website = normalized,
                        origin = origin, cardholder = holder, cardNumber = number, expiryMonth = month, expiryYear = year)
                    next.validate()
                    onSave(next)
                } catch (_: Exception) { error = "Could not save. Check required fields, HTTPS origin, installed app package, card details and the unlock timeout." }
            }) { Text("Save") }
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
    }

    @Composable
    private fun FillChoices(request: VaultFillRequest) {
        Text("Fill only into:", style = MaterialTheme.typography.titleMedium)
        Text("${request.origin.packageName}\n${request.origin.httpsOrigin ?: "Native app"}")
        val matches = entries.filter { it.kind == request.kind && it.origin?.matches(request.origin) == true }
        if (matches.isEmpty()) Text("No entry matches this form type, app signer and origin. Open the vault to add an entry or explicitly bind an existing entry.")
        matches.forEach { entry ->
            Button(onClick = { fill(entry, request) }, modifier = Modifier.fillMaxWidth()) { Text("Use ${entry.label} · ${entry.summary}") }
        }
        if (request.kind == VaultKind.PAYMENT_CARD) Text("This fills card details only. It does not submit the form or authorize a payment; enter CVV yourself if the merchant requires it.")
        TextButton(onClick = { setResult(RESULT_CANCELED); finish() }) { Text("Cancel") }
    }

    @Suppress("DEPRECATION")
    private fun fill(entry: VaultEntry, request: VaultFillRequest) {
        if (Build.VERSION.SDK_INT < 26) return
        try {
            session?.checkActive() ?: error("Locked")
            check(SystemClock.elapsedRealtime() < request.expiresAt)
            check(!request.cancelled.get() && entry.kind == request.kind)
            check(entry.origin?.matches(request.origin) == true)
            // Re-check installed signers immediately before releasing the credential.
            check(request.origin.matches(VaultTargets.origin(this, request.origin.packageName, request.origin.httpsOrigin)))
            val presentation = RemoteViews(packageName, android.R.layout.simple_list_item_1).apply { setTextViewText(android.R.id.text1, entry.label) }
            val dataset = Dataset.Builder(presentation)
            request.fields.forEach { (field, id) -> dataset.setValue(id, AutofillValue.forText(when (field) {
                VaultField.USERNAME -> entry.username
                VaultField.PASSWORD -> entry.password
                VaultField.CARD_NUMBER -> entry.cardNumber
                VaultField.CARDHOLDER -> entry.cardholder
                VaultField.EXPIRY_MONTH -> entry.expiryMonth.padStart(2, '0')
                VaultField.EXPIRY_YEAR -> entry.expiryYear
            })) }
            val response = FillResponse.Builder().addDataset(dataset.build()).build()
            setResult(RESULT_OK, Intent().putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, response))
            lock()
            finish()
        } catch (_: Exception) { lock(); message = "Fill cancelled: the session expired or the target identity changed. Return to the form and retry." }
    }

    companion object { const val EXTRA_FILL_TOKEN = "vault.fill.token" }
}
