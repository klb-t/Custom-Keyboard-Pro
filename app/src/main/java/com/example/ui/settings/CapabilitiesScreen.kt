package com.example.ui.settings

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.MainActivity
import com.example.core.caps.Abilities
import com.example.core.caps.Availability
import com.example.core.caps.Need

/** The existing ability registry, including real fallbacks, rendered in one place. */
@Composable
fun CapabilitiesScreen(onNavigate: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var revision by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { revision++ }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) revision++ }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    fun launch(intent: Intent) {
        runCatching { context.startActivity(intent) }.onFailure { error = "This phone has no screen for that access: ${it.javaClass.simpleName}" }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
        SettingsSection("Phone tools", "Each capability asks for access when you choose to use it.") {
            ActionRow("Files & cloud media", "Select folders or files from installed providers",
                onClick = { onNavigate(MainActivity.ROUTE_MEDIA) })
            ActionRow("Passwords & cards", "Unlock the local vault and configure system autofill",
                onClick = { onNavigate(MainActivity.ROUTE_VAULT) })
        }
        error?.let { Text(it, Modifier.padding(16.dp)) }
        Abilities.ALL.forEach { ability ->
            val status = remember(ability.id, revision) { Abilities.availability(context, ability) }
            SettingsSection(ability.label, when (status) {
                Availability.ON -> "Ready"
                Availability.OFF -> "Access needed"
                Availability.ABSENT -> "Not implemented in this build"
            }) {
                InfoRow(ability.gives)
                InfoRow("Without access: ${ability.without}")
                if (ability.built) {
                    val options = (ability.needs as? Need.AnyOf)?.options ?: listOf(ability.needs)
                    options.forEach { need ->
                        when (need) {
                            is Need.Permission -> if (status != Availability.ON) ActionRow("Grant permission", need.name,
                                onClick = { permission.launch(need.name) })
                            is Need.SpecialAccess -> ActionRow("Open access settings", need.where, onClick = {
                                Abilities.settingsIntent(context, ability.copy(needs = need))?.let(::launch)
                            })
                            else -> Unit
                        }
                    }
                    val query = when (ability.id) {
                        Abilities.POCKET_LOCK -> "pocket"
                        Abilities.READ_ALOUD -> "tts"
                        Abilities.DICTATION -> "asr"
                        Abilities.POINTER -> "pointer"
                        else -> "engine"
                    }
                    ActionRow("Configure", "Open matching expert settings", onClick = {
                        SettingsNavigation.open(context, "all", query)
                    })
                }
            }
        }
    }
}
