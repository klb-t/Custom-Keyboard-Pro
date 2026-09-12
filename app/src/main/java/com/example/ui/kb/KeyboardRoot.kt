package com.example.ui.kb

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.core.config.Settings
import com.example.core.config.SettingsStore
import com.example.core.layout.KeyAction
import com.example.core.layout.ElementDef
import com.example.core.layout.ElementPlacement
import com.example.core.layout.FreeKeyPins
import com.example.core.layout.NormRect
import com.example.core.layout.LayerTransforms
import com.example.core.layout.LayoutDef
import com.example.core.layout.LayoutRepository
import com.example.core.layout.ModifierKind
import com.example.core.layout.PanelId
import com.example.core.layout.PresentationMode
import com.example.core.layout.TextUnit as EditUnit

/**
 * The whole keyboard: indicator strip, suggestion strip, keys, and whichever panel is
 * open on top of them.
 *
 * Sizing and presentation are decided here from settings alone, so "make it shorter",
 * "put it on the right" and "split it" are configuration rather than separate code
 * paths. Split in particular is a transform on the layer ([LayerTransforms.split]), so
 * every other part of the keyboard is unaware that it happened.
 */
@Composable
fun KeyboardRoot(
    settings: Settings,
    onHeightChanged: (Int) -> Unit
) {
    val host = LocalKeyboardHost.current
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val theme = remember(settings.themeId, settings.customThemesJson) { ThemeStore.resolve(settings) }

    val landscape = configuration.screenWidthDp > configuration.screenHeightDp
    val screenHeightDp = configuration.screenHeightDp
    val screenWidthDp = configuration.screenWidthDp

    val stripHeight = 42.dp
    val indicatorHeight = 20.dp

    val keyboardHeightDp = (screenHeightDp * settings.heightFor(landscape))
        .coerceIn(120f, screenHeightDp * 0.85f)

    val suggestions by host.suggestions.suggestions.collectAsState()
    val aiBusy by host.suggestions.aiBusy.collectAsState()
    val panel = host.openPanelId

    val totalHeightDp = keyboardHeightDp +
        (if (settings.suggestionsEnabled || panel != null) stripHeight.value else 0f) +
        (if (settings.indicatorStripVisible) indicatorHeight.value else 0f) +
        settings.bottomPaddingDp

    val floating = settings.presentation == PresentationMode.FLOATING
    // Free keys need the whole screen to be placed on, exactly as a floating panel
    // does — the difference is that nothing is drawn behind them.
    val free = settings.presentation == PresentationMode.FREE

    val needsWholeScreen = floating || free ||
        host.layout.elements.any { it.visible && it.placement != ElementPlacement.DOCKED }

    LaunchedEffect(totalHeightDp, needsWholeScreen, screenHeightDp) {
        val target = if (needsWholeScreen) screenHeightDp.toFloat() else totalHeightDp
        onHeightChanged(with(density) { target.dp.roundToPx() })
    }

    val elements = host.layout.elements.filter { it.visible }

    if (elements.isNotEmpty()) {
        // A layout that describes its own pieces decides its own placement, and the
        // app-wide presentation setting stops applying: it can only ever say one
        // thing about the whole keyboard, which is the limitation elements exist to
        // remove.
        ElementComposition(
            settings = settings,
            theme = theme,
            elements = elements,
            dockedHeightDp = totalHeightDp,
            keyboardHeightDp = keyboardHeightDp,
            stripHeight = stripHeight,
            indicatorHeight = indicatorHeight,
            suggestions = suggestions,
            aiBusy = aiBusy,
            panel = panel
        )
    } else if (free) {
        FreeKeySurface(settings = settings, theme = theme)
    } else if (floating) {
        FloatingShell(
            settings = settings,
            theme = theme,
            contentHeightDp = totalHeightDp,
            avoidShiftPx = host.avoidance.shiftPx,
            avoidFade = host.avoidance.fade
        ) {
            KeyboardBody(
                settings, theme, suggestions, aiBusy, panel,
                keyboardHeightDp, stripHeight, indicatorHeight
            )
        }
    } else {
        val widthFraction = when (settings.presentation) {
            PresentationMode.ONE_HANDED_LEFT, PresentationMode.ONE_HANDED_RIGHT ->
                settings.widthFraction.coerceIn(0.5f, 1f)
            else -> settings.widthFraction.coerceIn(0.3f, 1f)
        }
        val alignment = when (settings.presentation) {
            PresentationMode.ONE_HANDED_LEFT -> Alignment.CenterStart
            PresentationMode.ONE_HANDED_RIGHT -> Alignment.CenterEnd
            else -> Alignment.Center
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(totalHeightDp.dp)
                .background(
                    theme.background.copy(
                        alpha = theme.background.alpha * settings.panelOpacity.coerceIn(0f, 1f)
                    )
                )
                .alpha(settings.keyboardOpacity.coerceIn(0.05f, 1f)),
            contentAlignment = alignment
        ) {
            Box(Modifier.fillMaxHeight().width((screenWidthDp * widthFraction).dp)) {
                KeyboardBody(
                    settings, theme, suggestions, aiBusy, panel,
                    keyboardHeightDp, stripHeight, indicatorHeight
                )
            }
            if (widthFraction < 0.99f) {
                OneHandedHandle(
                    theme = theme,
                    alignment = if (settings.presentation == PresentationMode.ONE_HANDED_LEFT)
                        Alignment.CenterEnd else Alignment.CenterStart
                )
            }
        }
    }
}

@Composable
private fun KeyboardBody(
    settings: Settings,
    theme: KeyboardTheme,
    suggestions: List<com.example.core.suggest.Suggestion>,
    aiBusy: Boolean,
    panel: PanelId?,
    keyboardHeightDp: Float,
    stripHeight: androidx.compose.ui.unit.Dp,
    indicatorHeight: androidx.compose.ui.unit.Dp
) {
    val host = LocalKeyboardHost.current
    val layout = host.layout

    Column(Modifier.fillMaxSize()) {
        if (settings.indicatorStripVisible) {
            IndicatorStrip(theme = theme, height = indicatorHeight)
        }

        if (settings.suggestionsEnabled || panel != null) {
            SuggestionStrip(
                suggestions = if (panel == null) suggestions else emptyList(),
                settings = settings,
                theme = theme,
                aiBusy = aiBusy,
                onAccept = { suggestion -> acceptSuggestion(host, suggestion) },
                onReject = { host.suggestions.block(it.text) },
                onToolbar = { host.openPanel(it) },
                height = stripHeight
            )
        }

        Box(
            Modifier
                .fillMaxWidth()
                .height(keyboardHeightDp.dp)
                .padding(horizontal = settings.sidePaddingDp.dp)
        ) {
            when (panel) {
                null -> Box(Modifier.fillMaxSize()) {
                    KeyArea(layout = layout, settings = settings, theme = theme)
                    if (settings.debugOverlay) {
                        DebugOverlay(
                            state = host.state,
                            layout = layout,
                            layerName = host.state.renderLayer(layout),
                            selectionStart = host.editor.selectionStart,
                            selectionEnd = host.editor.selectionEnd,
                            suggestionCount = suggestions.size,
                            theme = theme
                        )
                    }
                }
                PanelId.EMOJI -> EmojiPanel(theme) { host.openPanel(null) }
                PanelId.CLIPBOARD -> ClipboardPanel(theme) { host.openPanel(null) }
                PanelId.VOICE -> VoicePanel(theme, settings) { host.openPanel(null) }
                PanelId.AI_TOOLS -> AiToolsPanel(theme, settings) { host.openPanel(null) }
                PanelId.CURSOR -> CursorPanel(theme) { host.openPanel(null) }
                PanelId.LAYOUT_PICKER -> LayoutPickerPanel(theme, settings) { host.openPanel(null) }
                PanelId.INDICATORS -> Box(Modifier.fillMaxSize().background(theme.background)) {
                    IndicatorStrip(theme, 32.dp)
                }
                PanelId.NUMPAD -> KeyArea(
                    layout = LayoutRepository.byId("numpad") ?: layout,
                    settings = settings,
                    theme = theme
                )
                PanelId.SETTINGS -> LaunchedEffect(Unit) {
                    host.openApp()
                    host.openPanel(null)
                }
            }
        }

        if (settings.bottomPaddingDp > 0f) {
            Box(Modifier.fillMaxWidth().height(settings.bottomPaddingDp.dp).background(theme.background))
        }
    }
}

private fun acceptSuggestion(host: KeyboardHost, suggestion: com.example.core.suggest.Suggestion) {
    if (suggestion.replacesWord) {
        host.editor.replaceCurrentWord(suggestion.text)
    } else {
        // A model's continuation and a pasted clip are inserted verbatim; a word from
        // the next-word model is a word, so it gets the space that follows one.
        val verbatim = suggestion.source == com.example.core.suggest.SuggestionSource.AI ||
            suggestion.source == com.example.core.suggest.SuggestionSource.CLIPBOARD
        host.editor.commitCompletion(
            if (verbatim) suggestion.text else suggestion.text + " "
        )
    }
    host.suggestions.clear()
}

@Composable
private fun KeyArea(
    layout: LayoutDef,
    settings: Settings,
    theme: KeyboardTheme,
    /**
     * Shows this layer instead of whichever one the keyboard is currently on.
     *
     * The docked panel follows layer switching, because that is what a layer key is
     * for. An element pinned somewhere on the screen — a numeric block, a lone Escape
     * — shows its own layer and keeps showing it, because its whole point is being
     * there regardless of what the main panel is doing.
     */
    layerOverride: String? = null,
    surfaceId: String = "main",
    reservesSpace: Boolean = true
) {
    val host = LocalKeyboardHost.current
    val state = host.state

    val layerName = layerOverride ?: state.renderLayer(layout)
    val rawLayer = layout.layer(layerName) ?: layout.base

    // A layout with no shift layer still shifts: uppercase its character keys.
    val shifted = state.isActive(ModifierKind.SHIFT) &&
        layerName == layout.defaultLayer &&
        !layout.layers.containsKey(LayoutDef.SHIFT_LAYER)

    val effectiveLayer = remember(
        rawLayer, shifted, settings.presentation, settings.splitGapFraction,
        settings.flickInput, settings.backspaceSwipeDeletesWord,
        settings.freeKeyScale, settings.freeSpreadX, settings.freeSpreadY,
        settings.freeOriginXDp, settings.freeOriginYDp, settings.freeKeyPinsJson
    ) { effectiveLayerOf(rawLayer, shifted, settings) }

    val background: ImageBitmap? = remember(layout.background?.imageFile) {
        LayoutRepository.loadAsset(layout.background?.imageFile)?.asImageBitmap()
    }

    // A layout can be edited into a state with nothing to press. Stranding someone with
    // a blank rectangle and no way back is the one failure a keyboard must not have.
    if (effectiveLayer.allKeys.none { it.visible }) {
        EmptyLayoutNotice(layout.name, theme)
        return
    }

    KeySurface(
        layout = layout.copy(layers = layout.layers + (effectiveLayer.name to effectiveLayer)),
        layerName = effectiveLayer.name,
        state = state,
        settings = settings,
        theme = theme,
        background = background,
        onAction = { key, action ->
            host.feedback(key)
            host.perform(action)
        },
        onKeyDown = { key -> host.feedback(key) },
        onSurfaceSwipe = { direction -> host.performSurfaceGesture(direction) },
        learner = host.touchLearner,
        surfaceId = surfaceId,
        reservesSpace = reservesSpace,
        onCursorNudge = { steps ->
            repeat(kotlin.math.abs(steps)) {
                host.perform(
                    KeyAction.MoveCursor(
                        if (steps > 0) com.example.core.layout.CursorDirection.RIGHT
                        else com.example.core.layout.CursorDirection.LEFT,
                        EditUnit.CHARACTER
                    )
                )
            }
        },
        modifier = Modifier.fillMaxSize()
    )
}

/**
 * Shown when the current layout has no usable keys — an empty JSON edit, a mask import
 * with nothing bound. Offers the two ways out rather than leaving a blank surface.
 */
@Composable
private fun EmptyLayoutNotice(layoutName: String, theme: KeyboardTheme) {
    val host = LocalKeyboardHost.current
    Column(
        modifier = Modifier.fillMaxSize().background(theme.background).padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "\u201c$layoutName\u201d has no keys to press.",
            color = theme.keyText,
            fontSize = 15.sp
        )
        Box(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier
                    .background(theme.keyBackground, RoundedCornerShape(8.dp))
                    .clickable { host.openPanel(PanelId.LAYOUT_PICKER) }
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Text("Choose another", color = theme.keyText, fontSize = 14.sp)
            }
            Box(
                Modifier
                    .background(theme.keyBackground, RoundedCornerShape(8.dp))
                    .clickable { host.openApp("layouts") }
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Text("Edit it", color = theme.keyText, fontSize = 14.sp)
            }
        }
    }
}

/** The tab that pulls a one-handed keyboard back to full width. */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.OneHandedHandle(
    theme: KeyboardTheme,
    alignment: Alignment
) {
    Column(
        modifier = Modifier.align(alignment).padding(4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        HandleButton("⤢", theme) {
            com.example.core.config.SettingsStore.update {
                it.copy(presentation = PresentationMode.NORMAL, widthFraction = 1f)
            }
        }
        HandleButton("⇄", theme) {
            com.example.core.config.SettingsStore.update {
                it.copy(
                    presentation = if (it.presentation == PresentationMode.ONE_HANDED_LEFT)
                        PresentationMode.ONE_HANDED_RIGHT else PresentationMode.ONE_HANDED_LEFT
                )
            }
        }
    }
}

@Composable
private fun HandleButton(glyph: String, theme: KeyboardTheme, onClick: () -> Unit) {
    Box(
        Modifier
            .size(34.dp)
            .background(theme.keySpecialBackground, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(glyph, color = theme.keySpecialText, fontSize = 15.sp)
    }
}

/**
 * Floating mode.
 *
 * The input window becomes full height and the keyboard is a movable box inside it;
 * the service narrows the touchable region to that box so taps outside it still reach
 * the app underneath.
 */
@Composable
private fun FloatingShell(
    settings: Settings,
    theme: KeyboardTheme,
    contentHeightDp: Float,
    avoidShiftPx: Float = 0f,
    avoidFade: Float = 1f,
    content: @Composable () -> Unit
) {
    val host = LocalKeyboardHost.current
    val density = LocalDensity.current
    var offsetX by remember { mutableStateOf(settings.floatingX) }
    var offsetY by remember { mutableStateOf(settings.floatingY) }
    var width by remember { mutableStateOf(settings.floatingWidthDp) }
    // A floating panel has no reason to be as tall as a docked keyboard, so it carries
    // its own height; zero means "whatever the content needs".
    var height by remember {
        mutableStateOf(
            if (settings.floatingHeightDp > 0f) settings.floatingHeightDp else contentHeightDp + 26f
        )
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val maxW = maxWidth.value
        // The last stretch of the input view is underneath the system navigation bar,
        // which takes the touches. A panel allowed down there looks present and is
        // unreachable, so the usable height stops short of it.
        val maxH = (maxHeight.value - settings.floatingSafeBottomDp).coerceAtLeast(120f)

        // The panel is nudged up out of the cursor's way without its stored position
        // changing: where the user put it is a decision, and getting out of the way
        // for a moment is not a reason to overwrite it.
        val avoidShiftDp = with(density) { avoidShiftPx.toDp().value }
        val placedY = (offsetY.coerceIn(0f, (maxH - height).coerceAtLeast(0f)) - avoidShiftDp)
            .coerceAtLeast(0f)

        Box(
            modifier = Modifier
                .offset(offsetX.coerceIn(0f, (maxW - width).coerceAtLeast(0f)).dp, placedY.dp)
                .width(width.dp)
                .height(height.dp)
                .alpha((settings.keyboardOpacity * avoidFade).coerceIn(0.05f, 1f))
                .background(
                    theme.background.copy(
                        alpha = theme.background.alpha * settings.panelOpacity.coerceIn(0f, 1f)
                    ),
                    RoundedCornerShape(14.dp)
                )
                .border(1.dp, theme.keyBorder, RoundedCornerShape(14.dp))
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(26.dp)
                        .background(theme.stripBackground, RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp))
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragEnd = {
                                    com.example.core.config.SettingsStore.update {
                                        it.copy(floatingX = offsetX, floatingY = offsetY)
                                    }
                                }
                            ) { change, drag ->
                                change.consume()
                                offsetX += drag.x / density.density
                                offsetY += drag.y / density.density
                            }
                        },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("⠿", color = theme.keyHintText, fontSize = 13.sp,
                        modifier = Modifier.padding(start = 10.dp))
                    Box(Modifier.weight(1f))
                    Box(
                        Modifier
                            .fillMaxHeight()
                            .clickable {
                                com.example.core.config.SettingsStore.update {
                                    it.copy(presentation = PresentationMode.NORMAL)
                                }
                            }
                            .padding(horizontal = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("▭", color = theme.keyHintText, fontSize = 13.sp)
                    }
                }
                Box(Modifier.weight(1f)) { content() }
            }

            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(22.dp)
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragEnd = {
                                com.example.core.config.SettingsStore.update {
                                    it.copy(floatingWidthDp = width, floatingHeightDp = height)
                                }
                            }
                        ) { change, drag ->
                            change.consume()
                            width = (width + drag.x / density.density).coerceIn(220f, maxW)
                            height = (height + drag.y / density.density).coerceIn(140f, maxH)
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Text("◢", color = theme.keyHintText, fontSize = 12.sp)
            }
        }
    }
}

/**
 * Every transform between a layout's stored layer and the one actually drawn.
 *
 * Shared rather than inlined because the arranging overlay has to hit-test exactly
 * the geometry the renderer drew — computing it twice from the same function is the
 * only way that stays true when either end changes.
 */
private fun effectiveLayerOf(
    rawLayer: com.example.core.layout.LayerDef,
    shifted: Boolean,
    settings: Settings
): com.example.core.layout.LayerDef {
    var result = if (shifted) LayerTransforms.uppercased(rawLayer) else rawLayer
    result = LayerTransforms.applyPreferences(
        layer = result,
        allowFlick = settings.flickInput,
        allowBackspaceWordSwipe = settings.backspaceSwipeDeletesWord
    )
    if (settings.presentation == PresentationMode.SPLIT) {
        result = LayerTransforms.split(result, settings.splitGapFraction)
    }
    if (settings.presentation == PresentationMode.FREE) {
        // Free placement is a transform on the layer, not a second renderer:
        // placement, hit testing, the touch model and popups all already understand
        // keys that carry their own bounds.
        result = LayerTransforms.scatter(
            layer = result,
            scale = settings.freeKeyScale,
            spreadX = settings.freeSpreadX,
            spreadY = settings.freeSpreadY,
            originX = settings.freeOriginXDp / 400f,
            originY = settings.freeOriginYDp / 800f,
            pinned = FreeKeyPins.parse(settings.freeKeyPinsJson)
        )
    }
    return result
}

/**
 * Keys with nothing behind them.
 *
 * The surface fills the screen and paints no background at all, so what shows between
 * the keys is the app underneath. Which touches that app actually receives is a
 * separate question, answered by the insets policy rather than by this: see
 * [com.example.core.layout.InsetsMode].
 */
@Composable
private fun FreeKeySurface(settings: Settings, theme: KeyboardTheme) {
    val host = LocalKeyboardHost.current
    val layout = host.layout
    val density = LocalDensity.current

    // Cursor avoidance applies here rather than in the service because both of its
    // visible effects — moving and fading — are drawing, not windowing.
    val shiftDp = with(density) { host.avoidance.shiftPx.toDp() }
    val opacity = (settings.keyboardOpacity * host.avoidance.fade).coerceIn(0.05f, 1f)

    Box(
        Modifier
            .fillMaxSize()
            .offset(y = -shiftDp)
            .alpha(opacity)
    ) {
        KeyArea(layout = layout, settings = settings, theme = theme)

        if (settings.freeArrangeMode) {
            ArrangeOverlay(settings = settings, theme = theme)
        }
    }
}

/**
 * While arranging, a drag moves a key instead of typing with it.
 *
 * Implemented as a layer above the keys rather than as a branch inside the key
 * gesture loop, which keeps the two apart in the only way that matters: while this is
 * up, no touch can reach the typing path at all, so there is no reading of a gesture
 * that could go either way.
 */
@Composable
private fun ArrangeOverlay(settings: Settings, theme: KeyboardTheme) {
    val host = LocalKeyboardHost.current
    val layout = host.layout
    val layerName = host.state.renderLayer(layout)
    val rawLayer = layout.layer(layerName) ?: layout.base
    val layer = remember(rawLayer, settings) { effectiveLayerOf(rawLayer, false, settings) }

    var dragging by remember { mutableStateOf<String?>(null) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val widthPx = with(LocalDensity.current) { maxWidth.toPx() }
        val heightPx = with(LocalDensity.current) { maxHeight.toPx() }
        val placed = remember(layer, widthPx, heightPx) {
            com.example.core.layout.KeyPlacement.place(layer, widthPx, heightPx)
        }

        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(placed, widthPx, heightPx) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            dragging = com.example.core.layout.KeyPlacement
                                .hitTest(placed, offset.x, offset.y)?.key?.id
                        },
                        onDragEnd = { dragging = null },
                        onDragCancel = { dragging = null }
                    ) { change, delta ->
                        change.consume()
                        val id = dragging ?: return@detectDragGestures
                        val current = placed.firstOrNull { it.key.id == id } ?: return@detectDragGestures
                        val natural = com.example.core.layout.NormRect(
                            current.left / widthPx, current.top / heightPx,
                            current.right / widthPx, current.bottom / heightPx
                        )
                        SettingsStore.update { s ->
                            val pins = FreeKeyPins.moved(
                                pins = FreeKeyPins.parse(s.freeKeyPinsJson),
                                id = id,
                                from = natural,
                                dx = delta.x / widthPx,
                                dy = delta.y / heightPx
                            )
                            s.copy(freeKeyPinsJson = FreeKeyPins.write(pins))
                        }
                    }
                }
        )

        Box(
            Modifier
                .align(Alignment.TopCenter)
                .padding(8.dp)
                .background(theme.popupBackground, RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Text(
                "Arranging — drag keys. Typing is off until you turn this off.",
                color = theme.popupText,
                fontSize = 12.sp
            )
        }
    }
}

/**
 * A layout drawn as its own set of pieces.
 *
 * Docked elements share the panel at the bottom in the order the layout lists them;
 * floating and free elements are placed by their own bounds over the whole screen.
 * Each carries its own opacity, its own background, whether it claims space from the
 * app, and whether it may move out of the cursor's way — because those are questions
 * about a piece of keyboard, not about the keyboard.
 */
@Composable
private fun ElementComposition(
    settings: Settings,
    theme: KeyboardTheme,
    elements: List<ElementDef>,
    dockedHeightDp: Float,
    keyboardHeightDp: Float,
    stripHeight: androidx.compose.ui.unit.Dp,
    indicatorHeight: androidx.compose.ui.unit.Dp,
    suggestions: List<com.example.core.suggest.Suggestion>,
    aiBusy: Boolean,
    panel: PanelId?
) {
    val host = LocalKeyboardHost.current
    val density = LocalDensity.current
    val layout = host.layout

    val docked = elements.filter { it.placement == ElementPlacement.DOCKED }
    val loose = elements.filter { it.placement != ElementPlacement.DOCKED }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val viewWidth = maxWidth
        val viewHeight = maxHeight

        if (docked.isNotEmpty()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(dockedHeightDp.dp)
                    .align(Alignment.BottomCenter)
            ) {
                DockedElements(
                    settings = settings,
                    theme = theme,
                    elements = docked,
                    keyboardHeightDp = keyboardHeightDp,
                    stripHeight = stripHeight,
                    indicatorHeight = indicatorHeight,
                    suggestions = suggestions,
                    aiBusy = aiBusy,
                    panel = panel
                )
            }
        }

        loose.forEach { element ->
            // A floating piece with no stated position would otherwise be invisible at
            // the top-left corner of nothing; put it somewhere reachable instead.
            val bounds = element.bounds ?: NormRect(0.55f, 0.30f, 0.98f, 0.62f)
            val shift = if (element.pinned) 0f else host.avoidance.shiftPx
            val shiftDp = with(density) { shift.toDp() }

            Box(
                Modifier
                    .offset(
                        x = viewWidth * bounds.left,
                        y = (viewHeight * bounds.top - shiftDp).coerceAtLeast(0.dp)
                    )
                    .size(
                        width = viewWidth * bounds.width.coerceAtLeast(0.05f),
                        height = viewHeight * bounds.height.coerceAtLeast(0.05f)
                    )
                    .alpha((settings.keyboardOpacity * element.opacity).coerceIn(0.05f, 1f))
                    .then(
                        if (element.placement == ElementPlacement.FLOATING)
                            Modifier.background(
                                theme.background.copy(
                                    alpha = theme.background.alpha *
                                        (settings.panelOpacity * element.panelOpacity).coerceIn(0f, 1f)
                                ),
                                RoundedCornerShape(12.dp)
                            )
                        else Modifier
                    )
            ) {
                KeyArea(
                    layout = layout,
                    settings = settings,
                    theme = theme,
                    layerOverride = element.layer,
                    surfaceId = element.id,
                    reservesSpace = element.reservesSpace
                )
            }
        }
    }
}

/** The pieces that live in the panel at the bottom, stacked in the order given. */
@Composable
private fun DockedElements(
    settings: Settings,
    theme: KeyboardTheme,
    elements: List<ElementDef>,
    keyboardHeightDp: Float,
    stripHeight: androidx.compose.ui.unit.Dp,
    indicatorHeight: androidx.compose.ui.unit.Dp,
    suggestions: List<com.example.core.suggest.Suggestion>,
    aiBusy: Boolean,
    panel: PanelId?
) {
    val host = LocalKeyboardHost.current
    val layout = host.layout

    Column(
        Modifier
            .fillMaxSize()
            .background(
                theme.background.copy(
                    alpha = theme.background.alpha * settings.panelOpacity.coerceIn(0f, 1f)
                )
            )
            .alpha(settings.keyboardOpacity.coerceIn(0.05f, 1f))
    ) {
        if (settings.indicatorStripVisible) {
            IndicatorStrip(theme = theme, height = indicatorHeight)
        }
        if (settings.suggestionsEnabled || panel != null) {
            SuggestionStrip(
                suggestions = if (panel == null) suggestions else emptyList(),
                settings = settings,
                theme = theme,
                aiBusy = aiBusy,
                onAccept = { suggestion -> acceptSuggestion(host, suggestion) },
                onReject = { host.suggestions.block(it.text) },
                onToolbar = { host.openPanel(it) },
                height = stripHeight
            )
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(keyboardHeightDp.dp)
                .padding(horizontal = settings.sidePaddingDp.dp)
        ) {
            Column(Modifier.fillMaxSize()) {
                elements.forEach { element ->
                    Box(Modifier.fillMaxWidth().weight(1f)) {
                        KeyArea(
                            layout = layout,
                            settings = settings,
                            theme = theme,
                            // The first docked element is the main panel and follows
                            // layer switching; any further ones show what they say.
                            layerOverride = if (element === elements.first()) null else element.layer,
                            surfaceId = element.id,
                            reservesSpace = element.reservesSpace
                        )
                    }
                }
            }
        }
    }
}
