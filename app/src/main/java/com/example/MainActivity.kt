package com.example

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.example.core.config.SettingsStore
import com.example.core.layout.LayoutRepository
import com.example.ui.settings.AboutScreen
import com.example.ui.settings.AiSettingsScreen
import com.example.ui.settings.AppearanceScreen
import com.example.ui.settings.DiagnosticsScreen
import com.example.ui.settings.DictionaryScreen
import com.example.ui.settings.HomeScreen
import com.example.ui.settings.LayoutStudioScreen
import com.example.ui.settings.TypingSettingsScreen
import com.example.ui.settings.VoiceSettingsScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.util.AppLogger

/**
 * The settings app.
 *
 * It is the keyboard's only configuration surface, and it is also where the keyboard
 * sends the user when it needs something it cannot do itself — granting the microphone
 * permission, or editing a layout.
 */
class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_ROUTE = "route"
        const val ROUTE_HOME = "home"
        const val ROUTE_APPEARANCE = "appearance"
        const val ROUTE_TYPING = "typing"
        const val ROUTE_LAYOUTS = "layouts"
        const val ROUTE_AI = "ai"
        const val ROUTE_VOICE = "voice"
        const val ROUTE_DICTIONARY = "dictionary"
        const val ROUTE_ABOUT = "about"
        const val ROUTE_DIAGNOSTICS = "diagnostics"
        const val ROUTE_PERMISSIONS = "permissions"
    }

    private var micPermissionGranted by mutableStateOf(false)

    private val requestMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        micPermissionGranted = granted
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLogger.init(applicationContext)
        SettingsStore.init(this)
        LayoutRepository.init(this)
        enableEdgeToEdge()

        setContent {
            val settings by SettingsStore.state.collectAsState()
            var route by remember { mutableStateOf(intent?.getStringExtra(EXTRA_ROUTE) ?: ROUTE_HOME) }

            MyApplicationTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    topBar = {
                        TopAppBar(
                            title = { Text(titleFor(route)) },
                            navigationIcon = {
                                if (route != ROUTE_HOME) {
                                    IconButton(onClick = { route = ROUTE_HOME }) {
                                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                                    }
                                }
                            }
                        )
                    }
                ) { inner ->
                    Box(Modifier.fillMaxSize().padding(inner)) {
                        when (route) {
                            ROUTE_APPEARANCE -> AppearanceScreen(settings)
                            ROUTE_TYPING -> TypingSettingsScreen(settings)
                            ROUTE_LAYOUTS -> LayoutStudioScreen(settings)
                            ROUTE_AI -> AiSettingsScreen(settings)
                            ROUTE_VOICE, ROUTE_PERMISSIONS -> VoiceSettingsScreen(
                                settings = settings,
                                micGranted = micPermissionGranted,
                                onRequestMic = { requestMic.launch(Manifest.permission.RECORD_AUDIO) }
                            )
                            ROUTE_DICTIONARY -> DictionaryScreen()
                            ROUTE_ABOUT -> AboutScreen()
                            ROUTE_DIAGNOSTICS -> DiagnosticsScreen()
                            else -> HomeScreen(
                                settings = settings,
                                onNavigate = { route = it },
                                onEnableKeyboard = { openImeSettings() },
                                onChooseKeyboard = { showImePicker() }
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        micPermissionGranted = androidx.core.content.ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        // A layout may have been added or edited by another part of the app.
        LayoutRepository.reload()
    }

    private fun openImeSettings() {
        try {
            startActivity(Intent(AndroidSettings.ACTION_INPUT_METHOD_SETTINGS))
        } catch (e: Exception) {
            AppLogger.e("Settings", "Could not open input method settings", e)
        }
    }

    private fun showImePicker() {
        try {
            (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)?.showInputMethodPicker()
        } catch (e: Exception) {
            AppLogger.e("Settings", "Could not open the input method picker", e)
        }
    }
}

@Composable
private fun titleFor(route: String): String = when (route) {
    MainActivity.ROUTE_APPEARANCE -> "Size, shape & theme"
    MainActivity.ROUTE_TYPING -> "Typing"
    MainActivity.ROUTE_LAYOUTS -> "Layouts"
    MainActivity.ROUTE_AI -> "AI"
    MainActivity.ROUTE_VOICE, MainActivity.ROUTE_PERMISSIONS -> "Dictation"
    MainActivity.ROUTE_DICTIONARY -> "Dictionary & shortcuts"
    MainActivity.ROUTE_ABOUT -> "About & help"
    MainActivity.ROUTE_DIAGNOSTICS -> "Diagnostics"
    else -> "Custom Keyboard Pro"
}
