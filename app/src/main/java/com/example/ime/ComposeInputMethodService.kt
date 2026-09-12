package com.example.ime

import android.inputmethodservice.InputMethodService
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.example.util.AppLogger

/**
 * The plumbing a Compose UI needs to live inside an `InputMethodService`.
 *
 * An IME is not an Activity, so nothing gives its views a lifecycle owner, a
 * ViewModelStore or a saved-state registry; Compose requires all three and crashes
 * without them. This supplies them and drives the lifecycle from the IME's own
 * callbacks.
 */
abstract class ComposeInputMethodService :
    InputMethodService(), LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedStateController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    override fun onCreate() {
        super.onCreate()
        AppLogger.init(applicationContext)
        savedStateController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }

    override fun onStartInputView(info: android.view.inputmethod.EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        moveTo(Lifecycle.State.RESUMED)
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        moveTo(Lifecycle.State.CREATED)
    }

    override fun onDestroy() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        store.clear()
        super.onDestroy()
    }

    /**
     * Steps the registry towards [target] one event at a time. `LifecycleRegistry`
     * rejects a jump from CREATED straight to RESUMED, and an IME's callbacks do not
     * arrive in the order an Activity's do, so the transition has to be walked.
     */
    private fun moveTo(target: Lifecycle.State) {
        if (lifecycleRegistry.currentState == Lifecycle.State.DESTROYED) return
        while (lifecycleRegistry.currentState < target) {
            when (lifecycleRegistry.currentState) {
                Lifecycle.State.INITIALIZED -> lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
                Lifecycle.State.CREATED -> lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
                Lifecycle.State.STARTED -> lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
                else -> return
            }
        }
        while (lifecycleRegistry.currentState > target) {
            when (lifecycleRegistry.currentState) {
                Lifecycle.State.RESUMED -> lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
                Lifecycle.State.STARTED -> lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
                else -> return
            }
        }
    }

    /**
     * Tags the IME window's own decor view with these three owners — not the
     * `ComposeView` that ends up inside it.
     *
     * Compose resolves a per-window "window recomposer" the first time any
     * `ComposeView` in a window attaches, and it does that by walking *up* from
     * that window's `rootView`, not from the `ComposeView` itself
     * (`View.getWindowRecomposer()` → `rootView.getTag(...)`). An IME's window is
     * a `Dialog` ([InputMethodService.getWindow]), and its root is not our
     * `ComposeView` — the framework wraps it in its own decor first (a
     * `LinearLayout` tagged `android:id/parentPanel` in the logs this turned up).
     * Tagging the `ComposeView` directly, as an earlier version of this method
     * did, left nothing to find walking up *from* that root, since the
     * `ComposeView` sits *below* it: `ViewTreeLifecycleOwner.get()` never looks
     * down at descendants. The result was `createLifecycleAwareWindowRecomposer`
     * forcing a null lifecycle owner open with `!!` the instant the keyboard
     * tried to draw — every single time, which is exactly what made it look like
     * the keyboard could not open at all rather than like one bad case.
     *
     * Tagging the decor view instead fixes both directions: `getWindowRecomposer`
     * finds it immediately since it starts the walk at that exact view, and
     * anything a `ComposeView` queries from further down still finds it by
     * walking up through the decor view as an ancestor. There is nothing this
     * needs a specific view for any more, so it takes none — call it once the
     * window exists (any time from [onCreate] onward) and every view attached
     * afterward, anywhere in this window, is covered.
     */
    protected fun attachViewTreeOwners() {
        val decorView = window?.window?.decorView
        if (decorView == null) {
            AppLogger.e(
                "ComposeIME",
                "no window decor view yet — tree owners not attached, " +
                    "Compose will crash the moment this view is attached"
            )
            return
        }
        decorView.setViewTreeLifecycleOwner(this)
        decorView.setViewTreeViewModelStoreOwner(this)
        decorView.setViewTreeSavedStateRegistryOwner(this)
    }
}
