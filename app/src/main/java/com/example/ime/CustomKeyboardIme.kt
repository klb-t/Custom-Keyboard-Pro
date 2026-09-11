package com.example.ime

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import com.example.MainActivity
import com.example.core.ai.AiClient
import com.example.core.ai.AiConfig
import com.example.core.ai.AiTasks
import com.example.core.asr.AsrState
import com.example.core.asr.VoiceController
import com.example.core.config.Settings
import com.example.core.config.SettingsStore
import com.example.core.data.ClipboardEntity
import com.example.core.data.KeyboardRepository
import com.example.core.layout.ClipboardOp
import com.example.core.layout.IndicatorKeys
import com.example.core.layout.KeyAction
import com.example.core.layout.KeyDef
import com.example.core.layout.KeyCodes
import com.example.core.layout.LayoutDef
import com.example.core.layout.LayoutRepository
import com.example.core.layout.ModifierKind
import com.example.core.layout.PanelId
import com.example.core.layout.PresentationMode
import com.example.core.layout.SwitchTarget
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
import kotlinx.coroutines.launch

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

    override val state = KeyboardState()

    override val editor: EditorController by lazy {
        EditorController(
            connection = { currentInputConnection },
            editorInfo = { currentInputEditorInfo },
            settings = { SettingsStore.current }
        )
    }

    override val repository: KeyboardRepository by lazy { KeyboardRepository.get(this) }

    override val suggestions: SuggestionEngine by lazy {
        SuggestionEngine(serviceScope, repository) { SettingsStore.current }
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
        SettingsStore.init(this)
        LayoutRepository.init(this)

        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboardManager?.addPrimaryClipChangedListener(clipboardListener)

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
            suggestions.aiBusy.collect { busy -> state.setFlag(IndicatorKeys.AI_BUSY, busy) }
        }
        serviceScope.launch {
            repository.sweepClipboard(SettingsStore.current.clipboardRetentionDays)
        }
    }

    override fun onDestroy() {
        clipboardManager?.removePrimaryClipChangedListener(clipboardListener)
        voice.release()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onCreateInputView(): View {
        val view = ComposeView(this)
        view.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        view.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        view.setContent {
            val settings by SettingsStore.state.collectAsState()
            CompositionLocalProvider(LocalKeyboardHost provides this@CustomKeyboardIme) {
                KeyboardRoot(
                    settings = settings,
                    onHeightChanged = { px -> applyInputViewHeight(view, px) }
                )
            }
        }
        composeView = view
        attachViewTreeOwners(view)
        return view
    }

    /**
     * The input view's height is whatever the current settings and orientation work out
     * to, so changing the height slider resizes the live keyboard rather than needing it
     * to be dismissed and reopened.
     */
    private fun applyInputViewHeight(view: View, heightPx: Int) {
        if (heightPx <= 0 || requestedHeightPx.value == heightPx) return
        requestedHeightPx.value = heightPx
        view.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, heightPx)
        view.requestLayout()
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        panelState.value = null
        state.clearAllModifiers()
        state.setLayer(LayoutDef.BASE_LAYER)
        state.clearPending()
        suggestions.clear()
        previousWord = ""

        val sensitive = editor.isSensitive
        state.setFlag(IndicatorKeys.PASSWORD_FIELD, editor.isPasswordField)
        state.setFlag(IndicatorKeys.INCOGNITO, sensitive && SettingsStore.current.incognitoInPasswordFields)

        if (SettingsStore.current.autoCapitalize && !sensitive) {
            if (TextOps.shouldCapitalise(editor.textBefore(64))) {
                state.setModifier(ModifierKind.SHIFT, active = true)
            }
        }
        refreshSuggestions()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        voice.cancel()
        suggestions.clear()
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

    override fun onComputeInsets(outInsets: Insets?) {
        super.onComputeInsets(outInsets)
        val insets = outInsets ?: return
        val view = composeView
        if (SettingsStore.current.presentation == PresentationMode.FLOATING && view != null) {
            // Only the floating panel should swallow touches; the rest of the screen
            // belongs to the app underneath.
            val bounds = Rect(0, 0, view.width, view.height)
            insets.touchableInsets = Insets.TOUCHABLE_INSETS_REGION
            insets.touchableRegion.set(bounds)
            insets.contentTopInsets = view.height
            insets.visibleTopInsets = view.height
        } else {
            insets.touchableInsets = Insets.TOUCHABLE_INSETS_VISIBLE
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
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

    override fun openPanel(panel: PanelId?) {
        panelState.value = panel
        if (panel == null) voice.dismiss()
    }

    override fun selectLayout(id: String) {
        layoutIdState.value = id
        SettingsStore.update { it.copy(activeLayoutId = id) }
        state.setLayer(LayoutDef.BASE_LAYER)
    }

    override fun openApp(route: String?) {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            if (route != null) putExtra(MainActivity.EXTRA_ROUTE, route)
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            AppLogger.e("IME", "Could not open the settings app", e)
        }
    }

    override fun requestMicrophonePermission() = openApp(MainActivity.ROUTE_PERMISSIONS)

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

    override fun textForAi(): String {
        val selected = editor.selectedText()?.toString()
        if (!selected.isNullOrBlank()) return selected
        return editor.allText(4000).toString().takeLast(SettingsStore.current.aiContextChars)
    }

    // -----------------------------------------------------------------------
    // Action execution — the one switch that says what this keyboard can do
    // -----------------------------------------------------------------------

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
                editor.backspace(action.unit)
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
                editor.moveCursor(action.direction, action.unit, action.extendSelection)
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

        val endsWord = text.isNotEmpty() && !TextOps.isWordChar(text.last())
        if (endsWord) learnCurrentWordBefore(text)
        refreshSuggestions()

        if (settings.autoCapitalize && endsWord) {
            if (TextOps.shouldCapitalise(editor.textBefore(64))) {
                state.setModifier(ModifierKind.SHIFT, active = true)
            }
        }
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

    private fun refreshSuggestions() {
        suggestions.update(
            textBeforeCursor = editor.textBefore(SettingsStore.current.aiContextChars.coerceAtLeast(64)),
            sensitive = editor.isSensitive,
            locale = layout.locale.orEmpty()
        )
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
        if (source.isBlank()) return

        state.setFlag(IndicatorKeys.AI_BUSY, true)
        serviceScope.launch {
            val response = AiClient.complete(
                config = AiConfig.from(settings, maxTokens = 800),
                systemPrompt = task.systemPrompt,
                userPrompt = task.render(source)
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

    private fun performClipboard(op: ClipboardOp) {
        when (op) {
            ClipboardOp.COPY -> editor.copy()
            ClipboardOp.CUT -> editor.cut()
            ClipboardOp.PASTE -> editor.paste()
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
    private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
        val settings = SettingsStore.current
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

        val item = clip.getItemAt(0)
        val text = item.text?.toString()
        val uri = item.uri?.toString()
        serviceScope.launch {
            when {
                !text.isNullOrBlank() ->
                    repository.rememberClip(ClipboardEntity.TYPE_TEXT, text, settings.clipboardMaxItems)
                !uri.isNullOrBlank() ->
                    repository.rememberClip(ClipboardEntity.TYPE_URI, uri, settings.clipboardMaxItems)
            }
        }
    }
}
