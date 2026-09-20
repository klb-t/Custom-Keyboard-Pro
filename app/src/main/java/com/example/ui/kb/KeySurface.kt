package com.example.ui.kb

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.foundation.Image
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.platform.LocalDensity
import com.example.core.config.Settings
import com.example.core.layout.BackgroundFit
import com.example.core.layout.KeyAction
import com.example.core.layout.KeyDef
import com.example.core.layout.KeyPlacement
import com.example.core.layout.KeyShape
import com.example.core.layout.KeyTrigger
import com.example.core.layout.LayerDef
import com.example.core.layout.LayoutDef
import com.example.core.layout.PlacedKey
import com.example.core.layout.SwipeDirection
import com.example.ime.KeyboardState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * The key surface: it draws a layer and turns finger movement into actions.
 *
 * Touch handling is one gesture loop over the whole surface rather than a clickable
 * per key. That is what makes sliding from one key to another work, what lets a
 * long-press popup track the finger that opened it, and what lets a bitmap-traced
 * layout (whose keys are arbitrary rectangles over an image) be hit-tested by the same
 * code as a grid layout. It also means the key that lights up and the key that types
 * are resolved from one hit test, which was not true before.
 */
@Composable
fun KeySurface(
    layout: LayoutDef,
    layerName: String,
    state: KeyboardState,
    settings: Settings,
    theme: KeyboardTheme,
    background: ImageBitmap?,
    onAction: (KeyDef, KeyAction) -> Unit,
    onKeyDown: (KeyDef) -> Unit,
    onCursorNudge: (Int) -> Unit,
    /**
     * A long swipe that no key claimed. Returns true if it did something — if it did
     * not, the press falls through and still types, so an unbound direction cannot
     * silently eat a keystroke.
     */
    onSurfaceSwipe: (SwipeDirection) -> Boolean = { false },
    learner: com.example.core.hitmap.TouchLearner? = null,
    /** Identifies this surface when it reports its geometry; see KeyboardHost. */
    surfaceId: String = "main",
    /** False when this surface's keys should not claim touches from the app. */
    reservesSpace: Boolean = true,
    modifier: Modifier = Modifier
) {
    val layer: LayerDef = layout.layer(layerName) ?: layout.base
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    // Where this surface sits inside the input view, so the key rectangles reported
    // to the service are in the same coordinates its insets are.
    var surfaceOrigin by remember { mutableStateOf(Offset.Zero) }

    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .onGloballyPositioned { surfaceOrigin = it.positionInRoot() }
    ) {
        val widthPx = constraints.maxWidth.toFloat()
        val heightPx = constraints.maxHeight.toFloat()

        val placement = remember(layer, widthPx, heightPx) {
            KeyPlacement.place(layer, widthPx, heightPx)
        }

        // The service needs the drawn geometry to restrict touches to the keys
        // themselves. Reporting what was actually placed — rather than recomputing it
        // there — is what keeps the touchable region and the visible keys in step.
        val host = LocalKeyboardHost.current
        LaunchedEffect(placement, surfaceOrigin, reservesSpace) {
            host.reportKeyRects(
                surfaceId,
                if (!reservesSpace) emptyList()
                else placement.filter { it.key.visible }.map { p ->
                    android.graphics.Rect(
                        (surfaceOrigin.x + p.left).toInt(),
                        (surfaceOrigin.y + p.top).toInt(),
                        (surfaceOrigin.x + p.right).toInt(),
                        (surfaceOrigin.y + p.bottom).toInt()
                    )
                }
            )
        }

        // Learned offsets are stored per key as a fraction of its size, so they are
        // rescaled whenever the placement changes rather than going stale.
        val learnedOffsets = remember(placement, settings.touchModelEnabled) {
            if (settings.touchModelEnabled) learner?.offsetsFor(placement).orEmpty() else emptyMap()
        }

        val pressed: SnapshotStateList<String> = remember { mutableStateListOf() }
        val taps = remember { TapTracker() }
        var preview by remember { mutableStateOf<PlacedKey?>(null) }
        var popup by remember { mutableStateOf<PopupState?>(null) }
        // A key carrying whole tabs of symbols opens a board that stays open, rather
        // than a strip you have to keep the finger on. Separate state because the two
        // are separate interactions, not two sizes of one.
        var board by remember { mutableStateOf<PlacedKey?>(null) }

        val gapPx = with(density) { settings.keyGapDp.dp.toPx() }
        val cornerPx = with(density) { settings.keyCornerDp.dp.toPx() }
        val swipeThresholdPx = with(density) { settings.swipeThresholdDp.dp.toPx() }
        val slideStepPx = with(density) { 16.dp.toPx() }

        background?.let { bitmap ->
            Image(
                painter = BitmapPainter(bitmap),
                contentDescription = null,
                modifier = Modifier.fillMaxSize().alpha(layout.background?.opacity ?: 1f),
                contentScale = when (layout.background?.fit) {
                    BackgroundFit.CONTAIN -> ContentScale.Fit
                    BackgroundFit.COVER -> ContentScale.Crop
                    else -> ContentScale.FillBounds
                }
            )
        }

        // Read through a live handle rather than keying the gesture loop on it.
        //
        // Placement changes on every Shift, because a shifted layer is a different
        // layer with different key actions — and keying the loop on it meant the loop
        // was torn down and rebuilt in the middle of typing, taking any finger
        // currently down with it. Nothing about the *gesture* changes when a label
        // does, so the loop keeps running and simply reads the newest geometry.
        val livePlacement by rememberUpdatedState(placement)
        val liveLearnedOffsets by rememberUpdatedState(learnedOffsets)

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(settings, layerName) {
                    val touches = mutableMapOf<PointerId, Touch>()

                    fun resolve(x: Float, y: Float): PlacedKey? =
                        if (settings.touchModelEnabled) {
                            KeyPlacement.probableKey(
                                livePlacement, x, y,
                                with(density) { settings.touchModelSigmaDp.dp.toPx() },
                                liveLearnedOffsets
                            )
                        } else {
                            KeyPlacement.hitTest(livePlacement, x, y)
                        }

                    fun stopTimers(touch: Touch) {
                        touch.longPressJob?.cancel()
                        touch.longPressJob = null
                        touch.repeatJob?.cancel()
                        touch.repeatJob = null
                    }

                    fun armTimers(touch: Touch) {
                        val key = touch.placed.key
                        if (key.repeatable) {
                            // Hold-to-repeat wins over long-press: a key that repeats is a
                            // key you hold down, and having it fire a different action after
                            // a moment would make holding it unpredictable.
                            val repeatAction = key.actionFor(KeyTrigger.Repeat) ?: key.tapAction
                            if (repeatAction != null) {
                                touch.repeatJob = scope.launch {
                                    delay(settings.repeatStartMs)
                                    var interval = settings.repeatIntervalMs
                                    while (true) {
                                        onAction(key, repeatAction)
                                        touch.consumed = true
                                        delay(interval)
                                        // Accelerate, but never past a rate a person can stop.
                                        if (interval > 22L) interval -= 3L
                                    }
                                }
                            }
                            return
                        }

                        val longPressAction = key.actionFor(KeyTrigger.LongPress)
                        val hasBoard = settings.longPressPopup && key.popupGroups.isNotEmpty()
                        val hasPopup = settings.longPressPopup && key.popup.isNotEmpty()
                        if (!hasBoard && !hasPopup && longPressAction == null) return

                        touch.longPressJob = scope.launch {
                            delay(settings.longPressMs)
                            if (hasBoard) {
                                // The board outlives the gesture, so the touch is
                                // finished here rather than left half-open waiting for
                                // a finger that has already done its job.
                                board = touch.placed
                                touch.consumed = true
                                onKeyDown(key)
                            } else if (hasPopup) {
                                val opened = PopupState(touch.placed, key.popup, 0)
                                popup = opened.copy(
                                    selected = popupIndexAt(
                                        opened, touch.currentX, widthPx,
                                        touch.placed.width.coerceAtLeast(1f)
                                    )
                                )
                                touch.popupOpen = true
                                onKeyDown(key)
                            } else if (longPressAction != null) {
                                onAction(key, longPressAction)
                                touch.consumed = true
                            }
                        }
                    }

                    fun beginTouch(id: PointerId, x: Float, y: Float) {
                        val placed = resolve(x, y) ?: return
                        val touch = Touch(placed, x, y)
                        touches[id] = touch
                        pressed.add(placed.key.id)
                        onKeyDown(placed.key)
                        if (settings.keyPreviewPopup && placed.key.tapAction is KeyAction.Text) {
                            preview = placed
                        }
                        armTimers(touch)
                    }

                    fun endTouch(id: PointerId, cancelled: Boolean) {
                        val touch = touches.remove(id) ?: return
                        stopTimers(touch)
                        pressed.remove(touch.placed.key.id)
                        if (preview?.key?.id == touch.placed.key.id) preview = null

                        val key = touch.placed.key

                        if (touch.popupOpen) {
                            val current = popup
                            popup = null
                            touch.popupOpen = false
                            if (!cancelled && current != null) {
                                current.items.getOrNull(current.selected)?.let { choice ->
                                    onAction(key, KeyAction.Text(choice))
                                }
                            }
                            return
                        }

                        if (cancelled || touch.consumed) return

                        val direction = SwipeDirection.of(
                            touch.currentX - touch.startX,
                            touch.currentY - touch.startY,
                            swipeThresholdPx
                        )
                        val swipeAction = direction?.let { key.actionFor(KeyTrigger.Swipe(it)) }
                        if (swipeAction != null) {
                            onAction(key, swipeAction)
                            return
                        }

                        // A long swipe that no key wanted belongs to the keyboard as a
                        // whole. The extra distance is what keeps it from firing on a
                        // sloppy tap near an unbound key.
                        val longSwipe = SwipeDirection.of(
                            touch.currentX - touch.startX,
                            touch.currentY - touch.startY,
                            swipeThresholdPx * 3f
                        )
                        if (longSwipe != null && onSurfaceSwipe(longSwipe)) return

                        val now = System.currentTimeMillis()
                        val isDoubleTap = taps.keyId == key.id &&
                            now - taps.atMillis < settings.doubleTapMs
                        taps.keyId = key.id
                        taps.atMillis = now

                        val action = (if (isDoubleTap) key.actionFor(KeyTrigger.DoubleTap) else null)
                            ?: key.tapAction
                            ?: return

                        if (settings.touchModelEnabled) {
                            if (action is KeyAction.Backspace) {
                                learner?.onBackspace()
                            } else {
                                learner?.onTap(
                                    touch.placed, touch.startX, touch.startY,
                                    learn = settings.touchModelLearning
                                )
                            }
                        }
                        onAction(key, action)
                    }

                    fun moveTouch(id: PointerId, x: Float, y: Float) {
                        val touch = touches[id] ?: return
                        touch.currentX = x
                        touch.currentY = y

                        if (touch.popupOpen) {
                            val current = popup ?: return
                            // Same function as the strip was positioned with. Reading
                            // the index off geometry computed a second time by hand is
                            // how the two came apart in the first place.
                            val index = popupIndexAt(
                                current, x, widthPx, touch.placed.width.coerceAtLeast(1f)
                            )
                            if (index != current.selected) popup = current.copy(selected = index)
                            return
                        }

                        val key = touch.placed.key

                        // Sliding along the space bar scrubs the cursor. It is the one
                        // gesture that has to stay continuous rather than firing on release.
                        if (settings.spaceSlideCursor && key.tapAction is KeyAction.Space) {
                            val delta = x - touch.slideAnchorX
                            if (abs(delta) >= slideStepPx) {
                                val steps = (delta / slideStepPx).toInt()
                                onCursorNudge(steps)
                                touch.slideAnchorX += steps * slideStepPx
                                touch.consumed = true
                                stopTimers(touch)
                            }
                            return
                        }

                        val travelled = abs(x - touch.startX) + abs(y - touch.startY)
                        if (travelled < swipeThresholdPx / 2f) return

                        // Past the threshold, a key with a matching swipe binding keeps the
                        // finger; one without it hands over to whichever key is now under it.
                        val direction = SwipeDirection.of(
                            x - touch.startX, y - touch.startY, swipeThresholdPx
                        )
                        if (direction != null && key.actionFor(KeyTrigger.Swipe(direction)) != null) {
                            stopTimers(touch)
                            return
                        }

                        val under = resolve(x, y) ?: return
                        if (under.key.id != key.id && !touch.consumed) {
                            stopTimers(touch)
                            pressed.remove(key.id)
                            val moved = Touch(under, touch.startX, touch.startY)
                            moved.currentX = x
                            moved.currentY = y
                            touches[id] = moved
                            pressed.add(under.key.id)
                            onKeyDown(under.key)
                            if (settings.keyPreviewPopup) preview = under
                            armTimers(moved)
                        }
                    }

                    // The gesture loop is restarted whenever its keys change — a
                    // rotation, a window resize, a settings write, a layer switch. That
                    // cancels this coroutine wherever it happens to be, including with a
                    // finger down and a popup open, and nothing downstream ever hears
                    // about it. Cleaning up here is what stops a cancelled gesture from
                    // leaving a popup on screen for the rest of the session.
                    try {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                event.changes.forEach { change: PointerInputChange ->
                                    when {
                                        change.changedToDownIgnoreConsumed() ->
                                            beginTouch(change.id, change.position.x, change.position.y)
                                        change.changedToUpIgnoreConsumed() ->
                                            endTouch(change.id, cancelled = false)
                                        else ->
                                            moveTouch(change.id, change.position.x, change.position.y)
                                    }
                                }
                                // Consume so the keyboard body's own gestures do not also fire.
                                if (touches.isNotEmpty()) event.changes.forEach { it.consume() }

                                // No finger down means nothing may still be showing as
                                // held. Enforced every event rather than trusted to the
                                // release path, because the release path is exactly what
                                // a cancelled gesture skips.
                                if (touches.isEmpty()) {
                                    if (popup != null) popup = null
                                    if (preview != null) preview = null
                                    if (pressed.isNotEmpty()) pressed.clear()
                                }
                            }
                        }
                    } finally {
                        touches.values.forEach { stopTimers(it) }
                        touches.clear()
                        popup = null
                        preview = null
                        pressed.clear()
                    }
                }
        ) {
            placement.forEach { placed ->
                if (!placed.key.visible) return@forEach
                KeyView(
                    placed = placed,
                    isPressed = pressed.contains(placed.key.id),
                    state = state,
                    settings = settings,
                    theme = theme,
                    gapPx = gapPx,
                    cornerPx = cornerPx
                )
            }
        }

        preview?.let { placed ->
            if (popup == null) {
                KeyPreview(placed = placed, theme = theme, density = density)
            }
        }

        board?.let { anchor ->
            val groups = anchor.key.popupGroups
            SymbolBoard(
                groups = groups,
                selectedGroupId = BoardMemory.selectedGroup(settings, anchor.key.id, groups),
                pinnedGroupIds = BoardMemory.pinnedGroups(settings),
                theme = theme,
                columns = settings.symbolBoardColumns,
                onSelectGroup = { BoardMemory.rememberGroup(anchor.key.id, it) },
                onTogglePin = { BoardMemory.togglePin(it) },
                onPick = { symbol ->
                    onAction(anchor.key, KeyAction.Text(symbol))
                    // Kept open: picking one symbol from a scientific board is very
                    // often picking three.
                },
                onClose = { board = null }
            )
        }

        popup?.let { current ->
            LongPressPopup(
                popup = current,
                surfaceWidthPx = widthPx,
                theme = theme
            )
        }
    }
}

/** Live state of one finger on the surface. */
private class Touch(
    val placed: PlacedKey,
    val startX: Float,
    val startY: Float
) {
    var currentX: Float = startX
    var currentY: Float = startY
    var slideAnchorX: Float = startX
    var longPressJob: Job? = null
    var repeatJob: Job? = null

    /** An action has already fired for this touch; releasing must not fire another. */
    var consumed: Boolean = false
    var popupOpen: Boolean = false
}

data class PopupState(val anchor: PlacedKey, val items: List<String>, val selected: Int)

/** Remembers the previous tap so a second one on the same key can mean something else. */
private class TapTracker {
    var keyId: String? = null
    var atMillis: Long = 0L
}

/**
 * Where the long-press strip starts, placed so its **first** item sits under the finger.
 *
 * It used to be centred on the key, which put the middle of the list under the finger
 * while the selection still started at item 0 — the two disagreed. A finger held
 * perfectly still committed the first item; a finger that twitched by a pixel produced
 * a move event, the index was recomputed from position, and out came whatever was
 * halfway along. On a Polish layout that is the difference between "ó" and "õ" for a
 * gesture the user cannot perform any more carefully.
 *
 * The first alternate is the one people actually want — it is why it is first — so it
 * belongs where the finger already is. Sliding right then walks the rest.
 */
internal fun popupOriginX(popup: PopupState, surfaceWidth: Float, cellWidth: Float): Float {
    val total = cellWidth * popup.items.size
    val wanted = popup.anchor.centerX - cellWidth / 2f
    // Near the right edge the whole strip has to shift left to stay on screen, and the
    // first item then is not under the finger. That is why the selected index is read
    // back through [popupIndexAt] rather than assumed to be zero.
    return wanted.coerceIn(0f, (surfaceWidth - total).coerceAtLeast(0f))
}

/** Which item a finger at [x] is over, using the same geometry the strip was drawn with. */
internal fun popupIndexAt(
    popup: PopupState,
    x: Float,
    surfaceWidth: Float,
    cellWidth: Float
): Int {
    val originX = popupOriginX(popup, surfaceWidth, cellWidth)
    val index = floor((x - originX) / cellWidth).toInt()
    return index.coerceIn(0, popup.items.lastIndex)
}

// ---------------------------------------------------------------------------
// Key rendering
// ---------------------------------------------------------------------------

@Composable
private fun KeyView(
    placed: PlacedKey,
    isPressed: Boolean,
    state: KeyboardState,
    settings: Settings,
    theme: KeyboardTheme,
    gapPx: Float,
    cornerPx: Float
) {
    val density = LocalDensity.current
    val key = placed.key

    val isActive = when (val action = key.tapAction) {
        is KeyAction.Modifier -> state.isActive(action.kind)
        is KeyAction.Layer -> state.layer == action.layer
        else -> false
    }

    /*
     * Staying on and applying once are different states, so they cannot look alike.
     *
     * Shift tapped once applies to the next character and then stops; tapped again it
     * locks until turned off. Drawing both as the same solid highlight leaves no way
     * to tell, from the keyboard, which one you are in — and the answer changes what
     * the next keystroke does. Locked is solid; a one-shot is the same colour left
     * translucent, which reads as temporary without needing a legend.
     */
    val isLocked = when (val action = key.tapAction) {
        is KeyAction.Modifier -> state.isLocked(action.kind)
        is KeyAction.Layer -> state.layer == action.layer && state.layerSticky
        else -> false
    }

    // The three opacities are multiplied into the colours rather than applied as a
    // layer alpha, because a layer alpha would fade a key and its label together and
    // the whole point is being able to keep solid glyphs on a glass key.
    val background = when {
        isPressed -> theme.keyPressedBackground
        isLocked -> theme.keyActiveBackground
        isActive -> theme.keyActiveBackground.copy(alpha = theme.keyActiveBackground.alpha * 0.5f)
        else -> theme.keyBackgroundFor(key.style)
    }.let { it.copy(alpha = it.alpha * settings.keyOpacity.coerceIn(0f, 1f)) }

    val contentColor = (
        if (isActive || isPressed) {
            if (theme.isDark) Color.White else theme.keyText
        } else {
            theme.keyTextFor(key.style)
        }
        ).let { it.copy(alpha = it.alpha * settings.keyLabelOpacity.coerceIn(0f, 1f)) }

    // A setting above zero overrides the theme's own width: an outline is what gives
    // a transparent key an edge to aim at, so it has to be reachable without editing
    // the theme.
    val borderWidth = if (settings.keyBorderWidthDp > 0f) settings.keyBorderWidthDp else theme.borderWidthDp
    val borderColor = theme.keyBorder.let {
        it.copy(alpha = it.alpha * settings.keyBorderOpacity.coerceIn(0f, 1f))
    }

    val shape: Shape = when (key.shape) {
        KeyShape.RECT -> RectangleShape
        KeyShape.CIRCLE -> CircleShape
        KeyShape.PILL -> RoundedCornerShape(percent = 50)
        KeyShape.ROUNDED -> RoundedCornerShape(with(density) { cornerPx.toDp() })
    }

    val half = gapPx / 2f
    val widthDp: Dp = with(density) { (placed.width - gapPx).coerceAtLeast(1f).toDp() }
    val heightDp: Dp = with(density) { (placed.height - gapPx).coerceAtLeast(1f).toDp() }
    val fontSize = with(density) {
        val base = placed.height * 0.36f * settings.keyTextScale
        base.coerceIn(10.dp.toPx(), 30.dp.toPx()).toSp()
    }

    Box(
        modifier = Modifier
            .offset { IntOffset((placed.left + half).roundToInt(), (placed.top + half).roundToInt()) }
            .size(widthDp, heightDp)
            .background(background, shape)
            .then(
                if (borderWidth > 0f) Modifier.border(borderWidth.dp, borderColor, shape)
                else Modifier
            ),
        contentAlignment = Alignment.Center
    ) {
        val icon = KeyIcons.vector(key.icon)
        val glyph = KeyIcons.glyph(key.icon)
        when {
            icon != null -> Icon(
                imageVector = icon,
                contentDescription = key.label ?: key.id,
                tint = contentColor,
                modifier = Modifier.size(with(density) { (placed.height * 0.34f).toDp() })
            )
            glyph != null -> Text(
                text = glyph,
                color = contentColor,
                fontSize = fontSize,
                textAlign = TextAlign.Center
            )
            else -> {
                val label = key.effectiveLabel
                Text(
                    text = label,
                    color = contentColor,
                    fontSize = if (label.length > 2) fontSize * 0.62f else fontSize,
                    fontWeight = if (key.style == null) FontWeight.Normal else FontWeight.Medium,
                    maxLines = 2,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 1.dp)
                )
            }
        }

        key.hint?.takeIf { it.isNotEmpty() }?.let { hint ->
            Text(
                text = hint,
                color = theme.keyHintText,
                fontSize = fontSize * 0.45f,
                modifier = Modifier.align(Alignment.TopEnd).padding(horizontal = 3.dp, vertical = 1.dp)
            )
        }

        if (settings.indicatorsEnabled) {
            // A layout may name its own lamp colours; otherwise the user's choice wins,
            // and the theme is the last word.
            key.indicators.forEach { indicator ->
                Indicator(
                    on = state.indicatorOn(indicator.source),
                    style = indicator.style,
                    onColor = indicator.onColor?.let { Color(it.toInt() ) }
                        ?: Color(settings.indicatorOnColor.toInt()),
                    offColor = indicator.offColor?.let { Color(it.toInt()) }
                        ?: Color(settings.indicatorOffColor.toInt()).takeIf { it.alpha > 0f },
                    keyHeight = placed.height
                )
            }
        }
    }
}

/**
 * The lamp on a key.
 *
 * A modifier that is merely on and one that is locked look different, because on a
 * keyboard with sticky modifiers "shift applies to the next letter" and "shift applies
 * until I say otherwise" are the two states people actually confuse.
 */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.Indicator(
    on: Boolean,
    style: com.example.core.layout.IndicatorStyle,
    onColor: Color,
    offColor: Color?,
    keyHeight: Float
) {
    val color = if (on) onColor else (offColor ?: return)
    val density = LocalDensity.current
    val dot = with(density) { (keyHeight * 0.1f).coerceIn(4.dp.toPx(), 9.dp.toPx()).toDp() }
    val bar = with(density) { (keyHeight * 0.045f).coerceAtLeast(2.dp.toPx()).toDp() }

    when (style) {
        com.example.core.layout.IndicatorStyle.DOT_TOP_LEFT ->
            Box(Modifier.align(Alignment.TopStart).padding(3.dp).size(dot).background(color, CircleShape))
        com.example.core.layout.IndicatorStyle.DOT_TOP_RIGHT ->
            Box(Modifier.align(Alignment.TopEnd).padding(3.dp).size(dot).background(color, CircleShape))
        com.example.core.layout.IndicatorStyle.DOT_BOTTOM_LEFT ->
            Box(Modifier.align(Alignment.BottomStart).padding(3.dp).size(dot).background(color, CircleShape))
        com.example.core.layout.IndicatorStyle.DOT_BOTTOM_RIGHT ->
            Box(Modifier.align(Alignment.BottomEnd).padding(3.dp).size(dot).background(color, CircleShape))
        com.example.core.layout.IndicatorStyle.BAR_TOP ->
            Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(horizontal = 6.dp).height(bar).background(color))
        com.example.core.layout.IndicatorStyle.BAR_BOTTOM ->
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 6.dp).height(bar).background(color))
        com.example.core.layout.IndicatorStyle.BAR_LEFT ->
            Box(Modifier.align(Alignment.CenterStart).fillMaxHeight().padding(vertical = 6.dp).width(bar).background(color))
        com.example.core.layout.IndicatorStyle.BAR_RIGHT ->
            Box(Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 6.dp).width(bar).background(color))
        com.example.core.layout.IndicatorStyle.OUTLINE ->
            Box(Modifier.fillMaxSize().border(1.5.dp, color, RoundedCornerShape(6.dp)))
        com.example.core.layout.IndicatorStyle.GLOW ->
            Box(Modifier.fillMaxSize().background(color.copy(alpha = 0.22f), RoundedCornerShape(6.dp)))
        com.example.core.layout.IndicatorStyle.FILL ->
            Box(Modifier.fillMaxSize().background(color.copy(alpha = 0.5f)))
        com.example.core.layout.IndicatorStyle.LABEL_TINT ->
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth(0.4f).height(bar).background(color))
    }
}

@Composable
private fun KeyPreview(placed: PlacedKey, theme: KeyboardTheme, density: androidx.compose.ui.unit.Density) {
    val label = placed.key.effectiveLabel
    if (label.isEmpty()) return
    val heightPx = placed.height * 1.25f
    Popup(
        alignment = Alignment.TopStart,
        offset = IntOffset(placed.left.roundToInt(), (placed.top - heightPx).roundToInt())
    ) {
        Box(
            modifier = Modifier
                .size(
                    with(density) { placed.width.toDp() },
                    with(density) { heightPx.toDp() }
                )
                .background(theme.popupBackground, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                color = theme.popupText,
                fontSize = with(density) { (placed.height * 0.45f).toSp() },
                fontWeight = FontWeight.Medium
            )
        }
    }
}

/**
 * The long-press mini-keyboard.
 *
 * It stays under the finger that opened it: moving left and right changes the
 * highlighted character and lifting commits it, so an accent costs one gesture rather
 * than a press, a release and a second press.
 */
@Composable
private fun LongPressPopup(popup: PopupState, surfaceWidthPx: Float, theme: KeyboardTheme) {
    val density = LocalDensity.current
    val cellWidth = popup.anchor.width
    val cellHeight = popup.anchor.height
    val originX = popupOriginX(popup, surfaceWidthPx, cellWidth)
    val originY = popup.anchor.top - cellHeight * 1.15f

    Popup(
        alignment = Alignment.TopStart,
        offset = IntOffset(originX.roundToInt(), originY.roundToInt())
    ) {
        // Cells are exactly one anchor-key wide with no padding around them, because
        // the finger-tracking maths in moveTouch assumes that pitch.
        Row(
            modifier = Modifier.background(theme.popupBackground, RoundedCornerShape(10.dp))
        ) {
            popup.items.forEachIndexed { index, item ->
                Box(
                    modifier = Modifier
                        .size(
                            with(density) { cellWidth.coerceAtLeast(1f).toDp() },
                            with(density) { cellHeight.coerceAtLeast(1f).toDp() }
                        )
                        .padding(2.dp)
                        .background(
                            if (index == popup.selected) theme.popupSelected else Color.Transparent,
                            RoundedCornerShape(6.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = item,
                        color = theme.popupText,
                        fontSize = with(density) { (cellHeight * 0.4f).toSp() }
                    )
                }
            }
        }
    }
}
