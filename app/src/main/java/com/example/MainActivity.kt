package com.example

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.ui.theme.MyApplicationTheme
import com.example.util.AppLogger
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
  @OptIn(ExperimentalMaterial3Api::class)
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    AppLogger.init(applicationContext)
    enableEdgeToEdge()
    setContent {
      MyApplicationTheme {
        val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
        val scope = rememberCoroutineScope()
        var currentScreen by remember { mutableStateOf("Settings") }

        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                ModalDrawerSheet {
                    Text("Menu Boczne", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(16.dp))
                    HorizontalDivider()
                    NavigationDrawerItem(
                        label = { Text("Ustawienia Klawiatury") },
                        selected = currentScreen == "Settings",
                        onClick = {
                            currentScreen = "Settings"
                            scope.launch { drawerState.close() }
                        },
                        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                    )
                    NavigationDrawerItem(
                        label = { Text("Logi Systemowe (Live)") },
                        selected = currentScreen == "Logs",
                        onClick = {
                            currentScreen = "Logs"
                            scope.launch { drawerState.close() }
                        },
                        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                    )
                }
            }
        ) {
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                topBar = {
                    TopAppBar(
                        title = { Text(if (currentScreen == "Settings") "Ustawienia" else "Logi Systemowe") },
                        navigationIcon = {
                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                Icon(Icons.Default.Menu, contentDescription = "Menu")
                            }
                        }
                    )
                }
            ) { innerPadding ->
                if (currentScreen == "Settings") {
                    SettingsScreen(
                        modifier = Modifier.padding(innerPadding),
                        onEnableKeyboard = {
                            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
                        },
                        onSelectKeyboard = {
                            val inputMethodManager = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                            inputMethodManager.showInputMethodPicker()
                        }
                    )
                } else {
                    LogsScreen(modifier = Modifier.padding(innerPadding))
                }
            }
        }
      }
    }
  }
}

@Composable
fun LogsScreen(modifier: Modifier = Modifier) {
    LazyColumn(modifier = modifier.fillMaxSize().padding(16.dp)) {
        items(AppLogger.logs) { logMsg ->
            Text(logMsg, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 4.dp))
            HorizontalDivider()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(modifier: Modifier = Modifier, onEnableKeyboard: () -> Unit, onSelectKeyboard: () -> Unit) {
  val context = androidx.compose.ui.platform.LocalContext.current
  val sharedPreferences = remember { context.getSharedPreferences("keyboard_prefs", android.content.Context.MODE_PRIVATE) }
  
  val scrollState = rememberScrollState()
  var clipboardStorageSize by remember { mutableFloatStateOf(sharedPreferences.getFloat("clipboard_limit", 1024f)) }
  var enableProbabilisticEngine by remember { mutableStateOf(sharedPreferences.getBoolean("probabilistic_autocorrect", true)) }
  var enableAlphaChannel by remember { mutableStateOf(sharedPreferences.getBoolean("alpha_channel", false)) }

  var groqApiKey by remember { mutableStateOf(sharedPreferences.getString("groq_api_key", "") ?: "") }
  var ttsEndpoint by remember { mutableStateOf(sharedPreferences.getString("google_tts_api", "") ?: "") }
  var asrEndpoint by remember { mutableStateOf(sharedPreferences.getString("asr_api_endpoint", "") ?: "") }
  var ocrEndpoint by remember { mutableStateOf(sharedPreferences.getString("ocr_api_endpoint", "") ?: "") }
  var clipboardStorageSizeStr by remember { mutableStateOf(sharedPreferences.getFloat("clipboard_limit", 1024f).toString()) }

  Column(modifier = modifier.fillMaxSize().padding(16.dp).verticalScroll(scrollState)) {
    Text("Custom AI Keyboard Settings", style = MaterialTheme.typography.headlineMedium)
    Spacer(modifier = Modifier.height(16.dp))

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Setup Instructions", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = onEnableKeyboard, modifier = Modifier.fillMaxWidth()) {
                Text("1. Enable Keyboard in System Settings")
            }
             Spacer(modifier = Modifier.height(8.dp))
             Button(onClick = onSelectKeyboard, modifier = Modifier.fillMaxWidth()) {
                Text("2. Select Custom Keyboard as Default")
            }
            Spacer(modifier = Modifier.height(16.dp))
            var testText by remember { mutableStateOf("") }
            OutlinedTextField(
                value = testText,
                onValueChange = { testText = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("3. Input field for testing") }
            )
        }
    }

    Spacer(modifier = Modifier.height(16.dp))

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("API Configurations", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = groqApiKey,
                onValueChange = { 
                    groqApiKey = it
                    sharedPreferences.edit().putString("groq_api_key", it).apply() 
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Groq API Key") }
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = ttsEndpoint,
                onValueChange = { 
                    ttsEndpoint = it
                    sharedPreferences.edit().putString("google_tts_api", it).apply() 
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Google Text-to-Speech API Endpoint") }
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = asrEndpoint,
                onValueChange = { 
                    asrEndpoint = it
                    sharedPreferences.edit().putString("asr_api_endpoint", it).apply() 
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("ASR API Endpoint") }
            )
            Spacer(modifier = Modifier.height(8.dp))
             OutlinedTextField(
                value = ocrEndpoint,
                onValueChange = { 
                    ocrEndpoint = it
                    sharedPreferences.edit().putString("ocr_api_endpoint", it).apply() 
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("OCR API Endpoint") }
            )
        }
    }

    Spacer(modifier = Modifier.height(16.dp))

    Text("Advanced Parameters", style = MaterialTheme.typography.titleLarge)
    Spacer(modifier = Modifier.height(8.dp))

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            ListItem(
                headlineContent = { Text("Probabilistic Autocorrect Seed") },
                supportingContent = { Text("Use local context for next-word generation") },
                trailingContent = { Switch(checked = enableProbabilisticEngine, onCheckedChange = { 
                    enableProbabilisticEngine = it
                    sharedPreferences.edit().putBoolean("probabilistic_autocorrect", it).apply()
                 }) }
            )
            HorizontalDivider()
            ListItem(
                headlineContent = { Text("Bypass Graphical Layout") },
                supportingContent = { Text("Insert alpha channel over keys") },
                trailingContent = { Switch(checked = enableAlphaChannel, onCheckedChange = { 
                    enableAlphaChannel = it
                    sharedPreferences.edit().putBoolean("alpha_channel", it).apply()
                 }) }
            )
            HorizontalDivider()
            
            var containerStyle by remember { mutableStateOf(sharedPreferences.getString("container_style", "Solid") ?: "Solid") }
            var smartPositioning by remember { mutableStateOf(sharedPreferences.getBoolean("smart_positioning", false)) }
            var showSuggestionsBar by remember { mutableStateOf(sharedPreferences.getBoolean("show_suggestions", true)) }
            
            Text("Container Style:", modifier = Modifier.padding(top = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Solid", "Transparent", "Gradient").forEach { style ->
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        RadioButton(selected = containerStyle == style, onClick = { 
                            containerStyle = style
                            sharedPreferences.edit().putString("container_style", style).apply()
                        })
                        Text(style)
                    }
                }
            }
            HorizontalDivider()
            
            ListItem(
                headlineContent = { Text("Pilnuj edytowanego pola") },
                supportingContent = { Text("Smart Positioning: intelligently move or zoom (mocked)") },
                trailingContent = { Switch(checked = smartPositioning, onCheckedChange = { 
                    smartPositioning = it
                    sharedPreferences.edit().putBoolean("smart_positioning", it).apply()
                 }) }
            )
            HorizontalDivider()

            ListItem(
                headlineContent = { Text("Pasek Podpowiedzi") },
                supportingContent = { Text("Show suggestions bar") },
                trailingContent = { Switch(checked = showSuggestionsBar, onCheckedChange = { 
                    showSuggestionsBar = it
                    sharedPreferences.edit().putBoolean("show_suggestions", it).apply()
                 }) }
            )
            HorizontalDivider()
            
            var layoutList by remember {
                mutableStateOf(
                    try {
                        val str = sharedPreferences.getString("enabled_layouts", """["ALPHANUMERIC", "CONTROL_NUMERIC", "SHORTCUTS_FULL", "SHORTCUTS_LETTERS"]""") ?: ""
                        val array = org.json.JSONArray(str)
                        List(array.length()) { array.getString(it) }
                    } catch (e: Exception) {
                        listOf("ALPHANUMERIC", "CONTROL_NUMERIC", "SHORTCUTS_FULL", "SHORTCUTS_LETTERS")
                    }
                )
            }
            
            val allAvailableLayouts = listOf("ALPHANUMERIC", "CONTROL_NUMERIC", "SHORTCUTS_FULL", "SHORTCUTS_LETTERS")
            
            Text("Edytor Układów (kolejność przełączania)", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(vertical = 8.dp))
            allAvailableLayouts.forEach { layoutName ->
                val isEnabled = layoutList.contains(layoutName)
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Checkbox(checked = isEnabled, onCheckedChange = { checked ->
                        val newList = layoutList.toMutableList()
                        if (checked) {
                            newList.add(layoutName)
                        } else {
                            newList.remove(layoutName)
                        }
                        layoutList = newList
                        sharedPreferences.edit().putString("enabled_layouts", org.json.JSONArray(newList).toString()).apply()
                    })
                    Text(layoutName, modifier = Modifier.weight(1f))
                    if (isEnabled) {
                        val idx = layoutList.indexOf(layoutName)
                        IconButton(onClick = {
                            if (idx > 0) {
                                val newList = layoutList.toMutableList()
                                val temp = newList[idx - 1]
                                newList[idx - 1] = newList[idx]
                                newList[idx] = temp
                                layoutList = newList
                                sharedPreferences.edit().putString("enabled_layouts", org.json.JSONArray(newList).toString()).apply()
                            }
                        }) {
                            Text("↑")
                        }
                        IconButton(onClick = {
                            if (idx < layoutList.size - 1) {
                                val newList = layoutList.toMutableList()
                                val temp = newList[idx + 1]
                                newList[idx + 1] = newList[idx]
                                newList[idx] = temp
                                layoutList = newList
                                sharedPreferences.edit().putString("enabled_layouts", org.json.JSONArray(newList).toString()).apply()
                            }
                        }) {
                            Text("↓")
                        }
                    }
                }
            }
            HorizontalDivider()

            val swipeOptions = listOf(
                "NONE" to "Brak",
                "MAIN_LAYOUT" to "Zmień układ główny",
                "SUB_LAYOUT" to "Zmień układ podrzędny",
                "ALPHABET" to "Zmień poziom alfabetu",
                "LANGUAGE" to "Zmień język"
            )

            var swipeVertical by remember { mutableStateOf(sharedPreferences.getString("swipe_vertical_action", "MAIN_LAYOUT") ?: "MAIN_LAYOUT") }
            var swipeHorizontal by remember { mutableStateOf(sharedPreferences.getString("swipe_horizontal_action", "SUB_LAYOUT") ?: "SUB_LAYOUT") }
            var swipeCircular by remember { mutableStateOf(sharedPreferences.getString("swipe_circular_action", "LANGUAGE") ?: "LANGUAGE") }

            Text("Gesty (Swipe)", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
            
            ListItem(
                headlineContent = { Text("Pionowy (Vertical)") },
                supportingContent = { 
                    var expanded by remember { mutableStateOf(false) }
                    Box {
                        TextButton(onClick = { expanded = true }) {
                            Text(swipeOptions.find { it.first == swipeVertical }?.second ?: swipeVertical)
                        }
                        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            swipeOptions.forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(option.second) },
                                    onClick = {
                                        swipeVertical = option.first
                                        sharedPreferences.edit().putString("swipe_vertical_action", option.first).apply()
                                        expanded = false
                                    }
                                )
                            }
                        }
                    }
                }
            )

            ListItem(
                headlineContent = { Text("Poziomy (Horizontal)") },
                supportingContent = { 
                    var expanded by remember { mutableStateOf(false) }
                    Box {
                        TextButton(onClick = { expanded = true }) {
                            Text(swipeOptions.find { it.first == swipeHorizontal }?.second ?: swipeHorizontal)
                        }
                        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            swipeOptions.forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(option.second) },
                                    onClick = {
                                        swipeHorizontal = option.first
                                        sharedPreferences.edit().putString("swipe_horizontal_action", option.first).apply()
                                        expanded = false
                                    }
                                )
                            }
                        }
                    }
                }
            )

            ListItem(
                headlineContent = { Text("W Kółko (Circular)") },
                supportingContent = { 
                    var expanded by remember { mutableStateOf(false) }
                    Box {
                        TextButton(onClick = { expanded = true }) {
                            Text(swipeOptions.find { it.first == swipeCircular }?.second ?: swipeCircular)
                        }
                        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            swipeOptions.forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(option.second) },
                                    onClick = {
                                        swipeCircular = option.first
                                        sharedPreferences.edit().putString("swipe_circular_action", option.first).apply()
                                        expanded = false
                                    }
                                )
                            }
                        }
                    }
                }
            )
            HorizontalDivider()

            var showLayoutEditorMsg by remember { mutableStateOf(false) }
            var zipProcessResult by remember { mutableStateOf<String?>(null) }
            
            HorizontalDivider()
            var clipboardRetention by remember { mutableStateOf(sharedPreferences.getInt("clipboard_retention_days", 0)) }
            Text("Automatyczne czyszczenie schowka", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
            ListItem(
                headlineContent = { Text("Czas przechowywania") },
                supportingContent = {
                    var expanded by remember { mutableStateOf(false) }
                    val options = listOf(0 to "Nigdy", 1 to "1 dzień", 7 to "7 dni", 30 to "30 dni")
                    Box {
                        TextButton(onClick = { expanded = true }) {
                            Text(options.find { it.first == clipboardRetention }?.second ?: "Nigdy")
                        }
                        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            options.forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(option.second) },
                                    onClick = {
                                        clipboardRetention = option.first
                                        sharedPreferences.edit().putInt("clipboard_retention_days", option.first).apply()
                                        expanded = false
                                    }
                                )
                            }
                        }
                    }
                }
            )
            HorizontalDivider()
            
            val zipLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                androidx.activity.result.contract.ActivityResultContracts.GetContent()
            ) { uri ->
                if (uri != null) {
                    zipProcessResult = "Przetwarzanie..."
                    // Uruchamiamy w tle
                    kotlin.concurrent.thread {
                        com.example.ZipProcessor.processZip(context, uri) { result ->
                            zipProcessResult = result
                        }
                    }
                }
            }
            
            Button(onClick = { zipLauncher.launch("application/zip") }, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Text("Wgraj paczkę ZIP z układami i mapami czułości")
            }
            zipProcessResult?.let {
                Text(it, color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 8.dp))
            }
            
            var recognizeResult by remember { mutableStateOf<String?>(null) }
            val scope = rememberCoroutineScope()
            val imagePickerLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                androidx.activity.result.contract.ActivityResultContracts.GetContent()
            ) { uri ->
                if (uri != null) {
                    recognizeResult = "Przetwarzanie obrazu przez Gemini API..."
                    scope.launch {
                        try {
                            val inputStream = context.contentResolver.openInputStream(uri)
                            val bitmap = android.graphics.BitmapFactory.decodeStream(inputStream)
                            inputStream?.close()
                            if (bitmap != null) {
                                val result = com.example.GeminiLayoutRecognizer.recognizeLayout(bitmap)
                                if (result != null) {
                                    val parsedLayout = com.example.domain.parser.LayoutParser.parseGeminiResponse(result)
                                    if (parsedLayout != null) {
                                        recognizeResult = "Udało się zmapować układ!\nLiczba paneli: ${parsedLayout.panels.size}\n\nSurowy JSON:\n$result"
                                    } else {
                                        recognizeResult = "Zwrócono wynik, ale parsowanie zawiodło:\n$result"
                                    }
                                } else {
                                    recognizeResult = "Zwrócono pusty wynik z Gemini."
                                }
                            } else {
                                recognizeResult = "Błąd: nie można załadować obrazu."
                            }
                        } catch (e: Exception) {
                            recognizeResult = "Błąd: ${e.message}"
                        }
                    }
                }
            }

            Button(onClick = { imagePickerLauncher.launch("image/*") }, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Text("Inteligentne rozpoznawanie układu z obrazu (Gemini API)")
            }
            if (recognizeResult != null) {
                OutlinedTextField(
                    value = recognizeResult ?: "",
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Wynik JSON z Gemini") },
                    modifier = Modifier.fillMaxWidth().height(250.dp).padding(vertical = 8.dp)
                )
            } else {
                Text("Aby skorzystać z inteligentnego rozpoznawania układu na podstawie zrzutów ekranu, podaj klucz w panelu Secrets.", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
            }
            HorizontalDivider()

            var longPressDelayStr by remember { mutableStateOf(sharedPreferences.getLong("long_press_delay", 400).toString()) }
            OutlinedTextField(
                value = longPressDelayStr,
                onValueChange = { 
                    longPressDelayStr = it
                    it.toLongOrNull()?.let { delay -> 
                        sharedPreferences.edit().putLong("long_press_delay", delay).apply()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Long Press Delay (ms)") }
            )
            Spacer(modifier = Modifier.height(16.dp))
            var popupConfigJsonStr by remember { mutableStateOf(sharedPreferences.getString("popup_config_json", "{\n  \"e\": [[\"ę\", \"ë\", \"é\"], [\"€\", \"¢\"]],\n  \"a\": [[\"ą\", \"á\", \"à\"], [\"@\"]],\n  \"c\": [[\"ć\", \"ç\"]],\n  \"n\": [[\"ń\", \"ñ\"]],\n  \"o\": [[\"ó\", \"ö\"]],\n  \"s\": [[\"ś\", \"ß\"]],\n  \"z\": [[\"ż\", \"ź\"]],\n  \"l\": [[\"ł\", \"£\", \"l\"], [\"Ł\", \"L\"]]\n}") ?: "") }
            OutlinedTextField(
                value = popupConfigJsonStr,
                onValueChange = { 
                    popupConfigJsonStr = it
                    sharedPreferences.edit().putString("popup_config_json", it).apply()
                },
                modifier = Modifier.fillMaxWidth().height(250.dp),
                label = { Text("Variants Configuration (JSON mapping)") },
                maxLines = 15
            )
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedTextField(
                value = clipboardStorageSizeStr,
                onValueChange = { 
                    clipboardStorageSizeStr = it
                    it.toFloatOrNull()?.let { limit -> 
                        sharedPreferences.edit().putFloat("clipboard_limit", limit).apply()
                    }
                },
                label = { Text("Clipboard Storage Limit (MB) - Free Input") },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
    }
  }
}
