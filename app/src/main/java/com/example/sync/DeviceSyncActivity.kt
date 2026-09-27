package com.example.sync

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.example.MainActivity
import com.example.core.config.SettingsStore
import com.example.core.sync.*
import com.example.ui.PrivateTextField
import com.example.ui.theme.MyApplicationTheme
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.*
import java.security.MessageDigest

/** Explicit sync session: tokens and the encryption passphrase never enter saved state. */
class DeviceSyncActivity : ComponentActivity() {
    private enum class Destination { DRIVE, EXPORT, IMPORT }
    private var destination by mutableStateOf(Destination.DRIVE)
    private var passphrase by mutableStateOf("")
    private var status by mutableStateOf("Choose how to share data. Nothing is sent until you review and confirm.")
    private var busy by mutableStateOf(false)
    private var account by mutableStateOf<DriveSyncTransport.Account?>(null)
    private var fileUri by mutableStateOf<Uri?>(null)
    private var plan by mutableStateOf<SyncRepository.Plan?>(null)
    private var transport: DriveSyncTransport? = null
    private var files: List<DriveSyncTransport.RemoteFile> = emptyList()
    private var task: Job? = null
    private var generation = 0
    private var authorizationPending = false
    private val signingIdentity by lazy { signingFingerprint() }
    private val repository by lazy { SyncRepository(this) }
    private val exportPicker = registerForActivityResult(ActivityResultContracts.CreateDocument("application/jose")) { uri ->
        if (uri != null) { destination = Destination.EXPORT; fileUri = uri; status = "File selected. Enter your device-sync passphrase, then preview." }
    }
    private val importPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { destination = Destination.IMPORT; fileUri = uri; status = "Encrypted file selected. Enter its passphrase, then preview." }
    }
    private val authorization = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        authorizationPending = false
        if (result.resultCode != RESULT_OK) { busy = false; status = "Google connection cancelled. No data was sent." }
        else runCatching { Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(result.data) }
            .onSuccess(::authorized).onFailure { authorizationFailure() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SettingsStore.init(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (android.os.Build.VERSION.SDK_INT >= 26) window.decorView.importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        if (android.os.Build.VERSION.SDK_INT >= 30) window.decorView.importantForContentCapture = android.view.View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS
        setContent { MyApplicationTheme {
            val settings by SettingsStore.state.collectAsState()
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding()
                .verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Device sync", style = MaterialTheme.typography.headlineSmall)
                Text("Share clipboard history, screenshots and portable settings. The same sync passphrase is needed on each phone. This version runs when you ask; it does not upload in the background.")
                ScopeOption("Clipboard history", settings.syncClipboard) { SettingsStore.update { s -> s.copy(syncClipboard = it) }; plan = null }
                ScopeOption("Images / screenshots", settings.syncImages) { SettingsStore.update { s -> s.copy(syncImages = it) }; plan = null }
                ScopeOption("Portable settings", settings.syncSettings) { SettingsStore.update { s -> s.copy(syncSettings = it) }; plan = null }
                Text(if (settings.syncPropagatePruning) "Automatic age/capacity cleanup is shared with other devices." else "Automatic age/capacity cleanup stays local. Manual deletions synchronize even after Trash expires.", style = MaterialTheme.typography.bodySmall)
                Text("Vault entries, API keys, accounts, private commands and paths stay on this phone. Newly selected history: up to ${settings.syncMaxItems} recent entries; images up to ${settings.syncMaxImageMb} MiB each, within the 12 MiB snapshot limit. Previously shared entries and deletion markers remain in sync history.", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(enabled = !busy, onClick = {
                    startActivity(Intent(this@DeviceSyncActivity, MainActivity::class.java).putExtra(MainActivity.EXTRA_ROUTE, "all")
                        .putExtra(MainActivity.EXTRA_SETTINGS_QUERY, "Sync:"))
                }) { Text("Expert sync limits") }
                HorizontalDivider()
                Button(enabled = !busy, onClick = ::connect) { Text("Connect / change Google account") }
                account?.let { Text("Google Drive: ${it.email}\nOnly IO Matrix app data is requested. No IO Matrix server.") }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(enabled = !busy, onClick = { exportPicker.launch("io-matrix-sync.jwe") }) { Text("Export file") }
                    OutlinedButton(enabled = !busy, onClick = { importPicker.launch(arrayOf("*/*")) }) { Text("Import file") }
                }
                Text("Mode: ${destination.name.lowercase()}")
                PrivateTextField("Sync passphrase (12+ characters)", passphrase, { passphrase = it; plan = null }, secret = true, limit = 1024, enabled = !busy)
                Text("Keep this passphrase separately. Google cannot recover it. It is cleared when you leave this screen.", style = MaterialTheme.typography.bodySmall)
                Text(status)
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                Button(enabled = !busy && passphrase.length >= 12 && (destination != Destination.DRIVE || account != null) &&
                    (destination == Destination.DRIVE || fileUri != null), onClick = ::preview) { Text("Preview changes") }
                plan?.let { review ->
                    Card { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Shared snapshot: ${review.sharedClips} clipboard entries, ${review.sharedSettings} settings, ${review.sharedDeletions} retained deletion markers.")
                        Text("Incoming: ${review.changedClips} clipboard changes, ${review.deletions} deletions, ${review.changedSettings.size} setting changes.")
                        if (review.changedSettings.isNotEmpty()) Text(review.changedSettings.take(25).joinToString(", ") + if (review.changedSettings.size > 25) "…" else "")
                        Text("Deleted local entries go to Trash. Concurrent edits use version order; device ID breaks ties. Repeating a transfer merges by identity.", style = MaterialTheme.typography.bodySmall)
                        Button(enabled = !busy, onClick = ::apply) { Text(if (destination == Destination.DRIVE) "Apply and sync" else if (destination == Destination.EXPORT) "Write encrypted file" else "Apply merge") }
                        TextButton(enabled = !busy, onClick = { plan = null }) { Text("Cancel preview") }
                    } }
                }
                HorizontalDivider()
                Text("Google setup", style = MaterialTheme.typography.titleMedium)
                Text("If connection is unavailable: enable Google Drive API in one Google Cloud project, configure consent, add an Android OAuth client for this package and signing SHA-1, and add your account as a test user while the project is in testing. Every phone must use the same app/project. No server client secret is placed in the APK.", style = MaterialTheme.typography.bodySmall)
                Text("Package: $packageName\nSHA-1: ${signingIdentity}", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(enabled = !busy, onClick = { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://console.cloud.google.com/auth/clients"))) }) { Text("Open Google project setup") }
                TextButton(onClick = { finish() }) { Text("Close") }
            }
        } }
    }

    private fun connect() {
        clearSession()
        destination = Destination.DRIVE; fileUri = null; busy = true; authorizationPending = true
        status = "Choose the Google account used on your other devices."
        val request = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(DriveSyncTransport.SCOPE)))
            .setPrompt(AuthorizationRequest.Prompt.SELECT_ACCOUNT).build()
        Identity.getAuthorizationClient(this).authorize(request)
            .addOnSuccessListener { result ->
                if (isFinishing || isDestroyed) return@addOnSuccessListener
                if (result.hasResolution()) authorization.launch(IntentSenderRequest.Builder(result.pendingIntent!!.intentSender).build())
                else { authorizationPending = false; authorized(result) }
            }.addOnFailureListener { authorizationPending = false; authorizationFailure() }
    }
    private fun authorizationFailure() { busy = false; status = "Google authorization is unavailable. Check the setup information below, Google Play services and the selected account. No data was sent; encrypted file transfer also works without this integration." }
    private fun authorized(result: AuthorizationResult) {
        busy = true
        val token = result.accessToken
        if (token.isNullOrBlank() || DriveSyncTransport.SCOPE !in result.grantedScopes) { authorizationFailure(); return }
        transport = DriveSyncTransport(token)
        val active = transport!!; val epoch = generation
        task = lifecycleScope.launch {
            try {
                val selected = withContext(Dispatchers.IO) { active.account() }
                if (epoch == generation) { account = selected; status = "Connected. Enter the same encryption passphrase on all devices, then preview." }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (epoch == generation) status = e.message ?: "Cannot read the selected Google account." }
            finally { if (epoch == generation) busy = false }
        }
    }
    private fun preview() = operate { password ->
        val remote = when (destination) {
            Destination.DRIVE -> {
                val client = transport ?: error("Connect Google again")
                files = client.list()
                files.map { file ->
                    currentCoroutineContext().ensureActive()
                    SyncJson.open(client.download(file), password).also {
                        require(file.name == "io-matrix-sync-v1-${it.device}.jwe") { "Device snapshot identity does not match its file." }
                    }
                }
            }
            Destination.IMPORT -> listOf(SyncJson.open(contentResolver.openInputStream(fileUri!!)!!.use { SyncRepository.readBounded(it, SyncJson.MAX_BYTES) }, password))
            Destination.EXPORT -> emptyList()
        }
        val next = repository.prepare(remote, password)
        withContext(Dispatchers.Main) { plan = next; status = "Preview ready. No local settings or clipboard entries have changed." }
    }
    private fun apply() {
        val review = plan ?: return
        operate { password ->
            val bytes = repository.apply(review, password)
            when (destination) {
                Destination.DRIVE -> {
                    val bundle = SyncJson.open(bytes, password)
                    (transport ?: error("Google connection closed; local changes are saved, reconnect to upload.")).upload(bundle.device, bytes, files)
                }
                Destination.EXPORT -> contentResolver.openOutputStream(fileUri!!, "wt")!!.use { it.write(bytes) }
                Destination.IMPORT -> Unit
            }
            withContext(Dispatchers.Main) {
                plan = null
                status = when (destination) {
                    Destination.DRIVE -> "Merged and uploaded. Run sync on the other phone to receive these changes."
                    Destination.EXPORT -> "Encrypted file saved. Open it with Device sync on the other phone; export back to exchange later changes."
                    Destination.IMPORT -> "Merged locally. Export or run Google sync to share subsequent changes."
                }
            }
        }
    }
    private fun operate(action: suspend (CharArray) -> Unit) {
        if (busy) return
        val password = passphrase.toCharArray(); val epoch = generation
        busy = true; status = "Working…"
        task = lifecycleScope.launch {
            try { withContext(Dispatchers.IO) { action(password) } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (epoch == generation) { plan = null; status = "Sync stopped: ${e.message ?: "unreadable data or wrong passphrase"}. Existing remote files were preserved. If upload failed after applying, local changes remain saved; retry." } }
            finally { password.fill('\u0000'); if (epoch == generation) busy = false }
        }.also { it.invokeOnCompletion { password.fill('\u0000') } }
    }
    private fun clearSession() {
        generation++; task?.cancel(); task = null; transport?.close(); transport = null
        account = null; files = emptyList(); plan = null; passphrase = ""; busy = false
    }
    override fun onStop() { clearSession(); super.onStop() }
    private fun signingFingerprint(): String = runCatching {
        @Suppress("DEPRECATION")
        val signatures = packageManager.getPackageInfo(packageName, android.content.pm.PackageManager.GET_SIGNATURES).signatures
        signatures?.firstOrNull()?.let { signature -> MessageDigest.getInstance("SHA-1").digest(signature.toByteArray())
            .joinToString(":") { "%02X".format(it) } } ?: "Unavailable"
    }.getOrDefault("Unavailable")

    @Composable private fun ScopeOption(label: String, enabled: Boolean, changed: (Boolean) -> Unit) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Checkbox(checked = enabled, enabled = !busy, onCheckedChange = changed); Text(label)
        }
    }
}
