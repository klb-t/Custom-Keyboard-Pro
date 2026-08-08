package com.example.ui.keyboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.automirrored.filled.KeyboardReturn
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SpaceBar
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown

import androidx.compose.foundation.border
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.boundsInWindow
import kotlin.math.exp

enum class KeyboardLayout(val displayName: String) {
    ALPHANUMERIC("ABC"),
    CONTROL_NUMERIC("Num/Ctrl"),
    SHORTCUTS_FULL("Skróty (Pełne)"),
    SHORTCUTS_LETTERS("Skróty + Litery")
}

class HitmapManager {
    val keys = mutableMapOf<String, Rect>()
    val learnedOffsets = mutableMapOf<String, Offset>()
    
    var lastTapPoint: Offset? = null
    var lastTypedKey: String? = null
    var lastTypedTime: Long = 0L
    var inCorrectionMode = false

    fun registerKey(char: String, bounds: Rect) {
        keys[char] = bounds
    }

    fun handleBackspace() {
        if (System.currentTimeMillis() - lastTypedTime < 3000) {
            inCorrectionMode = true
        } else {
            inCorrectionMode = false
        }
    }

    fun handleTap(x: Float, y: Float): String? {
        var bestKey: String? = null
        var maxProb = -1.0
        
        for ((char, rect) in keys) {
            val offset = learnedOffsets[char] ?: Offset.Zero
            val cx = rect.center.x + offset.x
            val cy = rect.center.y + offset.y
            
            val dx = x - cx
            val dy = y - cy
            val distSq = dx*dx + dy*dy
            val prob = exp(-distSq.toDouble() / 15000.0) // sensitivity
            if (prob > maxProb) {
                maxProb = prob
                bestKey = char
            }
        }
        
        lastTapPoint = Offset(x, y)
        if (bestKey != null) {
            if (inCorrectionMode) {
                notifyCorrection(bestKey)
                inCorrectionMode = false
            }
            lastTypedKey = bestKey
            lastTypedTime = System.currentTimeMillis()
        }
        return bestKey
    }

    private fun notifyCorrection(correctedKey: String) {
        val targetPoint = lastTapPoint ?: return
        val rect = keys[correctedKey] ?: return
        val currentOffset = learnedOffsets[correctedKey] ?: Offset.Zero
        val cx = rect.center.x + currentOffset.x
        val cy = rect.center.y + currentOffset.y
        
        val dx = targetPoint.x - cx
        val dy = targetPoint.y - cy
        
        learnedOffsets[correctedKey] = Offset(
            currentOffset.x + dx * 0.1f, // Learn 10% towards the correction
            currentOffset.y + dy * 0.1f
        )
    }
}

val LocalHitmapManager = compositionLocalOf { HitmapManager() }

val LocalKeyboardScale = compositionLocalOf { 1.0f }

@Composable
fun FloatingContainer(
    modifier: Modifier = Modifier,
    isFloating: Boolean = false,
    onToggleFloating: () -> Unit,
    content: @Composable () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences(context.packageName + "_preferences", android.content.Context.MODE_PRIVATE) }
    
    val displayMetrics = context.resources.displayMetrics
    val screenWidth = (displayMetrics.widthPixels / displayMetrics.density)
    val screenHeight = (displayMetrics.heightPixels / displayMetrics.density)
    
    var offsetX by remember { mutableStateOf(prefs.getFloat("floating_offset_x", 0f).coerceIn(-screenWidth, screenWidth)) }
    var offsetY by remember { mutableStateOf(prefs.getFloat("floating_offset_y", 0f).coerceIn(-screenHeight, screenHeight)) }
    var width by remember { mutableStateOf(prefs.getFloat("floating_width", 350f).coerceIn(200f, screenWidth)) }
    var height by remember { mutableStateOf(prefs.getFloat("floating_height", 280f).coerceIn(150f, screenHeight)) }
    var dockHeight by remember { mutableStateOf(prefs.getFloat("dock_height", 280f).coerceIn(150f, screenHeight)) }

    val density = androidx.compose.ui.platform.LocalDensity.current.density
    val hitmapManager = LocalHitmapManager.current
    val keyboardScale = LocalKeyboardScale.current.coerceIn(0.5f, 2.5f)
    var containerRootPos by remember { mutableStateOf(Offset.Zero) }

    val trackPointerModifier = Modifier
        .onGloballyPositioned { containerRootPos = it.localToWindow(Offset.Zero) }
        .pointerInput(Unit) {
            kotlinx.coroutines.coroutineScope {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                        event.changes.firstOrNull()?.let { change ->
                            if (change.pressed) {
                                hitmapManager.lastTapPoint = change.position + containerRootPos
                            }
                        }
                    }
                }
            }
        }

    val containerStyle = LocalContainerStyle.current
    val smartPositioning = LocalSmartPositioning.current
    
    val bgModifier = when (containerStyle) {
        "Transparent" -> Modifier.background(Color.Transparent, RoundedCornerShape(12.dp))
        "Gradient" -> Modifier.background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color(0xFF2A2D34), Color(0xFF131417))), RoundedCornerShape(12.dp))
        else -> Modifier.background(Color(0xFF1E1E1E), RoundedCornerShape(12.dp))
    }

    LaunchedEffect(smartPositioning) {
        if (smartPositioning && isFloating) {
            // Intelligent move: mock jumping to avoid cursor
            offsetY = (offsetY - 50f).coerceIn(-screenHeight, screenHeight)
        }
    }

    if (!isFloating) {
        val baseBg = when (containerStyle) {
            "Transparent" -> Modifier.background(Color.Transparent)
            "Gradient" -> Modifier.background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color(0xFF2A2D34), Color(0xFF131417))))
            else -> modifier // fallback to passed modifier
        }
        Box(
            modifier = baseBg.fillMaxWidth().height((dockHeight * keyboardScale).dp).then(trackPointerModifier)
        ) {
            content()
            IconButton(
                onClick = onToggleFloating,
                modifier = Modifier.align(Alignment.TopEnd)
            ) {
                Icon(Icons.Default.Language, contentDescription = "Float", tint = Color.Gray)
            }
            // Resize handle for docked mode
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(24.dp)
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragEnd = { prefs.edit().putFloat("dock_height", dockHeight).apply() }
                        ) { change, dragAmount ->
                            change.consume()
                            dockHeight = (dockHeight + dragAmount.y / (density * keyboardScale)).coerceIn(150f, screenHeight)
                        }
                    }
            ) {
                Icon(Icons.Default.Settings, contentDescription = "Resize Height", tint = Color.DarkGray, modifier = Modifier.fillMaxSize().padding(4.dp))
            }
        }
    } else {
        Box(
            modifier = Modifier
                .offset(offsetX.dp, offsetY.dp)
                .size((width * keyboardScale).dp, (height * keyboardScale).dp)
                .then(bgModifier)
                .border(1.dp, Color.DarkGray, RoundedCornerShape(12.dp))
                .then(trackPointerModifier)
        ) {
            // Drag handle at the top
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(24.dp)
                    .background(Color.DarkGray, RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragEnd = { prefs.edit().putFloat("floating_offset_x", offsetX).putFloat("floating_offset_y", offsetY).apply() }
                        ) { change, dragAmount ->
                            change.consume()
                            offsetX += dragAmount.x / density
                            offsetY += dragAmount.y / density
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Text("Drag me", color = Color.LightGray, fontSize = 10.sp)
                IconButton(
                    onClick = onToggleFloating,
                    modifier = Modifier.align(Alignment.CenterEnd).size(24.dp)
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Dock", tint = Color.White)
                }
            }
            
            Box(modifier = Modifier.padding(top = 24.dp).fillMaxSize()) {
                content()
            }
            
            // Resize handle at bottom end
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(24.dp)
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragEnd = { prefs.edit().putFloat("floating_width", width).putFloat("floating_height", height).apply() }
                        ) { change, dragAmount ->
                            change.consume()
                            width = (width + dragAmount.x / (density * keyboardScale)).coerceIn(200f, screenWidth)
                            height = (height + dragAmount.y / (density * keyboardScale)).coerceIn(150f, screenHeight)
                        }
                    }
            ) {
                Icon(Icons.Default.Settings, contentDescription = "Resize", tint = Color.Gray, modifier = Modifier.fillMaxSize())
            }
        }
    }
}

val LocalKeyboardAlpha = compositionLocalOf { 1.0f }
val LocalLongPressDelay = compositionLocalOf { 400L }
val LocalPopupConfig = compositionLocalOf { emptyMap<String, List<List<String>>>() }
val LocalContainerStyle = compositionLocalOf { "Solid" }
val LocalSmartPositioning = compositionLocalOf { false }
val LocalShowSuggestions = compositionLocalOf { true }
val LocalEnabledLayouts = compositionLocalOf { listOf(KeyboardLayout.ALPHANUMERIC.name, KeyboardLayout.CONTROL_NUMERIC.name) }
val LocalSwipeVerticalAction = compositionLocalOf { "MAIN_LAYOUT" }
val LocalSwipeHorizontalAction = compositionLocalOf { "SUB_LAYOUT" }
val LocalSwipeCircularAction = compositionLocalOf { "LANGUAGE" }

@Composable
fun KeyboardScreen(
    onKeyPress: (String) -> Unit,
    onBackspace: () -> Unit,
    onAction: (String) -> Unit,
    onKeyEvent: (Int, Int) -> Unit,
    modifier: Modifier = Modifier,
    onFloatingChanged: (Boolean, android.graphics.Rect) -> Unit = { _, _ -> }
) {
    var currentLayout by remember { mutableStateOf(KeyboardLayout.ALPHANUMERIC) }
    var isShiftActive by remember { mutableStateOf(false) }
    var isAltGrActive by remember { mutableStateOf(false) } // For Pl Programmer alt characters
    var isFloating by remember { mutableStateOf(false) }
    var keyboardBounds by remember { mutableStateOf(android.graphics.Rect()) }
    
    val swipeVert = LocalSwipeVerticalAction.current
    val swipeHoriz = LocalSwipeHorizontalAction.current
    val swipeCirc = LocalSwipeCircularAction.current

    val enabledLayouts = LocalEnabledLayouts.current.mapNotNull { name ->
        try { KeyboardLayout.valueOf(name) } catch (e: Exception) { null }
    }

    val performSwipeAction = { action: String ->
        when (action) {
            "MAIN_LAYOUT" -> {
                val currentIndex = enabledLayouts.indexOf(currentLayout)
                val nextIndex = if (currentIndex != -1 && currentIndex + 1 < enabledLayouts.size) currentIndex + 1 else 0
                if (enabledLayouts.isNotEmpty()) currentLayout = enabledLayouts[nextIndex]
            }
            "SUB_LAYOUT" -> {
                val subLayouts = enabledLayouts.filter { it != KeyboardLayout.ALPHANUMERIC }
                val currentIndex = subLayouts.indexOf(currentLayout)
                val nextIndex = if (currentIndex != -1 && currentIndex + 1 < subLayouts.size) currentIndex + 1 else 0
                if (subLayouts.isNotEmpty()) currentLayout = subLayouts[nextIndex]
            }
            "ALPHABET" -> {
                if (enabledLayouts.contains(KeyboardLayout.ALPHANUMERIC)) {
                    currentLayout = KeyboardLayout.ALPHANUMERIC
                }
            }
            "LANGUAGE" -> { /* mock lang change */ }
        }
    }

    val gestureModifier = Modifier.pointerInput(swipeVert, swipeHoriz, swipeCirc) {
        awaitEachGesture {
            val downEvent = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val pointerId = downEvent.id
            val startPos = downEvent.position
            
            var minX = startPos.x
            var maxX = startPos.x
            var minY = startPos.y
            var maxY = startPos.y
            
            var consumedSwipe = false
            
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == pointerId }
                if (change != null) {
                    val currentPos = change.position
                    if (currentPos.x < minX) minX = currentPos.x
                    if (currentPos.x > maxX) maxX = currentPos.x
                    if (currentPos.y < minY) minY = currentPos.y
                    if (currentPos.y > maxY) maxY = currentPos.y
                    
                    if (!consumedSwipe) {
                        val dx = currentPos.x - startPos.x
                        val dy = currentPos.y - startPos.y
                        
                        val width = maxX - minX
                        val height = maxY - minY
                        
                        // Circular: bounding box is reasonably large and roughly square
                        if (width > 200f && height > 200f && (width / height) in 0.5f..2.0f) {
                            performSwipeAction(swipeCirc)
                            consumedSwipe = true
                            change.consume()
                        } else if (Math.abs(dx) > 150f && Math.abs(dy) < 100f) {
                            performSwipeAction(swipeHoriz)
                            consumedSwipe = true
                            change.consume()
                        } else if (Math.abs(dy) > 150f && Math.abs(dx) < 100f) {
                            performSwipeAction(swipeVert)
                            consumedSwipe = true
                            change.consume()
                        }
                    }
                }
            } while (event.changes.any { it.pressed })
        }
    }

    LaunchedEffect(isFloating, keyboardBounds) {
        onFloatingChanged(isFloating, keyboardBounds)
    }
    
    fun getNextLayout(): KeyboardLayout {
        if (enabledLayouts.isEmpty()) return KeyboardLayout.ALPHANUMERIC
        val idx = enabledLayouts.indexOf(currentLayout)
        if (idx == -1) return enabledLayouts[0]
        return enabledLayouts[(idx + 1) % enabledLayouts.size]
    }
    
    // Ensure current layout is valid
    LaunchedEffect(enabledLayouts) {
        if (enabledLayouts.isNotEmpty() && !enabledLayouts.contains(currentLayout)) {
            currentLayout = enabledLayouts[0]
        }
    }

    LaunchedEffect(Unit) {
        com.example.util.AppLogger.d("Compose", "KeyboardScreen composed")
    }

    var showClipboard by remember { mutableStateOf(false) }
    var editingClipboardItem by remember { mutableStateOf<com.example.ime.clipboard.ClipboardItem?>(null) }

    FloatingContainer(
        isFloating = isFloating,
        onToggleFloating = { isFloating = !isFloating },
        modifier = modifier
            .background(Color(0xFF202124).copy(alpha = LocalKeyboardAlpha.current))
            .onGloballyPositioned { coords ->
                val bounds = coords.boundsInWindow()
                keyboardBounds = android.graphics.Rect(
                    bounds.left.toInt(),
                    bounds.top.toInt(),
                    bounds.right.toInt(),
                    bounds.bottom.toInt()
                )
            }
            .then(gestureModifier)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                if (LocalShowSuggestions.current) {
                    Row(
                        modifier = Modifier.fillMaxWidth().height(40.dp).background(Color(0xFF2B2C30)),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Auto 1", color = Color.White, fontSize = 14.sp)
                        Text("Auto 2", color = Color.White, fontSize = 14.sp)
                        Text("Auto 3", color = Color.White, fontSize = 14.sp)
                        IconButton(onClick = { showClipboard = !showClipboard }) {
                            Icon(androidx.compose.material.icons.Icons.Default.Settings, contentDescription = "Schowek", tint = Color.White)
                        }
                    }
                }
                Box(modifier = Modifier.weight(1f)) {
                    if (showClipboard) {
                        ClipboardPanel(
                            onClose = { showClipboard = false },
                            onPaste = { content -> 
                                onKeyPress(content)
                                showClipboard = false
                            },
                            onEdit = { item ->
                                showClipboard = false
                                editingClipboardItem = item
                            }
                        )
                    } else {
                        when (currentLayout) {
                            KeyboardLayout.ALPHANUMERIC -> {
                                AlphanumericLayout(
                                    isShiftActive = isShiftActive,
                                    isAltGrActive = isAltGrActive,
                                    onKeyPress = onKeyPress,
                                    onBackspace = onBackspace,
                                    onAction = onAction,
                                    onShiftToggle = { isShiftActive = !isShiftActive },
                                    onAltGrToggle = { isAltGrActive = !isAltGrActive },
                                    onLayoutChange = { currentLayout = getNextLayout() }
                                )
                            }
                            KeyboardLayout.CONTROL_NUMERIC -> {
                                ControlNumericLayout(
                                    onKeyPress = onKeyPress,
                                    onLayoutChange = { currentLayout = getNextLayout() },
                                    onKeyEvent = onKeyEvent
                                )
                            }
                            KeyboardLayout.SHORTCUTS_FULL -> {
                                ShortcutsFullLayout(
                                    onLayoutChange = { currentLayout = getNextLayout() },
                                    onKeyEvent = onKeyEvent
                                )
                            }
                            KeyboardLayout.SHORTCUTS_LETTERS -> {
                                ShortcutsLettersLayout(
                                    onKeyPress = onKeyPress,
                                    onBackspace = onBackspace,
                                    onLayoutChange = { currentLayout = getNextLayout() },
                                    onKeyEvent = onKeyEvent
                                )
                            }
                        }
                    }
                }
            }
            
            editingClipboardItem?.let { item ->
                EditClipboardWindow(
                    item = item,
                    onClose = { editingClipboardItem = null }
                )
            }
        }
    }
}

@Composable
fun AlphanumericLayout(
    isShiftActive: Boolean,
    isAltGrActive: Boolean,
    onKeyPress: (String) -> Unit,
    onBackspace: () -> Unit,
    onAction: (String) -> Unit,
    onShiftToggle: () -> Unit,
    onAltGrToggle: () -> Unit,
    onLayoutChange: () -> Unit
) {
    val row1 = listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p")
    val row2 = listOf("a", "s", "d", "f", "g", "h", "j", "k", "l")
    val row3 = listOf("z", "x", "c", "v", "b", "n", "m")

    fun getChar(char: String): String {
        var base = char
        if (isShiftActive) base = base.uppercase()
        
        // Polish programmer mapping
        if (isAltGrActive) {
           return when (char) {
               "a" -> if (isShiftActive) "Ą" else "ą"
               "c" -> if (isShiftActive) "Ć" else "ć"
               "e" -> if (isShiftActive) "Ę" else "ę"
               "l" -> if (isShiftActive) "Ł" else "ł"
               "n" -> if (isShiftActive) "Ń" else "ń"
               "o" -> if (isShiftActive) "Ó" else "ó"
               "s" -> if (isShiftActive) "Ś" else "ś"
               "x" -> if (isShiftActive) "Ź" else "ź"
               "z" -> if (isShiftActive) "Ż" else "ż"
               else -> base
           }
        }
        return base
    }

    val customConfig = LocalPopupConfig.current

    fun getVariants(char: String): List<List<String>> {
        val custom = customConfig[char.lowercase()]
        if (custom != null) {
            return custom
        }
        return when (char.lowercase()) {
            "e" -> listOf(
                listOf("ę", "ë", "é", "ë", "ê", "ē", "ė"),
                listOf("Ę", "Ë", "É", "Ë", "Ê", "Ē", "Ė"),
                listOf("€", "¢", "э", "Э", "е", "Е", "ε", "Ε")
            )
            "a" -> listOf(
                listOf("ą", "á", "à", "â", "ä", "ā", "å", "æ", "ã"),
                listOf("Ą", "Á", "À", "Â", "Ä", "Ā", "Å", "Æ", "Ã"),
                listOf("@", "α", "Α", "а", "А")
            )
            "c" -> listOf(
                listOf("ć", "ç", "č", "©", "¢")
            )
            "n" -> listOf(
                listOf("ń", "ñ", "ń")
            )
            "o" -> listOf(
                listOf("ó", "ö", "ô", "ò", "õ", "œ", "ø", "ō"),
                listOf("Ó", "Ö", "Ô", "Ò", "Õ", "Œ", "Ø", "Ō")
            )
            "s" -> listOf(
                listOf("ś", "ß", "š", "ş")
            )
            "z" -> listOf(
                listOf("ż", "ź", "ž")
            )
            "l" -> listOf(
                listOf("ł", "£", "l"),
                listOf("Ł", "L")
            )
            else -> emptyList()
        }
    }

    val hitmapManager = LocalHitmapManager.current

    fun applyHitmap(key: String): String {
        val lastTap = hitmapManager.lastTapPoint
        val resolvedKey = if (lastTap != null) hitmapManager.handleTap(lastTap.x, lastTap.y) ?: key else key
        return getChar(resolvedKey)
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.SpaceEvenly
    ) {
        // Row 1
        Row(modifier = Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.SpaceEvenly) {
            row1.forEach { key ->
                KeyButton(getChar(key), registerChar = key, onClick = { onKeyPress(applyHitmap(key)) }, weight = 1f, variants = getVariants(key), onVariantClick = { onKeyPress(it) })
            }
        }
        // Row 2
        Row(modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            row2.forEach { key ->
                KeyButton(getChar(key), registerChar = key, onClick = { onKeyPress(applyHitmap(key)) }, weight = 1f, variants = getVariants(key), onVariantClick = { onKeyPress(it) })
            }
        }
        // Row 3
        Row(modifier = Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.SpaceEvenly) {
            KeyButton("Shift", onClick = onShiftToggle, weight = 1.5f, isSpecial = true, isActive = isShiftActive)
            row3.forEach { key ->
                KeyButton(getChar(key), registerChar = key, onClick = { onKeyPress(applyHitmap(key)) }, weight = 1f, variants = getVariants(key), onVariantClick = { onKeyPress(it) })
            }
            KeyButton("Del", onClick = { hitmapManager.handleBackspace(); onBackspace() }, weight = 1.5f, isSpecial = true, icon = Icons.AutoMirrored.Filled.Backspace)
        }
        // Row 4
        Row(modifier = Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.SpaceEvenly) {
            KeyButton("Układ", onClick = onLayoutChange, weight = 1.5f, isSpecial = true) 
            KeyButton("Fn", onClick = { }, weight = 1f, isSpecial = true)
            KeyButton(",", registerChar = ",", onClick = { onKeyPress(applyHitmap(",")) }, weight = 1f)
            KeyButton("Space", registerChar = " ", onClick = { onKeyPress(applyHitmap(" ")) }, weight = 5f)
            KeyButton(".", registerChar = ".", onClick = { onKeyPress(applyHitmap(".")) }, weight = 1f)
            KeyButton("AltGr", onClick = onAltGrToggle, weight = 1f, isSpecial = true, isActive = isAltGrActive)
            KeyButton("Enter", onClick = { onAction("enter") }, weight = 1.5f, isSpecial = true, icon = Icons.AutoMirrored.Filled.KeyboardReturn)
        }
    }
}

@Composable
fun ControlNumericLayout(onKeyPress: (String) -> Unit, onLayoutChange: () -> Unit, onKeyEvent: (Int, Int) -> Unit) {
    var isShiftActive by remember { mutableStateOf(false) }
    var isCtrlActive by remember { mutableStateOf(false) }

    fun getMeta(): Int {
        var meta = 0
        if (isShiftActive) meta = meta or android.view.KeyEvent.META_SHIFT_ON
        if (isCtrlActive) meta = meta or android.view.KeyEvent.META_CTRL_ON
        return meta
    }

    Row(modifier = Modifier.fillMaxSize()) {
        // Numeric Block
        Column(modifier = Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.SpaceEvenly) {
            Row(modifier = Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.SpaceEvenly) {
                KeyButton("7", onClick = { onKeyPress("7") }, weight = 1f)
                KeyButton("8", onClick = { onKeyPress("8") }, weight = 1f)
                KeyButton("9", onClick = { onKeyPress("9") }, weight = 1f)
            }
            Row(modifier = Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.SpaceEvenly) {
                KeyButton("4", onClick = { onKeyPress("4") }, weight = 1f)
                KeyButton("5", onClick = { onKeyPress("5") }, weight = 1f)
                KeyButton("6", onClick = { onKeyPress("6") }, weight = 1f)
            }
             Row(modifier = Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.SpaceEvenly) {
                KeyButton("1", onClick = { onKeyPress("1") }, weight = 1f)
                KeyButton("2", onClick = { onKeyPress("2") }, weight = 1f)
                KeyButton("3", onClick = { onKeyPress("3") }, weight = 1f)
            }
            Row(modifier = Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.SpaceEvenly) {
                KeyButton("0", onClick = { onKeyPress("0") }, weight = 2f)
                KeyButton(".", onClick = { onKeyPress(".") }, weight = 1f)
            }
        }
        
        // Control Block (Arrows, SysRq, Lock)
        Column(modifier = Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.SpaceEvenly) {
            Row(modifier = Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.SpaceEvenly) {
                KeyButton("SysRq", onClick = { onKeyEvent(android.view.KeyEvent.KEYCODE_SYSRQ, getMeta()) }, weight = 1f, isSpecial = true)
                KeyButton("Scroll\nLock", onClick = { onKeyEvent(android.view.KeyEvent.KEYCODE_SCROLL_LOCK, getMeta()) }, weight = 1f, isSpecial = true)
                KeyButton("Pause", onClick = { onKeyEvent(android.view.KeyEvent.KEYCODE_BREAK, getMeta()) }, weight = 1f, isSpecial = true)
            }
            Row(modifier = Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.SpaceEvenly) {
                KeyButton("Ins", onClick = { onKeyEvent(android.view.KeyEvent.KEYCODE_INSERT, getMeta()) }, weight = 1f, isSpecial = true)
                KeyButton("Home", onClick = { onKeyEvent(android.view.KeyEvent.KEYCODE_MOVE_HOME, getMeta()) }, weight = 1f, isSpecial = true)
                KeyButton("PgUp", onClick = { onKeyEvent(android.view.KeyEvent.KEYCODE_PAGE_UP, getMeta()) }, weight = 1f, isSpecial = true)
            }
            Row(modifier = Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.SpaceEvenly) {
                KeyButton("Del", onClick = { onKeyEvent(android.view.KeyEvent.KEYCODE_FORWARD_DEL, getMeta()) }, weight = 1f, isSpecial = true)
                KeyButton("End", onClick = { onKeyEvent(android.view.KeyEvent.KEYCODE_MOVE_END, getMeta()) }, weight = 1f, isSpecial = true)
                KeyButton("PgDn", onClick = { onKeyEvent(android.view.KeyEvent.KEYCODE_PAGE_DOWN, getMeta()) }, weight = 1f, isSpecial = true)
            }
            // Arrows T-Shape
             Row(modifier = Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.SpaceEvenly) {
                KeyButton("Shift", onClick = { isShiftActive = !isShiftActive }, weight = 1f, isSpecial = true, isActive = isShiftActive)
                KeyButton("↑", onClick = { onKeyEvent(android.view.KeyEvent.KEYCODE_DPAD_UP, getMeta()) }, weight = 1f)
                KeyButton("Ctrl", onClick = { isCtrlActive = !isCtrlActive }, weight = 1f, isSpecial = true, isActive = isCtrlActive)
            }
            Row(modifier = Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.SpaceEvenly) {
                KeyButton("←", onClick = { onKeyEvent(android.view.KeyEvent.KEYCODE_DPAD_LEFT, getMeta()) }, weight = 1f)
                KeyButton("↓", onClick = { onKeyEvent(android.view.KeyEvent.KEYCODE_DPAD_DOWN, getMeta()) }, weight = 1f)
                KeyButton("→", onClick = { onKeyEvent(android.view.KeyEvent.KEYCODE_DPAD_RIGHT, getMeta()) }, weight = 1f)
            }
             Row(modifier = Modifier.fillMaxWidth().weight(0.8f), horizontalArrangement = Arrangement.SpaceEvenly) {
                KeyButton("Tab", onClick = { onKeyEvent(android.view.KeyEvent.KEYCODE_TAB, getMeta()) }, weight = 1f, isSpecial = true)
                KeyButton("Układ", onClick = onLayoutChange, weight = 1f, isSpecial = true)
            }
        }
    }
}

@Composable
fun ShortcutsFullLayout(onLayoutChange: () -> Unit, onKeyEvent: (Int, Int) -> Unit) {
    var isShiftActive by remember { mutableStateOf(false) }
    var isCtrlActive by remember { mutableStateOf(false) }
    var isAltActive by remember { mutableStateOf(false) }

    fun getMeta(): Int {
        var meta = 0
        if (isShiftActive) meta = meta or android.view.KeyEvent.META_SHIFT_ON
        if (isCtrlActive) meta = meta or android.view.KeyEvent.META_CTRL_ON
        if (isAltActive) meta = meta or android.view.KeyEvent.META_ALT_ON
        return meta
    }

    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceEvenly) {
        // F keys
        Row(modifier = Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.SpaceEvenly) {
            KeyButton("Esc", onClick = { onKeyEvent(android.view.KeyEvent.KEYCODE_ESCAPE, getMeta()) }, weight = 1f, isSpecial = true)
            for (i in 1..6) {
                KeyButton("F$i", onClick = { onKeyEvent(android.view.KeyEvent.KEYCODE_F1 + i - 1, getMeta()) }, weight = 1f, isSpecial = true)
            }
        }
        Row(modifier = Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.SpaceEvenly) {
            for (i in 7..12) {
                KeyButton("F$i", onClick = { onKeyEvent(android.view.KeyEvent.KEYCODE_F1 + i - 1, getMeta()) }, weight = 1f, isSpecial = true)
            }
            KeyButton("Tab", onClick = { onKeyEvent(android.view.KeyEvent.KEYCODE_TAB, getMeta()) }, weight = 1f, isSpecial = true)
        }
        // Modifiers & Editing
        Row(modifier = Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.SpaceEvenly) {
            KeyButton("Shift", onClick = { isShiftActive = !isShiftActive }, weight = 1.5f, isSpecial = true, isActive = isShiftActive)
            KeyButton("Ctrl", onClick = { isCtrlActive = !isCtrlActive }, weight = 1.5f, isSpecial = true, isActive = isCtrlActive)
            KeyButton("Alt", onClick = { isAltActive = !isAltActive }, weight = 1.5f, isSpecial = true, isActive = isAltActive)
            KeyButton("Fn", onClick = { }, weight = 1f, isSpecial = true)
            KeyButton("Ins", onClick = { onKeyEvent(android.view.KeyEvent.KEYCODE_INSERT, getMeta()) }, weight = 1f, isSpecial = true)
            KeyButton("Del", onClick = { onKeyEvent(android.view.KeyEvent.KEYCODE_FORWARD_DEL, getMeta()) }, weight = 1.5f, isSpecial = true)
        }
        // Letters A, C, V, X, Z (Copy/Paste) and Nav
        Row(modifier = Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.SpaceEvenly) {
            KeyButton("Home", onClick = { onKeyEvent(android.view.KeyEvent.KEYCODE_MOVE_HOME, getMeta()) }, weight = 1f, isSpecial = true)
            KeyButton("End", onClick = { onKeyEvent(android.view.KeyEvent.KEYCODE_MOVE_END, getMeta()) }, weight = 1f, isSpecial = true)
            KeyButton("PgUp", onClick = { onKeyEvent(android.view.KeyEvent.KEYCODE_PAGE_UP, getMeta()) }, weight = 1f, isSpecial = true)
            KeyButton("PgDn", onClick = { onKeyEvent(android.view.KeyEvent.KEYCODE_PAGE_DOWN, getMeta()) }, weight = 1f, isSpecial = true)
            KeyButton("Z", onClick = { onKeyEvent(android.view.KeyEvent.KEYCODE_Z, getMeta()) }, weight = 1f)
            KeyButton("X", onClick = { onKeyEvent(android.view.KeyEvent.KEYCODE_X, getMeta()) }, weight = 1f)
            KeyButton("C", onClick = { onKeyEvent(android.view.KeyEvent.KEYCODE_C, getMeta()) }, weight = 1f)
            KeyButton("V", onClick = { onKeyEvent(android.view.KeyEvent.KEYCODE_V, getMeta()) }, weight = 1f)
            KeyButton("A", onClick = { onKeyEvent(android.view.KeyEvent.KEYCODE_A, getMeta()) }, weight = 1f)
        }
        // Arrows and layout switch
        Row(modifier = Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.SpaceEvenly) {
            KeyButton("Układ", onClick = onLayoutChange, weight = 1.5f, isSpecial = true)
            KeyButton("←", onClick = { onKeyEvent(android.view.KeyEvent.KEYCODE_DPAD_LEFT, getMeta()) }, weight = 1f)
            KeyButton("↑", onClick = { onKeyEvent(android.view.KeyEvent.KEYCODE_DPAD_UP, getMeta()) }, weight = 1f)
            KeyButton("↓", onClick = { onKeyEvent(android.view.KeyEvent.KEYCODE_DPAD_DOWN, getMeta()) }, weight = 1f)
            KeyButton("→", onClick = { onKeyEvent(android.view.KeyEvent.KEYCODE_DPAD_RIGHT, getMeta()) }, weight = 1f)
            KeyButton("Enter", onClick = { onKeyEvent(android.view.KeyEvent.KEYCODE_ENTER, getMeta()) }, weight = 1.5f, isSpecial = true, icon = Icons.AutoMirrored.Filled.KeyboardReturn)
        }
    }
}

@Composable
fun ShortcutsLettersLayout(onKeyPress: (String) -> Unit, onLayoutChange: () -> Unit, onKeyEvent: (Int, Int) -> Unit, onBackspace: () -> Unit) {
    var isShiftActive by remember { mutableStateOf(false) }
    var isCtrlActive by remember { mutableStateOf(false) }
    var isAltActive by remember { mutableStateOf(false) }

    fun getMeta(): Int {
        var meta = 0
        if (isShiftActive) meta = meta or android.view.KeyEvent.META_SHIFT_ON
        if (isCtrlActive) meta = meta or android.view.KeyEvent.META_CTRL_ON
        if (isAltActive) meta = meta or android.view.KeyEvent.META_ALT_ON
        return meta
    }

    val row1 = listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p")
    val row2 = listOf("a", "s", "d", "f", "g", "h", "j", "k", "l")
    val row3 = listOf("z", "x", "c", "v", "b", "n", "m")

    fun emitChar(char: String) {
        if (isCtrlActive || isAltActive) {
            // we should probably emit key event instead of text if modifiers are pressed
            val keyCode = android.view.KeyEvent.KEYCODE_A + (char[0] - 'a')
            onKeyEvent(keyCode, getMeta())
        } else {
            onKeyPress(if (isShiftActive) char.uppercase() else char)
        }
    }

    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceEvenly) {
        // Modifiers & Actions
        Row(modifier = Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.SpaceEvenly) {
            KeyButton("Esc", onClick = { onKeyEvent(android.view.KeyEvent.KEYCODE_ESCAPE, getMeta()) }, weight = 1f, isSpecial = true)
            KeyButton("Tab", onClick = { onKeyEvent(android.view.KeyEvent.KEYCODE_TAB, getMeta()) }, weight = 1f, isSpecial = true)
            KeyButton("Ctrl", onClick = { isCtrlActive = !isCtrlActive }, weight = 1.2f, isSpecial = true, isActive = isCtrlActive)
            KeyButton("Alt", onClick = { isAltActive = !isAltActive }, weight = 1.2f, isSpecial = true, isActive = isAltActive)
            KeyButton("Shift", onClick = { isShiftActive = !isShiftActive }, weight = 1.2f, isSpecial = true, isActive = isShiftActive)
            KeyButton("Układ", onClick = onLayoutChange, weight = 1.5f, isSpecial = true)
        }
        // Letters Q-P
        Row(modifier = Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.SpaceEvenly) {
            row1.forEach { key ->
                KeyButton(if (isShiftActive) key.uppercase() else key, onClick = { emitChar(key) }, weight = 1f)
            }
        }
        // Letters A-L
        Row(modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            row2.forEach { key ->
                KeyButton(if (isShiftActive) key.uppercase() else key, onClick = { emitChar(key) }, weight = 1f)
            }
        }
        // Letters Z-M + arrows
        Row(modifier = Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.SpaceEvenly) {
            row3.forEach { key ->
                KeyButton(if (isShiftActive) key.uppercase() else key, onClick = { emitChar(key) }, weight = 1f)
            }
            KeyButton("←", onClick = { onKeyEvent(android.view.KeyEvent.KEYCODE_DPAD_LEFT, getMeta()) }, weight = 1f)
            KeyButton("→", onClick = { onKeyEvent(android.view.KeyEvent.KEYCODE_DPAD_RIGHT, getMeta()) }, weight = 1f)
        }
        // Space, Enter, Backspace
        Row(modifier = Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.SpaceEvenly) {
            KeyButton("Home", onClick = { onKeyEvent(android.view.KeyEvent.KEYCODE_MOVE_HOME, getMeta()) }, weight = 1f, isSpecial = true)
            KeyButton("End", onClick = { onKeyEvent(android.view.KeyEvent.KEYCODE_MOVE_END, getMeta()) }, weight = 1f, isSpecial = true)
            KeyButton("Space", onClick = { emitChar(" ") }, weight = 4f)
            KeyButton("Del", onClick = { onBackspace() }, weight = 1.5f, isSpecial = true, icon = Icons.AutoMirrored.Filled.Backspace)
            KeyButton("Enter", onClick = { onKeyEvent(android.view.KeyEvent.KEYCODE_ENTER, getMeta()) }, weight = 1.5f, isSpecial = true, icon = Icons.AutoMirrored.Filled.KeyboardReturn)
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun RowScope.KeyButton(
    text: String,
    onClick: () -> Unit,
    weight: Float,
    isSpecial: Boolean = false,
    isActive: Boolean = false,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    alpha: Float = LocalKeyboardAlpha.current,
    variants: List<List<String>> = emptyList(),
    onVariantClick: ((String) -> Unit)? = null,
    registerChar: String? = null
) {
    val bgColor = if (isActive) Color(0xFF4A90E2) else if (isSpecial) Color(0xFF383A3E) else Color(0xFF484A4D)
    val textColor = Color.White
    var showPopup by remember { mutableStateOf(false) }
    
    val hitmapManager = LocalHitmapManager.current
    var isRegistered by remember { mutableStateOf(false) }
    
    Box(
        modifier = Modifier
            .weight(weight)
            .padding(2.dp)
            .fillMaxHeight()
            .onGloballyPositioned { coordinates ->
                if (!isRegistered && registerChar != null) {
                    hitmapManager.registerKey(registerChar, coordinates.boundsInWindow())
                    isRegistered = true
                }
            }
            .background(bgColor.copy(alpha = alpha), RoundedCornerShape(4.dp))
            .then(
                if (variants.isNotEmpty() && onVariantClick != null) {
                    Modifier.combinedClickable(
                        onClick = onClick,
                        onLongClick = { showPopup = true }
                    )
                } else {
                    Modifier.clickable(onClick = onClick)
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = text, tint = textColor.copy(alpha = alpha), modifier = Modifier.size(20.dp))
        } else {
            Text(
                text = text,
                color = textColor.copy(alpha = alpha),
                fontSize = if (text.length > 1) 12.sp else 16.sp,
                fontWeight = FontWeight.Medium
            )
        }
        
        if (showPopup) {
            androidx.compose.ui.window.Popup(
                alignment = Alignment.BottomCenter,
                onDismissRequest = { showPopup = false },
                properties = androidx.compose.ui.window.PopupProperties(focusable = true, dismissOnClickOutside = true)
            ) {
                Box(
                    modifier = Modifier
                        .padding(bottom = 60.dp)
                        .background(Color(0xFF2B2C30), RoundedCornerShape(8.dp))
                        .padding(8.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Close",
                                modifier = Modifier
                                    .size(24.dp)
                                    .clickable { showPopup = false },
                                tint = Color.Gray
                            )
                        }
                        variants.forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                row.forEach { variant ->
                                    Box(
                                        modifier = Modifier
                                            .size(36.dp)
                                            .background(Color(0xFF484A4D), RoundedCornerShape(4.dp))
                                            .clickable {
                                                onVariantClick?.invoke(variant)
                                                showPopup = false
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(text = variant, color = Color.White, fontSize = 18.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
