package com.example.ime

import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.TextView
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import com.example.ui.keyboard.KeyboardScreen
import com.example.util.AppLogger
import android.graphics.Color
import android.content.ClipboardManager
import android.content.Context
import com.example.ime.clipboard.ClipboardItem
import com.example.ime.clipboard.ClipboardRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ConfigurableIME : ComposeInputMethodService() {
    
    private var composeView: ComposeView? = null
    private val globalScaleState = androidx.compose.runtime.mutableStateOf(1.0f)
    private var clipboardManager: ClipboardManager? = null
    val clipboardRepository by lazy { ClipboardRepository(this) }
    private val serviceScope = CoroutineScope(Dispatchers.IO)

    private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
        clipboardManager?.primaryClip?.let { clip ->
            if (clip.itemCount > 0) {
                val item = clip.getItemAt(0)
                val text = item.text?.toString()
                if (!text.isNullOrBlank()) {
                    serviceScope.launch {
                        clipboardRepository.insert(ClipboardItem(type = "TEXT", content = text))
                    }
                } else if (item.uri != null) {
                    serviceScope.launch {
                        clipboardRepository.insert(ClipboardItem(type = "URI", content = item.uri.toString()))
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboardManager?.addPrimaryClipChangedListener(clipboardListener)
        
        serviceScope.launch {
            val sharedPrefs = getSharedPreferences(packageName + "_preferences", Context.MODE_PRIVATE)
            val retentionDays = sharedPrefs.getInt("clipboard_retention_days", 0)
            if (retentionDays > 0) {
                val timeLimit = System.currentTimeMillis() - (retentionDays * 24L * 60 * 60 * 1000)
                clipboardRepository.deleteOlderThan(timeLimit)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        clipboardManager?.removePrimaryClipChangedListener(clipboardListener)
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        if (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP) {
            globalScaleState.value = (globalScaleState.value + 0.05f).coerceIn(0.5f, 2.5f)
            getSharedPreferences(packageName + "_preferences", android.content.Context.MODE_PRIVATE).edit().putFloat("keyboard_scale", globalScaleState.value).apply()
            return true
        }
        if (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN) {
            globalScaleState.value = (globalScaleState.value - 0.05f).coerceIn(0.5f, 2.5f)
            getSharedPreferences(packageName + "_preferences", android.content.Context.MODE_PRIVATE).edit().putFloat("keyboard_scale", globalScaleState.value).apply()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: android.view.KeyEvent): Boolean {
        if (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP || keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN) {
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private var isFloating = false
    private var floatingBounds = android.graphics.Rect()

    override fun onComputeInsets(outInsets: Insets?) {
        super.onComputeInsets(outInsets)
        outInsets ?: return
        if (isFloating) {
            // When floating, let the underlying window handle touches for the whole screen except our popup
            outInsets.contentTopInsets = outInsets.visibleTopInsets
            outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_REGION
            outInsets.touchableRegion.set(floatingBounds)
        } else {
            outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_CONTENT
        }
    }

    override fun onCreateInputView(): View {
        AppLogger.d("IME_DEBUG", "onCreateInputView called")

        try {
            val container = object : FrameLayout(this) {
                override fun onAttachedToWindow() {
                    super.onAttachedToWindow()
                    val root = rootView
                    AppLogger.d("IME_DEBUG", "Container onAttachedToWindow. rootView: $root")
                    setupComposeViewTree(root)
                }
            }.apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
            }

            composeView = ComposeView(this).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
                setContent {
                    val context = androidx.compose.ui.platform.LocalContext.current
                    val sharedPrefs = context.getSharedPreferences(context.packageName + "_preferences", android.content.Context.MODE_PRIVATE)
                    
                    // Load globalScaleState from shared preferences if possible
                    androidx.compose.runtime.LaunchedEffect(Unit) {
                        globalScaleState.value = sharedPrefs.getFloat("keyboard_scale", 1.0f)
                    }

                    // Refreshing state correctly here based on runtime updates
                    val alphaValState = androidx.compose.runtime.remember { 
                        androidx.compose.runtime.mutableStateOf(if (sharedPrefs.getBoolean("alpha_channel", false)) 0.2f else 1.0f) 
                    }
                    val longPressDelayState = androidx.compose.runtime.remember { 
                        androidx.compose.runtime.mutableStateOf(sharedPrefs.getLong("long_press_delay", 400L)) 
                    }
                    val popupConfigState = androidx.compose.runtime.remember {
                        androidx.compose.runtime.mutableStateOf(parsePopupConfig(sharedPrefs.getString("popup_config_json", "") ?: ""))
                    }
                    val containerStyleState = androidx.compose.runtime.remember {
                        androidx.compose.runtime.mutableStateOf(sharedPrefs.getString("container_style", "Solid") ?: "Solid")
                    }
                    val smartPositioningState = androidx.compose.runtime.remember {
                        androidx.compose.runtime.mutableStateOf(sharedPrefs.getBoolean("smart_positioning", false))
                    }
                    val showSuggestionsState = androidx.compose.runtime.remember {
                        androidx.compose.runtime.mutableStateOf(sharedPrefs.getBoolean("show_suggestions", true))
                    }
                    val enabledLayoutsState = androidx.compose.runtime.remember {
                        androidx.compose.runtime.mutableStateOf(
                            try {
                                val jsonStr = sharedPrefs.getString("enabled_layouts", """["ALPHANUMERIC", "CONTROL_NUMERIC", "SHORTCUTS_FULL", "SHORTCUTS_LETTERS"]""") ?: """["ALPHANUMERIC", "CONTROL_NUMERIC", "SHORTCUTS_FULL", "SHORTCUTS_LETTERS"]"""
                                val array = org.json.JSONArray(jsonStr)
                                val list = mutableListOf<String>()
                                for (i in 0 until array.length()) {
                                    list.add(array.getString(i))
                                }
                                list
                            } catch (e: Exception) {
                                listOf("ALPHANUMERIC", "CONTROL_NUMERIC", "SHORTCUTS_FULL", "SHORTCUTS_LETTERS")
                            }
                        )
                    }
                    val swipeVerticalState = androidx.compose.runtime.remember {
                        androidx.compose.runtime.mutableStateOf(sharedPrefs.getString("swipe_vertical_action", "MAIN_LAYOUT") ?: "MAIN_LAYOUT")
                    }
                    val swipeHorizontalState = androidx.compose.runtime.remember {
                        androidx.compose.runtime.mutableStateOf(sharedPrefs.getString("swipe_horizontal_action", "SUB_LAYOUT") ?: "SUB_LAYOUT")
                    }
                    val swipeCircularState = androidx.compose.runtime.remember {
                        androidx.compose.runtime.mutableStateOf(sharedPrefs.getString("swipe_circular_action", "LANGUAGE") ?: "LANGUAGE")
                    }
                    
                    androidx.compose.runtime.LaunchedEffect(Unit) {
                        sharedPrefs.registerOnSharedPreferenceChangeListener { prefs, key ->
                            if (key == "alpha_channel") {
                                alphaValState.value = if (prefs.getBoolean("alpha_channel", false)) 0.2f else 1.0f
                            } else if (key == "long_press_delay") {
                                longPressDelayState.value = prefs.getLong("long_press_delay", 400L)
                            } else if (key == "popup_config_json") {
                                popupConfigState.value = parsePopupConfig(prefs.getString("popup_config_json", "") ?: "")
                            } else if (key == "container_style") {
                                containerStyleState.value = prefs.getString("container_style", "Solid") ?: "Solid"
                            } else if (key == "smart_positioning") {
                                smartPositioningState.value = prefs.getBoolean("smart_positioning", false)
                            } else if (key == "show_suggestions") {
                                showSuggestionsState.value = prefs.getBoolean("show_suggestions", true)
                            } else if (key == "enabled_layouts") {
                                try {
                                    val jsonStr = prefs.getString("enabled_layouts", """["ALPHANUMERIC", "CONTROL_NUMERIC", "SHORTCUTS_FULL", "SHORTCUTS_LETTERS"]""") ?: """["ALPHANUMERIC", "CONTROL_NUMERIC", "SHORTCUTS_FULL", "SHORTCUTS_LETTERS"]"""
                                    val array = org.json.JSONArray(jsonStr)
                                    val list = mutableListOf<String>()
                                    for (i in 0 until array.length()) {
                                        list.add(array.getString(i))
                                    }
                                    enabledLayoutsState.value = list
                                } catch (e: Exception) {}
                            } else if (key == "swipe_vertical_action") {
                                swipeVerticalState.value = prefs.getString(key, "MAIN_LAYOUT") ?: "MAIN_LAYOUT"
                            } else if (key == "swipe_horizontal_action") {
                                swipeHorizontalState.value = prefs.getString(key, "SUB_LAYOUT") ?: "SUB_LAYOUT"
                            } else if (key == "swipe_circular_action") {
                                swipeCircularState.value = prefs.getString(key, "LANGUAGE") ?: "LANGUAGE"
                            }
                        }
                    }

                    val currentViewConfig = androidx.compose.ui.platform.LocalViewConfiguration.current
                    val customViewConfig = androidx.compose.runtime.remember(currentViewConfig, longPressDelayState.value) {
                        object : androidx.compose.ui.platform.ViewConfiguration by currentViewConfig {
                            override val longPressTimeoutMillis: Long
                                get() = longPressDelayState.value
                        }
                    }

                    androidx.compose.runtime.CompositionLocalProvider(
                        com.example.ui.keyboard.LocalKeyboardAlpha provides alphaValState.value,
                        com.example.ui.keyboard.LocalLongPressDelay provides longPressDelayState.value,
                        com.example.ui.keyboard.LocalPopupConfig provides popupConfigState.value,
                        com.example.ui.keyboard.LocalContainerStyle provides containerStyleState.value,
                        com.example.ui.keyboard.LocalSmartPositioning provides smartPositioningState.value,
                        com.example.ui.keyboard.LocalShowSuggestions provides showSuggestionsState.value,
                        com.example.ui.keyboard.LocalEnabledLayouts provides enabledLayoutsState.value,
                        com.example.ui.keyboard.LocalKeyboardScale provides globalScaleState.value,
                        com.example.ui.keyboard.LocalSwipeVerticalAction provides swipeVerticalState.value,
                        com.example.ui.keyboard.LocalSwipeHorizontalAction provides swipeHorizontalState.value,
                        com.example.ui.keyboard.LocalSwipeCircularAction provides swipeCircularState.value,
                        androidx.compose.ui.platform.LocalViewConfiguration provides customViewConfig
                    ) {
                        androidx.compose.runtime.LaunchedEffect(Unit) {
                            AppLogger.d("IME_DEBUG", "Compose Content launched!")
                        }
                        KeyboardScreen(
                            onKeyPress = { key ->
                                commitText(key)
                            },
                            onBackspace = {
                                deleteLeftLength(1)
                            },
                            onAction = { action ->
                                performEnterAction()
                            },
                            onKeyEvent = { code, meta ->
                                sendKeyWithMeta(code, meta)
                                AppLogger.d("IME_DEBUG", "sendKeyWithMeta: $code meta: $meta")
                            },
                            onFloatingChanged = { floating, bounds ->
                                if (floatingBounds != bounds) {
                                    floatingBounds = bounds
                                    composeView?.requestLayout()
                                }
                                if (isFloating != floating) {
                                    isFloating = floating
                                    val container = composeView?.parent as? FrameLayout
                                    if (floating) {
                                        container?.layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
                                        composeView?.layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
                                    } else {
                                        container?.layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT)
                                        composeView?.layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT)
                                    }
                                    container?.requestLayout()
                                }
                            }
                        )
                    }
                }
            }
            
            container.addView(composeView!!)
            setupComposeViewTree(composeView!!)
            return container
        } catch (e: Throwable) {
            AppLogger.e("IME_ERROR", "Crash in onCreateInputView", e)
            return TextView(this).apply {
                text = "CRASH: ${e.message}"
                textSize = 20f
                setBackgroundColor(Color.RED)
                setTextColor(Color.WHITE)
            }
        }
    }

    private fun commitText(text: String) {
        AppLogger.d("IME_DEBUG", "commitText: $text")
        currentInputConnection?.commitText(text, 1)
    }

    private fun deleteLeftLength(length: Int) {
        AppLogger.d("IME_DEBUG", "delete left: $length")
        currentInputConnection?.deleteSurroundingText(length, 0)
    }
    
    private fun performEnterAction() {
        AppLogger.d("IME_DEBUG", "performEnterAction")
        val editorInfo = currentInputEditorInfo
        if (editorInfo != null && (editorInfo.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) == 0) {
            val action = editorInfo.imeOptions and EditorInfo.IME_MASK_ACTION
            if (action != EditorInfo.IME_ACTION_NONE) {
                currentInputConnection?.performEditorAction(action)
                return
            }
        }
        currentInputConnection?.commitText("\n", 1)
    }

    private fun sendKeyWithMeta(keyCode: Int, metaState: Int) {
        val ic = currentInputConnection ?: return
        val downTime = android.os.SystemClock.uptimeMillis()
        val eventTime = android.os.SystemClock.uptimeMillis()
        val downEvent = android.view.KeyEvent(downTime, eventTime, android.view.KeyEvent.ACTION_DOWN, keyCode, 0, metaState)
        val upEvent = android.view.KeyEvent(downTime, eventTime, android.view.KeyEvent.ACTION_UP, keyCode, 0, metaState)
        ic.sendKeyEvent(downEvent)
        ic.sendKeyEvent(upEvent)
    }
}

private fun parsePopupConfig(jsonString: String): Map<String, List<List<String>>> {
    val result = mutableMapOf<String, List<List<String>>>()
    if (jsonString.isBlank()) return result
    try {
        val jsonObj = org.json.JSONObject(jsonString)
        jsonObj.keys().forEach { key ->
            val outerArray = jsonObj.optJSONArray(key) ?: return@forEach
            val rows = mutableListOf<List<String>>()
            for (i in 0 until outerArray.length()) {
                val innerArray = outerArray.optJSONArray(i) ?: continue
                val row = mutableListOf<String>()
                for (j in 0 until innerArray.length()) {
                    row.add(innerArray.getString(j))
                }
                rows.add(row)
            }
            result[key] = rows
        }
    } catch (e: Exception) {
        AppLogger.e("IME_DEBUG", "Error parsing popup config", e)
    }
    return result
}
