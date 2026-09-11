package com.example.ime

import android.inputmethodservice.InputMethodService
import android.view.View
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

    protected fun attachViewTreeOwners(view: View) {
        view.setViewTreeLifecycleOwner(this)
        view.setViewTreeViewModelStoreOwner(this)
        view.setViewTreeSavedStateRegistryOwner(this)
    }
}
