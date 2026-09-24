package com.example.ime

import android.content.ClipData
import android.content.ClipboardManager
import kotlinx.coroutines.withContext
import android.net.Uri
import java.util.UUID
import com.example.core.clipboard.ClipStore
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.example.MainActivity
import com.example.core.ai.AiClient
import com.example.core.ai.AiConfig
import com.example.core.ai.AiTasks
import com.example.core.asr.AsrState
import com.example.core.asr.VoiceController
import com.example.core.config.Settings
import com.example.core.config.SettingsStore
import com.example.core.convert.PasteConversion
import com.example.core.data.ClipboardEntity
import com.example.core.data.KeyboardRepository
import com.example.core.data.WordLists
import com.example.core.discovery.ProviderCatalog
import com.example.core.hitmap.TouchLearner
import com.example.core.layout.ClipboardOp
import com.example.core.layout.IndicatorKeys
import com.example.core.layout.KeyAction
import com.example.core.text.CapitalHow
import com.example.core.text.CapitalMoment
import com.example.core.text.Capitalisation
import com.example.core.layout.KeyDef
import com.example.core.layout.KeyCodes
import com.example.core.layout.LayoutDef
import com.example.core.layout.LayoutJson
import com.example.core.layout.LayoutRepository
import com.example.core.layout.ModifierKind
import com.example.core.layout.PanelId
import com.example.core.layout.CursorAvoidStrategy
import com.example.core.layout.InsetsMode
import com.example.core.layout.PresentationMode
import com.example.core.layout.SwipeDirection
import com.example.core.layout.SwitchTarget
import com.example.core.suggest.Correction
import com.example.core.predict.CompletionEngine
import com.example.core.suggest.SuggestionEngine
import com.example.core.text.TextOps
import com.example.ui.kb.KeyboardHost
import com.example.ui.kb.KeyboardRoot
import com.example.ui.kb.LocalKeyboardHost
import com.example.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * The input method itself.
 *
 * It owns the runtime objects the UI reads, and it is the only place a [KeyAction] is
 * turned into an effect on the user's text. Everything the keyboard can do arrives
 * here as an action — from a key, a panel, a gesture or a suggestion — so there is one
 * switch to read to know what the keyboard is capable of, and one place where the
 * rules about password fields are enforced.
 */
class CustomKeyboardIme : ComposeInputMethodService(), KeyboardHost {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override val state = KeyboardState { SettingsStore.current }

    override val touchLearner: TouchLearner by lazy { TouchLearner(this) }

    override val editor: EditorController by lazy {
        EditorController(
            connection = { currentInputConnection },
            editorInfo = { currentInputEditorInfo },
            settings = { SettingsStore.current }
        )
    }

    override val repository: KeyboardRepository by lazy { KeyboardRepository.get(this) }

    override val suggestions: SuggestionEngine by lazy {
        SuggestionEngine(this, serviceScope, repository) { SettingsStore.current }
    }

    override val completions: CompletionEngine by lazy {
        CompletionEngine(serviceScope) { SettingsStore.current }
    }

    override val voice: VoiceController by lazy {
        VoiceController(this, serviceScope) { SettingsStore.current }
    }

    private val feedbackController: FeedbackController by lazy {
        FeedbackController(this) { SettingsStore.current }
    }

    private var composeView: ComposeView? = null
    private var clipboardManager: ClipboardManager? = null

    private val panelState = mutableStateOf<PanelId?>(null)
    private val layoutIdState = mutableStateOf<String?>(null)
    private val requestedHeightPx = mutableStateOf(0)

    /** The last word committed, so the bigram model knows what followed what. */
    private var previousWord: String = ""

    /**
     * What the last auto-correction changed, so one press of backspace can put it back.
     * Auto-correction without that escape is a keyboard arguing with its user.
     */
    private data class AutoCorrection(val original: String, val corrected: String, val terminator: String)

    private var lastAutoCorrection: AutoCorrection? = null

    /** The app being typed into, for per-app layout memory. */
    private var currentPackage: String? = null

    override val openPanelId: PanelId?
        get() = panelState.value

    override val layout: LayoutDef
        get() = LayoutRepository.resolve(
            layoutIdState.value ?: SettingsStore.current.activeLayoutId,
            SettingsStore.current.enabledLayoutIds
        )

    override val availableLayouts: List<LayoutDef>
        get() = LayoutRepository.all()

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        val tag = "IME.onCreate"
        AppLogger.d(tag, "start")
        try {
            SettingsStore.init(this)
            AppLogger.d(tag, "> SettingsStore.init ok")
            LayoutRepository.init(this)
            ProviderCatalog.init(this)
            WordLists.init(this)
            AppLogger.d(tag, "> LayoutRepository.init ok (${LayoutRepository.all().size} layouts)")
            val activeLayout = layout
            AppLogger.d(tag, "> active layout resolved: id=${activeLayout.id}")
            touchLearner.load(activeLayout.id)
            AppLogger.d(tag, "> touchLearner.load ok")

            clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            clipboardManager?.addPrimaryClipChangedListener(clipboardListener)
            AppLogger.d(tag, "> clipboard listener attached")

            serviceScope.launch {
                voice.state.collect { asr ->
                    state.setFlag(IndicatorKeys.ASR_LISTENING, asr is AsrState.Listening)
                    state.setFlag(
                        IndicatorKeys.ASR_ACTIVE,
                        asr is AsrState.Listening || asr is AsrState.Processing
                    )
                }
            }
            serviceScope.launch {
                suggestions.aiBusy.collect { busy ->
                    suggestionsBusy = busy
                    refreshBusyLamp()
                }
            }
            serviceScope.launch {
                repository.sweepClipboard(SettingsStore.current.clipboardRetentionDays)
            }

            // The touchable region for floating mode is computed from these, and the
            // framework only recomputes insets when the view lays out — so nudge it when
            // the panel is moved or resized, or it would keep capturing the old rectangle.
            serviceScope.launch {
                SettingsStore.state
                    .map { listOf(it.presentation, it.floatingX, it.floatingY, it.floatingWidthDp, it.floatingHeightDp) }
                    .distinctUntilChanged()
                    .collect { composeView?.requestLayout() }
            }
            AppLogger.d(tag, "done")
        } catch (crash: Throwable) {
            AppLogger.e(tag, "failed — the service will not come up", crash)
            throw crash
        }
    }

    override fun onDestroy() {
        clipboardManager?.removePrimaryClipChangedListener(clipboardListener)
        voice.release()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onCreateInputView(): View {
        val tag = "IME.onCreateInputView"
        AppLogger.d(tag, "start")
        val view = try {
            ComposeView(this).also {
                // Not plain ViewGroup.LayoutParams: the parent the framework adds this
                // view into (inside the IME window's own decor) is a FrameLayout, and
                // FrameLayout.onMeasure casts every child's layoutParams to
                // ViewGroup.MarginLayoutParams unconditionally
                // (measureChildWithMargins) — a bare ViewGroup.LayoutParams doesn't
                // extend that, so the very first measure pass threw a
                // ClassCastException. FrameLayout.LayoutParams does extend it, and
                // works as a layoutParams type for any ViewGroup parent this ends up
                // under (only the MarginLayoutParams-level fields are ever read here),
                // so it is the safe general choice, not just the one that matches this
                // specific parent.
                it.layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                it.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
                AppLogger.d(tag, "> ComposeView constructed")
            }
        } catch (crash: Throwable) {
            // Constructing the view itself failing is rare, but with nothing on
            // screen yet there is nothing to fall back to except logging and letting
            // it surface — the alternative is a silent process death either way.
            AppLogger.e(tag, "failed constructing the ComposeView", crash)
            throw crash
        }
        // The composition itself runs later, once the framework attaches this view to
        // a window — outside this method's own try/catch. A throw from in here cannot
        // be caught in place: the Compose compiler rejects a try/catch wrapped around
        // a composable call outright (it cannot guarantee the slot table stays
        // balanced if an exception jumps out of one), so there is no supported way to
        // show a fallback screen instead of the crash. It still gets logged in full —
        // Compose does not intercept the exception either, so it reaches AppLogger's
        // uncaught-exception handler exactly like any other main-thread crash.
        // Must happen before the view can possibly attach to the window — see
        // attachViewTreeOwners' own doc for why it tags the window's decor view
        // rather than this one.
        attachViewTreeOwners()
        AppLogger.d(tag, "> tree owners attached to window decor view")
        view.setContent {
            val settings by SettingsStore.state.collectAsState()
            CompositionLocalProvider(LocalKeyboardHost provides this@CustomKeyboardIme) {
                KeyboardRoot(
                    settings = settings,
                    onSizeChanged = { px, whole -> applyInputViewSize(view, px, whole) }
                )
            }
        }
        // The navigation bar sits on top of us, and since targetSdk 35 nobody moves it.
        //
        // Android used to lay an input view out above the system bars. Apps targeting
        // 35 and later are edge-to-edge whether they ask for it or not, and that now
        // includes the input method's own window: the bottom row of keys ends up
        // underneath the gesture handle or the three buttons, where every press goes to
        // the navigation bar instead. Nothing in the app's own layout can see this,
        // which is why it looked like a drawing bug.
        //
        // The insets are asked for rather than guessed — a handle on one phone, a
        // button bar on another, a bar down the *side* in landscape on a third, and
        // they change while the keyboard is open when somebody rotates. All four sides
        // become padding, so the keys are laid out only where a finger can reach them;
        // see [applyInputViewSize] for how the height follows.
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            takeSystemInsets(v, insets)
            insets
        }

        composeView = view
        AppLogger.d(tag, "done, view returned")
        return view
    }

    /** The system bars and cutouts overlapping our window, as last reported. */
    private var systemInsets: androidx.core.graphics.Insets = androidx.core.graphics.Insets.NONE

    /** The status bar's height; kept clear of only when the view covers the screen. */
    private var statusTopPx: Int = 0

    /**
     * Whether the input view currently spans the whole screen rather than just the
     * keyboard — true whenever something has to be placeable anywhere: a floating
     * panel, free keys, a layout with a floating piece.
     */
    @Volatile
    private var wholeScreen: Boolean = false

    private fun takeSystemInsets(view: View, insets: WindowInsetsCompat) {
        val bars = insets.getInsets(
            WindowInsetsCompat.Type.navigationBars() or WindowInsetsCompat.Type.displayCutout()
        )
        val status = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
        if (bars == systemInsets && status == statusTopPx) return
        systemInsets = bars
        statusTopPx = status
        AppLogger.d("IME.insets", "bars=$bars status=$status wholeScreen=$wholeScreen")
        applyInputViewSize(view, requestedHeightPx.value, wholeScreen, force = true)
    }

    /**
     * The input view's size follows the settings and the orientation, so changing the
     * height slider resizes the live keyboard rather than needing it reopened.
     *
     * Two shapes, and the difference is the fix for keys under the navigation bar:
     *
     *  - **Keyboard-sized**: exactly the keys plus the bottom bar. The bar is added
     *    to the height *and* padded, so the keys keep the size the user chose and the
     *    space under them is extra rather than taken.
     *  - **Whole screen**: MATCH_PARENT, so the window is exactly as tall as the
     *    system allows, and every bar — status bar included — is padding. The first
     *    version asked for "the screen height" in pixels instead, and since Android 15
     *    that figure includes the bars: the view came out taller than the window, and
     *    the bottom of the keyboard was simply cut off under the buttons. Asking for
     *    "as much as there is" cannot be wrong in that way.
     */
    private fun applyInputViewSize(view: View, heightPx: Int, whole: Boolean, force: Boolean = false) {
        if (heightPx <= 0 && !whole) return
        if (!force && requestedHeightPx.value == heightPx && wholeScreen == whole) return
        requestedHeightPx.value = heightPx
        wholeScreen = whole
        val bars = systemInsets
        view.updatePadding(
            left = bars.left,
            top = if (whole) maxOf(bars.top, statusTopPx) else 0,
            right = bars.right,
            bottom = bars.bottom
        )
        view.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            if (whole) ViewGroup.LayoutParams.MATCH_PARENT else heightPx + bars.bottom
        )
        view.requestLayout()
    }

    /**
     * A second chance at the insets, for the window that never dispatches them.
     *
     * Most do on attach. The one that does not would leave the keyboard where it was
     * before this fix — under the bar — with nothing in the log to say why.
     */
    override fun onWindowShown() {
        super.onWindowShown()
        val view = composeView ?: return
        ViewCompat.getRootWindowInsets(view)?.let { takeSystemInsets(view, it) }
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        val tag = "IME.onStartInputView"
        AppLogger.d(tag, "start (restarting=$restarting, package=${info?.packageName})")
        // Caught rather than rethrown: by this point the input view already exists
        // and may already be visible, so a throw here would tear down a keyboard that
        // was doing fine a moment ago over what is, at worst, stale per-editor state
        // (wrong capitalisation, a stale suggestion). Logged in full either way.
        try {
            currentPackage = info?.packageName
            // Before anything reads the cursor. The editor declares where it is when
            // it opens, and a keyboard that does not listen starts every field
            // believing the position is unknown — which is not a neutral state: it
            // makes an empty field indistinguishable from one whose text has simply
            // not been read yet, and sends every cursor movement down the blind path.
            editor.seedSelection(info)
            restoreLayoutForApp()
            AppLogger.d(tag, "> layout for app resolved: ${layout.id}")
            panelState.value = null
            state.clearAllModifiers()
            state.resetLayer(LayoutDef.BASE_LAYER)
            state.clearPending()
            suggestions.clear()
            completions.clear()
            previousWord = ""

            // Only ask for cursor reports when something will act on them: monitoring
            // costs the app a callback per scrolled frame.
            if (SettingsStore.current.avoidCoveringCursor) {
                currentInputConnection?.requestCursorUpdates(
                    android.view.inputmethod.InputConnection.CURSOR_UPDATE_MONITOR
                )
            } else {
                avoidance.clear()
            }

            val sensitive = editor.isSensitive
            state.setFlag(IndicatorKeys.PASSWORD_FIELD, editor.isPasswordField)
            state.setFlag(IndicatorKeys.INCOGNITO, sensitive && SettingsStore.current.incognitoInPasswordFields)
            AppLogger.d(tag, "> editor state read: sensitive=$sensitive")

            applyCapitalisation(CapitalMoment.OPENING, sensitive)
            refreshSuggestions()
            AppLogger.d(tag, "done")
        } catch (crash: Throwable) {
            AppLogger.e(tag, "failed partway through — keyboard may be in a stale state", crash)
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        voice.cancel()
        suggestions.clear()
        completions.clear()
        panelState.value = null
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int,
        newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        editor.onSelectionUpdate(newSelStart, newSelEnd)
        state.setFlag(IndicatorKeys.SELECTION, newSelStart != newSelEnd)
        refreshSuggestions()
    }

    /**
     * Hardware keyboards get the screen keyboard out of the way unless the user asked
     * otherwise. The previous build forced the input view visible unconditionally,
     * which covered a third of the screen for anyone using a physical keyboard.
     */
    override fun onEvaluateInputViewShown(): Boolean =
        SettingsStore.current.showOnHardwareKeyboard || super.onEvaluateInputViewShown()

    /**
     * Landscape full-screen extract mode replaces the app's own field with one drawn by
     * the IME. It is rarely what anyone wants from a custom keyboard, and it hides the
     * context this keyboard's suggestions depend on.
     */
    override fun onEvaluateFullscreenMode(): Boolean = false

    /**
     * How much of the screen the keyboard asks the app to keep clear.
     *
     * There is no correct answer here, which is exactly why it is a setting: a
     * full-width keyboard wants the app pushed above it, free transparent keys over a
     * photo want the app left alone, and a floating panel wants only its own
     * rectangle. All four policies are honoured here, and the region for KEYS_ONLY
     * comes from the rectangles the UI actually drew rather than from a second guess
     * at the geometry.
     */
    override fun onComputeInsets(outInsets: Insets?) {
        super.onComputeInsets(outInsets)
        val insets = outInsets ?: return
        val view = composeView ?: run {
            insets.touchableInsets = Insets.TOUCHABLE_INSETS_VISIBLE
            return
        }
        val settings = SettingsStore.current

        if (wholeScreen) {
            applyWholeScreenRegion(insets, view, settings)
            return
        }

        // Cursor avoidance can override the policy for as long as it applies: the
        // point of RESERVE_SPACE is precisely to claim space the policy would not.
        val reserving = settings.avoidCoveringCursor &&
            settings.cursorAvoidStrategy == CursorAvoidStrategy.RESERVE_SPACE &&
            avoidance.shiftPx > 0f

        when {
            reserving -> {
                insets.touchableInsets = Insets.TOUCHABLE_INSETS_VISIBLE
            }

            settings.insetsMode == InsetsMode.NONE -> {
                // Nothing is reserved and only the keys take touches: the app keeps
                // its full height and may well be covered, which is what was asked for.
                applyKeyRegion(insets, view, fallbackToVisible = false)
                insets.contentTopInsets = view.height
                insets.visibleTopInsets = view.height
            }

            settings.insetsMode == InsetsMode.KEYS_ONLY -> {
                applyKeyRegion(insets, view, fallbackToVisible = true)
                insets.contentTopInsets = view.height
                insets.visibleTopInsets = view.height
            }

            settings.insetsMode == InsetsMode.PANEL_ONLY ||
                settings.presentation == PresentationMode.FLOATING -> {
                applyPanelRegion(insets, view, settings)
            }

            else -> insets.touchableInsets = Insets.TOUCHABLE_INSETS_VISIBLE
        }
    }

    /**
     * Restricts touches to the keys themselves, so the gaps between free-floating keys
     * belong to the app underneath.
     *
     * [fallbackToVisible] decides what happens before the first frame has reported any
     * keys: KEYS_ONLY falls back to the whole view, because a keyboard that swallows
     * nothing is a keyboard that cannot be typed on, while NONE genuinely means none.
     */
    private fun applyKeyRegion(insets: Insets, view: View, fallbackToVisible: Boolean) {
        val rects = keyRects
        if (rects.isEmpty()) {
            insets.touchableInsets =
                if (fallbackToVisible) Insets.TOUCHABLE_INSETS_VISIBLE else Insets.TOUCHABLE_INSETS_REGION
            if (!fallbackToVisible) insets.touchableRegion.setEmpty()
            return
        }
        insets.touchableInsets = Insets.TOUCHABLE_INSETS_REGION
        insets.touchableRegion.setEmpty()
        rects.forEach { insets.touchableRegion.union(it) }
    }

    /**
     * The view covers the screen, so "the view" is no longer "the keyboard".
     *
     * Before this, a layout with one floating piece made the whole screen the
     * keyboard's: every touch anywhere was swallowed, and the app was told the
     * keyboard began at the top of the screen — so it was resized into nothing, which
     * is what the settings screen looked like behind the Workbench layout.
     *
     * Touches: the pieces themselves (unless the policy restricts them to keys), plus
     * every key and every pressable row, which are what the restricted policies keep.
     * Space kept clear: only what a docked piece asks for, and only under FULL or
     * PANEL_ONLY — a floating block cannot ask the app to keep a hole in the middle of
     * the screen, so it never moves the app at all.
     */
    private fun applyWholeScreenRegion(insets: Insets, view: View, settings: Settings) {
        val restricted = settings.insetsMode == InsetsMode.KEYS_ONLY || settings.insetsMode == InsetsMode.NONE
        val panels = panelRectsBySource.values.toList()
        val region = insets.touchableRegion
        region.setEmpty()
        if (!restricted) panels.forEach { region.union(it.rect) }
        keyRects.forEach { region.union(it) }

        if (region.isEmpty) {
            // Nothing drawn yet. The floating panel's rectangle can be worked out from
            // settings; anything else is a frame away from reporting, and until then
            // the whole screen is not ours to take.
            if (settings.presentation == PresentationMode.FLOATING && settings.insetsMode != InsetsMode.NONE) {
                applyPanelRegion(insets, view, settings)
                return
            }
        }

        insets.touchableInsets = Insets.TOUCHABLE_INSETS_REGION
        val keepClear = if (restricted) null
        else panels.filter { it.reservesContent }.minOfOrNull { it.rect.top }
        val top = keepClear ?: view.height
        insets.contentTopInsets = top
        insets.visibleTopInsets = top
    }

    /** The floating panel's own rectangle, and nothing around it. */
    private fun applyPanelRegion(insets: Insets, view: View, settings: Settings) {
        if (settings.presentation != PresentationMode.FLOATING) {
            // PANEL_ONLY on a docked keyboard means the keyboard's own strip of the
            // screen, which is what the view already is.
            insets.touchableInsets = Insets.TOUCHABLE_INSETS_VISIBLE
            return
        }

        // In floating mode the input view is full-height so the panel can be dragged
        // anywhere, but only the panel itself may take touches — everything around it
        // belongs to the app underneath. The region therefore has to be the panel's
        // own rectangle, not the view's: setting it to the view would capture the
        // whole screen, which is the opposite of floating.
        val density = resources.displayMetrics.density
        fun px(dp: Float) = (dp * density).toInt()

        val handleHeightDp = 26f
        val width = px(settings.floatingWidthDp)
        val height = px(
            (if (settings.floatingHeightDp > 0f) settings.floatingHeightDp else 240f) + handleHeightDp
        )
        // The panel is laid out inside the system bars' padding, so its origin is too.
        val innerW = view.width - view.paddingLeft - view.paddingRight
        val innerH = view.height - view.paddingTop - view.paddingBottom
        val left = view.paddingLeft + px(settings.floatingX).coerceIn(0, (innerW - width).coerceAtLeast(0))
        val top = view.paddingTop + px(settings.floatingY).coerceIn(0, (innerH - height).coerceAtLeast(0))

        insets.touchableInsets = Insets.TOUCHABLE_INSETS_REGION
        insets.touchableRegion.set(Rect(left, top, left + width, top + height))
        // The app keeps its full height: a floating keyboard does not push it up.
        insets.contentTopInsets = view.height
        insets.visibleTopInsets = view.height
    }

    /**
     * Where the text cursor is, when the app is willing to say.
     *
     * Only requested when the user asked for cursor avoidance, because monitoring
     * costs the app a callback per frame of scrolling and buys nothing unless
     * something acts on it.
     */
    override fun onUpdateCursorAnchorInfo(info: android.view.inputmethod.CursorAnchorInfo?) {
        super.onUpdateCursorAnchorInfo(info)
        val settings = SettingsStore.current
        if (!settings.avoidCoveringCursor || info == null) {
            avoidance.clear()
            return
        }

        val view = composeView ?: return
        val bottom = info.getInsertionMarkerBottom()
        if (bottom.isNaN() || bottom <= 0f) {
            avoidance.clear()
            return
        }

        // The insertion marker is in screen coordinates; the keyboard's top edge is
        // the screen height minus however much of it the keyboard occupies — unless
        // the view covers the screen, when it is wherever the docked piece begins.
        val screenHeight = resources.displayMetrics.heightPixels
        val keyboardTop = if (wholeScreen) {
            val onScreen = IntArray(2).also { view.getLocationOnScreen(it) }
            val inWindow = IntArray(2).also { view.getLocationInWindow(it) }
            val dockTop = panelRectsBySource.values.filter { it.reservesContent }
                .minOfOrNull { it.rect.top } ?: (inWindow[1] + view.height)
            (onScreen[1] - inWindow[1] + dockTop).toFloat()
        } else (screenHeight - view.height).toFloat()
        val margin = settings.cursorAvoidMarginDp * resources.displayMetrics.density
        val overlap = bottom + margin - keyboardTop

        if (overlap <= 0f) {
            avoidance.clear()
            return
        }

        when (settings.cursorAvoidStrategy) {
            CursorAvoidStrategy.FADE -> {
                avoidance.fade = settings.cursorAvoidFadeTo.coerceIn(0.05f, 1f)
                avoidance.shiftPx = 0f
            }
            CursorAvoidStrategy.MOVE_PANEL -> {
                // Only a keyboard that can be somewhere else can move out of the way.
                val movable = settings.presentation == PresentationMode.FLOATING ||
                    settings.presentation == PresentationMode.FREE
                avoidance.shiftPx = if (movable) overlap else 0f
                avoidance.fade = if (movable) 1f else settings.cursorAvoidFadeTo.coerceIn(0.05f, 1f)
            }
            CursorAvoidStrategy.RESERVE_SPACE -> {
                avoidance.shiftPx = overlap
                avoidance.fade = 1f
                composeView?.requestLayout()
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // Right Alt on a hardware keyboard is AltGr, and on a Polish layout that is
        // how the language is written. The layer already exists and the soft board
        // already reaches it; this is the same layer reached from the other kind of
        // keyboard, so there is no second table of characters to keep in step.
        if (event.action == KeyEvent.ACTION_DOWN &&
            (event.metaState and KeyEvent.META_ALT_RIGHT_ON) != 0 &&
            (event.metaState and KeyEvent.META_CTRL_ON) == 0
        ) {
            altGrCharacter(keyCode, event.isShiftPressed)?.let { text ->
                editor.commitText(text, applyConventions = false)
                refreshSuggestions()
                return true
            }
        }
        // The same chord from a hardware keyboard, answered the same way. undo() sends
        // it on when it has nothing of its own, so nothing is taken away from an app
        // that already handled it.
        if (event.action == KeyEvent.ACTION_DOWN && event.isCtrlPressed && !event.isAltPressed) {
            when (keyCode) {
                KeyEvent.KEYCODE_Z -> {
                    if (event.isShiftPressed) editor.redo() else editor.undo()
                    refreshSuggestions()
                    return true
                }
                KeyEvent.KEYCODE_Y -> {
                    editor.redo()
                    refreshSuggestions()
                    return true
                }
            }
        }
        if (SettingsStore.current.volumeKeysResize &&
            (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) &&
            isInputViewShown
        ) {
            val delta = if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) 0.02f else -0.02f
            val landscape = resources.configuration.screenWidthDp > resources.configuration.screenHeightDp
            SettingsStore.update { s ->
                if (landscape && s.separateLandscapeSize) {
                    s.copy(heightLandscape = (s.heightLandscape + delta).coerceIn(0.15f, 0.85f))
                } else {
                    s.copy(heightPortrait = (s.heightPortrait + delta).coerceIn(0.15f, 0.85f))
                }
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    /**
     * What the current layout's AltGr level puts on a hardware key, if anything.
     *
     * Read off the layout rather than from a table here, so a layout that defines its
     * own AltGr level — a user's, or one that arrived as data — works with a hardware
     * keyboard without anybody adding a case. Null means this layout has nothing to
     * say about that key, and the event goes on to the app untouched.
     */
    private fun altGrCharacter(keyCode: Int, shifted: Boolean): String? {
        val level = if (shifted) LayoutDef.ALTGR_SHIFT_LAYER else LayoutDef.ALTGR_LAYER
        val layer = layout.layer(level) ?: layout.layer(LayoutDef.ALTGR_LAYER) ?: return null
        val id = KeyCodes.label(keyCode).takeIf { it.length == 1 }?.lowercase() ?: return null
        val key = layer.rows.asSequence().flatMap { it.keys.asSequence() }
            .firstOrNull { it.id == id } ?: return null
        val text = (key.tapAction as? KeyAction.Text)?.text ?: return null
        // Only when the AltGr level actually changes this key. Otherwise Right Alt
        // over a letter with no AltGr meaning would type the plain letter and swallow
        // whatever shortcut the app had bound to it.
        return text.takeIf { !it.equals(id, ignoreCase = true) }
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (SettingsStore.current.volumeKeysResize &&
            (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) &&
            isInputViewShown
        ) {
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    // -----------------------------------------------------------------------
    // KeyboardHost
    // -----------------------------------------------------------------------

    override fun feedback(key: KeyDef?) = feedbackController.onKeyPress(key)

    /**
     * A long swipe across the keyboard that no key claimed. The bound value is an
     * action in the same notation layouts use, so a gesture can do anything a key can.
     */
    override fun performSurfaceGesture(direction: SwipeDirection): Boolean {
        val settings = SettingsStore.current
        val raw = when (direction) {
            SwipeDirection.UP, SwipeDirection.UP_LEFT, SwipeDirection.UP_RIGHT -> settings.gestureSwipeUp
            SwipeDirection.DOWN, SwipeDirection.DOWN_LEFT, SwipeDirection.DOWN_RIGHT -> settings.gestureSwipeDown
            SwipeDirection.LEFT -> settings.gestureSwipeLeft
            SwipeDirection.RIGHT -> settings.gestureSwipeRight
        }
        if (raw.isBlank()) return false
        val action = try {
            LayoutJson.parseAction(JSONObject(LayoutJson.stripCodeFence(raw)))
        } catch (e: Exception) {
            LayoutJson.parseAction(raw)
        }
        if (action == null || action is KeyAction.None) return false
        perform(action)
        return true
    }

    override fun openPanel(panel: PanelId?) {
        panelState.value = panel
        if (panel == null) voice.dismiss()
    }

    override fun selectLayout(id: String) {
        layoutIdState.value = id
        SettingsStore.update { it.copy(activeLayoutId = id) }
        state.resetLayer(LayoutDef.BASE_LAYER)
        touchLearner.load(id)
        rememberLayoutForApp(id)
    }

    /**
     * Per-app layout memory: a numeric pad in the calculator, the full PC layout in a
     * terminal, without switching by hand every time.
     */
    private fun restoreLayoutForApp() {
        if (!SettingsStore.current.rememberLayoutPerApp) return
        val packageName = currentPackage ?: return
        val remembered = appLayoutPrefs().getString(packageName, null) ?: return
        if (remembered == layout.id) return
        if (LayoutRepository.byId(remembered) == null) return
        layoutIdState.value = remembered
        touchLearner.load(remembered)
    }

    private fun rememberLayoutForApp(id: String) {
        if (!SettingsStore.current.rememberLayoutPerApp) return
        val packageName = currentPackage ?: return
        appLayoutPrefs().edit().putString(packageName, id).apply()
    }

    private fun appLayoutPrefs() = getSharedPreferences("app_layouts", Context.MODE_PRIVATE)

    override fun openApp(route: String?) {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            if (route != null) putExtra(MainActivity.EXTRA_ROUTE, route)
        }
        try {
            AppLogger.d("IME.openApp", "starting the settings app (route=$route)")
            startActivity(intent)
        } catch (e: Exception) {
            AppLogger.e("IME.openApp", "could not open the settings app", e)
        }
    }

    /**
     * Sends the user to the app to grant the microphone permission.
     *
     * An input method cannot ask for a runtime permission itself — there is no
     * activity to attach the dialog to — so the settings app asks on its behalf. The
     * keyboard hides first: it is a window sitting above the activity that is about
     * to put a dialog up, and leaving it there means handing the user a permission
     * prompt with a keyboard on top of it.
     */
    override fun requestMicrophonePermission() {
        AppLogger.d("IME.mic", "hiding the keyboard and opening the app to ask for RECORD_AUDIO")
        requestHideSelf(0)
        openApp(MainActivity.ROUTE_PERMISSIONS)
    }

    override fun hideKeyboard() {
        requestHideSelf(0)
    }

    override fun switchIme() {
        try {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.showInputMethodPicker()
        } catch (e: Exception) {
            AppLogger.e("IME", "Could not open the input method picker", e)
        }
    }

    override val avoidance = com.example.ui.kb.AvoidanceState()

    override val notices = com.example.ui.kb.NoticeBoard()

    /**
     * What was on screen when the user last asked for it to go to the AI, and when.
     * Context for the next task — "reply to this" — never its input, and forgotten
     * once it is old or the user has moved to another app.
     */
    private var screenContext: Triple<Long, String?, String>? = null

    private val performer: com.example.io.Performer by lazy {
        com.example.io.Performer(object : com.example.io.PerformerHost {
            override val context: Context get() = this@CustomKeyboardIme

            override fun sendKey(keyCode: Int) = sendDownUpKeyEvents(keyCode)

            override fun nearbyText(): String = textForAi()

            override fun commit(text: String) = editor.commitText(text, applyConventions = false)

            override fun copy(text: String, label: String) {
                putOnClipboard(ClipData.newPlainText(label, text))
            }

            override fun offerToAi(text: String) {
                screenContext = Triple(System.currentTimeMillis(), currentPackage, text)
            }

            override fun notice(text: String, actionLabel: String?, action: (() -> Unit)?) {
                AppLogger.d("IO", text)
                if (SettingsStore.current.suggestionsEnabled || openPanelId != null) {
                    notices.post(text, actionLabel, action)
                } else {
                    // No strip to show it on; a toast is the one surface left, and
                    // an action it cannot carry is named in the text instead.
                    android.widget.Toast.makeText(
                        this@CustomKeyboardIme,
                        if (actionLabel != null) "$text — $actionLabel in the app's settings" else text,
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
        })
    }

    /** The screen text handed to the AI, if it is still about what is in front. */
    private fun freshScreenContext(): String? {
        val (at, pkg, text) = screenContext ?: return null
        val fresh = System.currentTimeMillis() - at < 10 * 60_000L && pkg == currentPackage
        if (!fresh) screenContext = null
        return text.takeIf { fresh }
    }

    /**
     * The last key rectangles the UI drew — and the toolbar and rows, which are just
     * as pressable — in window pixels.
     *
     * Read by the restricted policies, and whenever the view covers the screen. Kept
     * as a plain field rather than
     * observable state because nothing recomposes on it — the framework asks for
     * insets on its own schedule, and this is simply the freshest answer available
     * when it does.
     */
    private val keyRectsBySource = java.util.concurrent.ConcurrentHashMap<String, List<Rect>>()

    private val keyRects: List<Rect>
        get() = keyRectsBySource.values.flatten()

    override fun reportKeyRects(sourceId: String, rects: List<Rect>) {
        val previous = if (rects.isEmpty()) keyRectsBySource.remove(sourceId)
        else keyRectsBySource.put(sourceId, rects)
        if ((previous ?: emptyList()) != rects) regionChanged()
    }

    /** One whole piece of the keyboard, and whether the app should stay above it. */
    private data class PanelRect(val rect: Rect, val reservesContent: Boolean)

    private val panelRectsBySource = java.util.concurrent.ConcurrentHashMap<String, PanelRect>()

    override fun reportPanelRect(sourceId: String, rect: Rect?, reservesContent: Boolean) {
        val next = rect?.let { PanelRect(it, reservesContent) }
        val previous = if (next == null) panelRectsBySource.remove(sourceId)
        else panelRectsBySource.put(sourceId, next)
        if (previous != next) regionChanged()
    }

    /**
     * The framework recomputes insets on a traversal, not on a whim, so a keyboard
     * whose pieces moved without one would keep the stale region — and a stale region
     * is keys that do not answer, or an app that does not get its touches back.
     */
    private fun regionChanged() {
        val settings = SettingsStore.current
        if (wholeScreen || settings.insetsMode == InsetsMode.KEYS_ONLY || settings.insetsMode == InsetsMode.NONE) {
            composeView?.post { composeView?.requestLayout() }
        }
    }

    override fun textForAi(): String {
        val selected = editor.selectedText()?.toString()
        if (!selected.isNullOrBlank()) return selected
        return editor.allText(4000).toString().takeLast(SettingsStore.current.aiContextChars)
    }

    // -----------------------------------------------------------------------
    // Action execution — the one switch that says what this keyboard can do
    // -----------------------------------------------------------------------

    override fun putOnClipboard(clip: ClipData) {
        val manager = clipboardManager
        if (manager == null) {
            AppLogger.e("Clipboard", "no clipboard manager; cannot hand over ${clip.itemCount} items")
            return
        }
        // Setting this fires our own listener, which would re-record everything the
        // user just assembled as if they had copied it. Suppressing the next change is
        // cheaper and more predictable than trying to recognise our own clip later.
        suppressNextClipboardChange = true
        manager.setPrimaryClip(clip)
        AppLogger.d("Clipboard", "handed over ${clip.itemCount} item(s)")
    }

    override fun perform(action: KeyAction) {
        val settings = SettingsStore.current
        when (action) {
            is KeyAction.None -> Unit

            is KeyAction.Text -> commitText(action.text, settings)

            is KeyAction.SendKey -> {
                editor.sendKey(action.keyCode, action.metaState or state.metaState())
                state.consumeOneShots()
                state.consumeOneShotLayer()
            }

            is KeyAction.Modifier -> state.pressModifier(action.kind, action.mode)

            is KeyAction.Layer -> state.switchLayer(action.layer, action.mode)

            is KeyAction.SwitchLayout -> switchLayout(action.layoutId, action.target, settings)

            is KeyAction.SwitchLanguage -> switchLanguage(action.locale, action.target, settings)

            is KeyAction.Backspace -> {
                // Backspace immediately after an auto-correction undoes it rather than
                // deleting a character, which is the only thing that makes replacing
                // what someone typed acceptable.
                if (!revertAutoCorrection()) editor.backspace(action.unit)
                state.clearPending()
                refreshSuggestions()
            }

            is KeyAction.ForwardDelete -> {
                editor.forwardDelete(action.unit)
                refreshSuggestions()
            }

            is KeyAction.Enter -> {
                learnCurrentWord()
                editor.performEnter()
                state.consumeOneShots()
                previousWord = ""
                refreshSuggestions()
            }

            is KeyAction.Space -> commitText(" ", settings)

            is KeyAction.MoveCursor -> {
                // Shift and an arrow selects. It is what every physical keyboard does
                // and what the cursor panel has always looked like it should do; until
                // now only a long press could extend, which is undiscoverable and
                // cannot be repeated to grow a selection.
                //
                // Shift is deliberately not consumed here, so a run of arrows keeps
                // extending rather than stopping after the first — which is what
                // holding shift does on a keyboard with a shift to hold.
                val extend = action.extendSelection || state.isActive(ModifierKind.SHIFT)
                editor.moveCursor(action.direction, action.unit, extend)
                state.consumeOneShotLayer()
            }

            is KeyAction.Select -> editor.select(action.unit)

            is KeyAction.Clipboard -> performClipboard(action.op)

            is KeyAction.Undo -> editor.undo()
            is KeyAction.Redo -> editor.redo()

            is KeyAction.OpenPanel -> openPanel(if (openPanelId == action.panel) null else action.panel)

            is KeyAction.Voice -> {
                if (voice.isActive) voice.stop() else openPanel(PanelId.VOICE)
            }

            is KeyAction.Ai -> runAiTask(action.taskId)

            is KeyAction.Macro -> action.steps.forEach { perform(it) }

            is KeyAction.DeadKey -> state.setDeadKey(action.combining)

            is KeyAction.Compose -> state.startCompose()

            is KeyAction.UnicodeInput -> state.startUnicode()

            is KeyAction.RepeatLast -> editor.commitRepeatLast()

            is KeyAction.ShowSettings -> openApp()

            is KeyAction.SwitchIme -> switchIme()

            is KeyAction.HideKeyboard -> hideKeyboard()

            is KeyAction.Presentation -> applyPresentation(action.mode, settings)

            is KeyAction.Do -> performer.run(action.command)
        }
    }

    /**
     * Commits a character, after giving whatever composition is in progress first
     * refusal on it: a pending dead key, a Unicode escape or a compose sequence all
     * swallow the keystroke and produce something else.
     */
    private fun commitText(raw: String, settings: Settings) {
        val pendingUnicode = state.unicodeBuffer
        if (pendingUnicode != null) {
            if (raw.length == 1 && raw[0].isLetterOrDigit()) {
                state.appendUnicode(raw)
                return
            }
            val hex = state.endUnicode()
            TextOps.codePointFromHex(hex.orEmpty())?.let { editor.commitText(it, applyConventions = false) }
            if (raw == " ") return
        }

        val pendingCompose = state.composeBuffer
        if (pendingCompose != null) {
            val sequence = pendingCompose + raw
            val resolved = ComposeSequences.resolve(sequence)
            when {
                resolved != null -> {
                    state.endCompose()
                    editor.commitText(resolved, applyConventions = false)
                    afterCharacter(resolved, settings)
                    return
                }
                ComposeSequences.isPrefix(sequence) -> {
                    state.appendCompose(raw)
                    return
                }
                else -> {
                    state.endCompose()
                    editor.commitText(sequence, applyConventions = false)
                    afterCharacter(sequence, settings)
                    return
                }
            }
        }

        val dead = state.pendingDeadKey
        if (dead != null) {
            state.setDeadKey(null)
            val combined = if (dead == "̸") TextOps.applyStroke(raw) else TextOps.applyDeadKey(dead, raw)
            editor.commitText(combined, applyConventions = false)
            afterCharacter(combined, settings)
            return
        }

        // Ctrl or Alt held turns a letter into a key event, so Ctrl+C reaches the app.
        if (state.wantsRawKeyEvents && raw.length == 1) {
            val code = KeyCodes.code(raw)
            if (code != null && code != 0) {
                // Undo is the one chord the keyboard answers itself before passing on.
                // There is no InputConnection call for it, so Ctrl+Z is a request that
                // a great many modern text fields simply never implemented — which is
                // why paste worked here and undo did nothing. The keyboard's own
                // history is tried first and the chord is still sent when it has
                // nothing to offer, so an app that does handle it is not cut off.
                if (state.isActive(ModifierKind.CTRL)) {
                    val shifted = state.isActive(ModifierKind.SHIFT)
                    if (code == KeyEvent.KEYCODE_Z) {
                        if (shifted) editor.redo() else editor.undo()
                        state.consumeOneShots()
                        refreshSuggestions()
                        return
                    }
                    if (code == KeyEvent.KEYCODE_Y) {
                        editor.redo()
                        state.consumeOneShots()
                        refreshSuggestions()
                        return
                    }
                }
                editor.sendKey(code, state.metaState())
                state.consumeOneShots()
                return
            }
        }

        val shifted = state.isActive(ModifierKind.SHIFT)
        val text = if (shifted && raw.length <= 2 && raw != raw.uppercase()) raw.uppercase() else raw
        editor.commitText(text, shiftActive = shifted)
        afterCharacter(text, settings)
    }

    private fun afterCharacter(text: String, settings: Settings) {
        state.consumeOneShots()
        state.consumeOneShotLayer()

        // What landed in the field can differ from what the key said — a capital, a
        // curly quote, punctuation with a space appended — so measure against that.
        val committed = editor.lastCommit.ifEmpty { text }
        val endsWord = committed.isNotEmpty() && !TextOps.isWordChar(committed.trimEnd().lastOrNull() ?: ' ')
        if (endsWord) {
            val completed = TextOps.currentWord(editor.textBefore(200).dropLast(committed.length))
            learnCurrentWordBefore(committed)
            if (settings.autoCorrect && !editor.isSensitive && completed.isNotBlank()) {
                autoCorrect(completed, committed)
            } else {
                lastAutoCorrection = null
            }
        } else {
            lastAutoCorrection = null
        }
        refreshSuggestions()

        if (endsWord) applyCapitalisation(CapitalMoment.TYPING, editor.isSensitive)
    }

    /**
     * Asks the capitalisation rules what to do here, and does it.
     *
     * One function for both moments, because the difference between them is a field on
     * a rule rather than a branch in the code — which is the whole point of the rules
     * being data. What used to be "and also, not when opening" hardcoded next to a
     * boolean is now one word in one rule the user can edit.
     */
    private fun applyCapitalisation(moment: CapitalMoment, sensitive: Boolean) {
        val settings = SettingsStore.current
        if (!settings.autoCapitalize || sensitive) return

        val rules = Capitalisation.fromJson(settings.capitalisationRulesJson)
        val before = editor.textBefore(200)
        // At the start of the field only when that is actually known — an unread field
        // looks exactly like an empty one from here, and reading the second as the
        // first is what made the keyboard capitalise mid-sentence.
        val atStart = when (moment) {
            CapitalMoment.OPENING -> editor.isKnownEmpty
            CapitalMoment.TYPING -> before.isEmpty()
        }

        when (Capitalisation.decide(rules, before, atStart, moment)) {
            CapitalHow.NOTHING -> Unit
            // One shot: a capital is wanted for the next letter, not for the rest of
            // the session. Without this the shift never came down again.
            CapitalHow.SHIFT -> state.setModifier(ModifierKind.SHIFT, active = true, oneShot = true)
            CapitalHow.FIX_AFTER_WORD -> if (moment == CapitalMoment.TYPING) fixLastWordCase()
        }
    }

    /**
     * Capitalises the word that was just finished, after the fact.
     *
     * Registered as an auto-correction rather than done quietly, so backspace puts it
     * back. This lane changes text the user already typed, and the rule this project
     * holds to is that nothing of that kind is acceptable unless one press undoes it.
     */
    private fun fixLastWordCase() {
        val committed = editor.lastCommit
        if (committed.isEmpty()) return
        val before = editor.textBefore(240)
        val word = TextOps.currentWord(before.dropLast(committed.length))
        if (word.isBlank()) return

        val fixed = Capitalisation.capitalise(word)
        if (fixed == word) return

        // The situation has to have held where the word *started*, not where it ended.
        val beforeWord = before.dropLast(committed.length + word.length)
        val rules = Capitalisation.fromJson(SettingsStore.current.capitalisationRulesJson)
        val here = Capitalisation.action(
            rules,
            Capitalisation.situations(beforeWord, atStartOfField = beforeWord.isEmpty()),
            CapitalMoment.TYPING
        )
        if (here != CapitalHow.FIX_AFTER_WORD) return

        // Spelling correction is the other rewrite that happens at a word boundary, and
        // it runs off the input path. Both re-check that the text still ends the way
        // they left it, so whichever lands first wins and the other stands down rather
        // than corrupting anything — but they do not yet compose. Only reachable by
        // choosing FIX_AFTER_WORD, which is not in the default rules; making the two
        // cooperate is the next step if that stops being true.
        val tail = word + committed
        if (!editor.textBefore(tail.length + 2).endsWith(tail)) return
        editor.batch {
            editor.deleteExactly(tail.length)
            editor.commitText(fixed + committed, applyConventions = false)
        }
        lastAutoCorrection = AutoCorrection(word, fixed, committed)
    }

    private fun learnCurrentWord() {
        val word = editor.currentWord()
        if (word.isNotBlank()) {
            suggestions.learn(previousWord, word, editor.isSensitive, layout.locale.orEmpty())
            previousWord = word
        }
    }

    /** The word ended by [terminator]; it sits just before the character just typed. */
    private fun learnCurrentWordBefore(terminator: String) {
        val before = editor.textBefore(160).dropLast(terminator.length)
        val word = TextOps.currentWord(before)
        if (word.isNotBlank()) {
            suggestions.learn(previousWord, word, editor.isSensitive, layout.locale.orEmpty())
            previousWord = word
        }
    }

    /**
     * Looks for a better spelling of the word just finished and, if there is a clear
     * one, swaps it in. Runs off the input path, and re-checks that the text still ends
     * the way it did before touching anything, because the user keeps typing.
     */
    private fun autoCorrect(word: String, terminator: String) {
        lastAutoCorrection = null
        serviceScope.launch {
            val replacement = Correction.suggest(
                context = this@CustomKeyboardIme,
                repository = repository,
                word = word,
                locale = layout.locale.orEmpty()
            ) ?: return@launch
            val tail = word + terminator
            if (!editor.textBefore(tail.length + 2).endsWith(tail)) return@launch
            editor.batch {
                editor.deleteExactly(tail.length)
                editor.commitText(replacement + terminator, applyConventions = false)
            }
            lastAutoCorrection = AutoCorrection(word, replacement, terminator)
        }
    }

    /** Puts back what the user actually typed. Returns false if there is nothing to undo. */
    private fun revertAutoCorrection(): Boolean {
        val correction = lastAutoCorrection ?: return false
        lastAutoCorrection = null
        val applied = correction.corrected + correction.terminator
        if (!editor.textBefore(applied.length + 2).endsWith(applied)) return false
        editor.batch {
            editor.deleteExactly(applied.length)
            editor.commitText(correction.original + correction.terminator, applyConventions = false)
        }
        return true
    }

    private fun refreshSuggestions() {
        val before = editor.textBefore(SettingsStore.current.aiContextChars.coerceAtLeast(64))
        val sensitive = editor.isSensitive
        suggestions.update(
            textBeforeCursor = before,
            sensitive = sensitive,
            locale = layout.locale.orEmpty()
        )
        // Same trigger, two lanes. The engine debounces and refuses sensitive fields
        // itself, so this stays one call rather than a policy scattered over the
        // service — every place text changes already ends up here.
        completions.request(before, sensitive)
    }

    /**
     * Runs a named AI task over the selection (or the surrounding text) and puts the
     * result back. With no task named, or a name that no longer exists, this opens the
     * panel so the user can choose — a key never simply does nothing.
     */
    private fun runAiTask(taskId: String) {
        val settings = SettingsStore.current
        if (!settings.aiEnabled || taskId.isBlank()) {
            openPanel(PanelId.AI_TOOLS)
            return
        }
        if (editor.isSensitive) return
        val task = AiTasks.byId(settings.aiCustomTasksJson, taskId)
        if (task == null) {
            openPanel(PanelId.AI_TOOLS)
            return
        }
        val source = textForAi()
        val onScreen = freshScreenContext()
        // An empty field is fine when the screen says what to answer: "reply to
        // this post" starts from nothing typed.
        if (source.isBlank() && onScreen == null) return

        state.setFlag(IndicatorKeys.AI_BUSY, true)
        serviceScope.launch {
            val prompt = task.render(source)
            val response = AiClient.complete(
                config = AiConfig.from(settings, maxTokens = 800),
                systemPrompt = task.systemPrompt,
                userPrompt = if (onScreen == null) prompt else
                    "For context, this is what is on the screen (do not rewrite it):\n" +
                        onScreen.take(6000) + "\n\n---\n\n" + prompt
            )
            state.setFlag(IndicatorKeys.AI_BUSY, false)
            response.onSuccess { raw ->
                val text = raw.trim()
                if (text.isEmpty()) return@onSuccess
                if (task.replaceInPlace) editor.replaceSelectionOrAll(text)
                else editor.commitText(text, applyConventions = false)
            }.onFailure {
                AppLogger.e("IME", "AI task ${task.id} failed", it)
                openPanel(PanelId.AI_TOOLS)
            }
        }
    }

    private fun switchLayout(id: String?, target: SwitchTarget, settings: Settings) {
        if (id != null) {
            selectLayout(id)
            return
        }
        val rotation = settings.enabledLayoutIds.filter { LayoutRepository.byId(it) != null }
        if (rotation.isEmpty()) return
        val index = rotation.indexOf(layout.id)
        val next = when {
            index < 0 -> 0
            target == SwitchTarget.NEXT -> (index + 1) % rotation.size
            else -> (index - 1 + rotation.size) % rotation.size
        }
        selectLayout(rotation[next])
    }

    private fun switchLanguage(locale: String?, target: SwitchTarget, settings: Settings) {
        val candidates = settings.enabledLayoutIds
            .mapNotNull { LayoutRepository.byId(it) }
            .filter { !it.locale.isNullOrBlank() }
        if (locale != null) {
            candidates.firstOrNull { it.locale == locale }?.let { selectLayout(it.id) }
            return
        }
        if (candidates.isEmpty()) {
            // No second language configured: offer the system picker rather than
            // silently doing nothing, which is what a globe key doing nothing feels like.
            switchIme()
            return
        }
        val index = candidates.indexOfFirst { it.id == layout.id }
        val next = when {
            index < 0 -> 0
            target == SwitchTarget.NEXT -> (index + 1) % candidates.size
            else -> (index - 1 + candidates.size) % candidates.size
        }
        selectLayout(candidates[next].id)
    }

    /**
     * Paste, or read the thing being pasted.
     *
     * A picture or a recording on the clipboard is almost never wanted *as* a picture
     * in a text field — there is nowhere for it to go. What the person wants is what is
     * written in it, and paste is the moment they ask for it without knowing there was
     * anything to ask.
     *
     * Everything about this is conditional on the setting being on and a provider being
     * configured; with either missing this is an ordinary paste and nothing goes
     * anywhere. When it does run, a failure falls back to the ordinary paste rather
     * than leaving the user with a tap that did nothing.
     */
    private fun pasteOrConvert() {
        val clip = clipboardManager?.primaryClip
        if (!PasteConversion.canConvert(this, clip, SettingsStore.current)) {
            editor.paste()
            return
        }
        if (editor.isSensitive) {
            // A password field is not somewhere a document gets uploaded on the way in.
            editor.paste()
            return
        }

        convertingPaste = true
        refreshBusyLamp()
        serviceScope.launch {
            val result = PasteConversion.convert(
                context = this@CustomKeyboardIme,
                clip = clip,
                settings = SettingsStore.current,
                language = layout.locale.orEmpty()
            )
            convertingPaste = false
            refreshBusyLamp()
            result.fold(
                onSuccess = { text ->
                    val trimmed = text.trim()
                    // Nothing found is an answer, not a failure: pasting the file's URI
                    // instead would be worse than pasting nothing.
                    if (trimmed.isNotEmpty()) {
                        editor.commitText(trimmed, applyConventions = false)
                        refreshSuggestions()
                    }
                },
                onFailure = { editor.paste() }
            )
        }
    }

    /** True while a pasted file is being read. */
    private var convertingPaste = false
    private var suggestionsBusy = false

    /**
     * One lamp, two things that can light it.
     *
     * Set from either side alone and whichever finished last would turn it off while
     * the other was still working.
     */
    private fun refreshBusyLamp() {
        state.setFlag(IndicatorKeys.AI_BUSY, suggestionsBusy || convertingPaste)
    }

    private fun performClipboard(op: ClipboardOp) {
        when (op) {
            ClipboardOp.COPY -> editor.copy()
            ClipboardOp.CUT -> editor.cut()
            ClipboardOp.PASTE -> pasteOrConvert()
            ClipboardOp.PASTE_PLAIN -> {
                val clip = clipboardManager?.primaryClip
                val text = if (clip != null && clip.itemCount > 0) {
                    clip.getItemAt(0).coerceToText(this)?.toString()
                } else {
                    null
                }
                if (!text.isNullOrEmpty()) editor.commitText(text, applyConventions = false)
                else editor.paste()
            }
            ClipboardOp.HISTORY -> openPanel(PanelId.CLIPBOARD)
            // Pins the most recent entry, which is whatever was copied last.
            ClipboardOp.PIN_CURRENT -> serviceScope.launch {
                repository.newestClip()?.let { repository.setClipPinned(it.id, true) }
            }
            ClipboardOp.CLEAR -> serviceScope.launch { repository.clearClipboard() }
        }
    }

    private fun applyPresentation(mode: PresentationMode, settings: Settings) {
        val next = if (mode != PresentationMode.CYCLE) mode else when (settings.presentation) {
            PresentationMode.NORMAL -> PresentationMode.ONE_HANDED_RIGHT
            PresentationMode.ONE_HANDED_RIGHT -> PresentationMode.ONE_HANDED_LEFT
            PresentationMode.ONE_HANDED_LEFT -> PresentationMode.SPLIT
            PresentationMode.SPLIT -> PresentationMode.FLOATING
            else -> PresentationMode.NORMAL
        }
        SettingsStore.update {
            it.copy(
                presentation = next,
                widthFraction = when (next) {
                    PresentationMode.ONE_HANDED_LEFT, PresentationMode.ONE_HANDED_RIGHT ->
                        if (it.widthFraction > 0.95f) 0.75f else it.widthFraction
                    PresentationMode.NORMAL -> 1f
                    else -> it.widthFraction
                }
            )
        }
    }

    // -----------------------------------------------------------------------
    // Clipboard capture
    // -----------------------------------------------------------------------

    /**
     * Remembers what the user copies — except when the keyboard is focused on a
     * password field. The previous build recorded everything, which meant a password
     * manager's copy landed in a plain-text database on the device.
     */
    /**
     * Set when the keyboard itself is about to write to the clipboard.
     *
     * Without it, pasting a composite immediately records every part of it again as a
     * fresh copy, and the history grows a duplicate of itself every time it is used.
     */
    private var suppressNextClipboardChange = false

    private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
        val settings = SettingsStore.current
        if (suppressNextClipboardChange) {
            suppressNextClipboardChange = false
            return@OnPrimaryClipChangedListener
        }
        if (!settings.clipboardEnabled) return@OnPrimaryClipChangedListener
        if (settings.clipboardIgnorePasswordFields && editor.isSensitive) {
            return@OnPrimaryClipChangedListener
        }
        val clip = clipboardManager?.primaryClip ?: return@OnPrimaryClipChangedListener
        if (clip.itemCount == 0) return@OnPrimaryClipChangedListener

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ flags clips the source app marked as sensitive; honour it.
            val isSensitive = clip.description?.extras
                ?.getBoolean("android.content.extra.IS_SENSITIVE", false) == true
            if (isSensitive) return@OnPrimaryClipChangedListener
        }

        // Every item, not just the first. A clip can hold several — a file and its
        // name, a selection and its HTML — and taking only item zero threw away the
        // half the user was more likely to want.
        val group = UUID.randomUUID().toString()
        val source = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            clip.description?.extras?.getString("android.content.extra.SOURCE_PACKAGE")
        } else {
            null
        }
        val items = (0 until clip.itemCount).map { clip.getItemAt(it) }
        val multi = items.size > 1
        val maxBytes = settings.clipboardMaxFileMb.coerceAtLeast(0) * 1_000_000L

        serviceScope.launch {
            items.forEach { item ->
                val text = item.text?.toString()
                val uri = item.uri
                when {
                    !text.isNullOrBlank() -> repository.rememberClipEntry(
                        ClipboardEntity(
                            type = ClipboardEntity.TYPE_TEXT,
                            content = text,
                            mime = "text/plain",
                            sourcePackage = source,
                            groupId = if (multi) group else null
                        ),
                        settings.clipboardMaxItems
                    )

                    uri != null -> rememberFileClip(uri, source, if (multi) group else null, maxBytes)
                }
            }
        }
    }

    /**
     * Takes a copy of what a clipboard URI points at, now, while it can be read.
     *
     * The grant on a clipboard URI is temporary and belongs to the clip. By the time
     * the user opens their history it is usually gone, which is why the previous
     * version's picture and file entries were dead on arrival — present in the list,
     * unreadable when tapped, and silent about it. Copying the bytes is only possible
     * at this moment, so it happens at this moment.
     */
    private suspend fun rememberFileClip(uri: Uri, source: String?, group: String?, maxBytes: Long) {
        val settings = SettingsStore.current
        if (!settings.clipboardKeepFiles) {
            // Still worth remembering that it happened, even without the bytes.
            repository.rememberClipEntry(
                ClipboardEntity(
                    type = ClipboardEntity.TYPE_URI,
                    content = uri.toString(),
                    mime = contentResolver.getType(uri) ?: ClipStore.guessMime(uri.toString()),
                    sourcePackage = source,
                    groupId = group
                ),
                settings.clipboardMaxItems
            )
            return
        }

        val mime = contentResolver.getType(uri) ?: ClipStore.guessMime(uri.toString())
        val file = withContext(Dispatchers.IO) { ClipStore.capture(this@CustomKeyboardIme, uri, maxBytes) }
        if (file == null) {
            AppLogger.d("Clipboard", "could not keep the bytes of $uri; remembering the name only")
            repository.rememberClipEntry(
                ClipboardEntity(
                    type = ClipboardEntity.TYPE_URI,
                    content = uri.toString(),
                    mime = mime,
                    sourcePackage = source,
                    groupId = group
                ),
                settings.clipboardMaxItems
            )
            return
        }

        repository.rememberClipEntry(
            ClipboardEntity(
                type = ClipboardEntity.TYPE_FILE,
                content = uri.lastPathSegment ?: file.name,
                mime = mime,
                filePath = file.absolutePath,
                sizeBytes = file.length(),
                sourcePackage = source,
                groupId = group
            ),
            settings.clipboardMaxItems
        )
    }
}
