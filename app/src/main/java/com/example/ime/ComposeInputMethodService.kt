package com.example.ime

import android.inputmethodservice.InputMethodService
import com.example.util.AppLogger
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

abstract class ComposeInputMethodService : InputMethodService(), LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry
        
    override val viewModelStore: ViewModelStore
        get() = store

    override fun onCreate() {
        super.onCreate()
        AppLogger.init(applicationContext)
        AppLogger.d("IME_DEBUG", "onCreate")
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }

    override fun onInitializeInterface() {
        try {
            super.onInitializeInterface()
            AppLogger.d("IME_DEBUG", "onInitializeInterface")
        } catch (e: Throwable) {
            AppLogger.e("IME_DEBUG", "Crash in onInitializeInterface", e)
        }
    }

    override fun onBindInput() {
        try {
            super.onBindInput()
            AppLogger.d("IME_DEBUG", "onBindInput")
        } catch (e: Throwable) {
            AppLogger.e("IME_DEBUG", "Crash in onBindInput", e)
        }
    }

    override fun onStartInput(attribute: android.view.inputmethod.EditorInfo?, restarting: Boolean) {
        try {
            super.onStartInput(attribute, restarting)
            AppLogger.d("IME_DEBUG", "onStartInput restarting=$restarting")
        } catch (e: Throwable) {
            AppLogger.e("IME_DEBUG", "Crash in onStartInput", e)
        }
    }

    override fun onStartInputView(info: android.view.inputmethod.EditorInfo?, restarting: Boolean) {
        try {
            super.onStartInputView(info, restarting)
            AppLogger.d("IME_DEBUG", "onStartInputView restarting=$restarting")
            
            if (lifecycleRegistry.currentState == Lifecycle.State.CREATED) {
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            }
            if (lifecycleRegistry.currentState == Lifecycle.State.STARTED) {
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
            }
            
            window?.window?.decorView?.let { setupComposeViewTree(it) }
        } catch (e: Throwable) {
            AppLogger.e("IME_DEBUG", "Crash in onStartInputView", e)
        }
    }

    override fun onEvaluateInputViewShown(): Boolean {
        try {
            val shown = super.onEvaluateInputViewShown()
            AppLogger.d("IME_DEBUG", "onEvaluateInputViewShown returns $shown")
            return true // FORCE show
        } catch (e: Throwable) {
            AppLogger.e("IME_DEBUG", "Crash in onEvaluateInputViewShown", e)
            return true
        }
    }

    override fun onWindowShown() {
        try {
            super.onWindowShown()
            AppLogger.d("IME_DEBUG", "onWindowShown")
        } catch (e: Throwable) {
            AppLogger.e("IME_DEBUG", "Crash in onWindowShown", e)
        }
    }

    override fun onWindowHidden() {
        try {
            super.onWindowHidden()
            AppLogger.d("IME_DEBUG", "onWindowHidden")
        } catch (e: Throwable) {
            AppLogger.e("IME_DEBUG", "Crash in onWindowHidden", e)
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        try {
            super.onFinishInputView(finishingInput)
            AppLogger.d("IME_DEBUG", "onFinishInputView finishingInput=$finishingInput")
            if (lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            }
            if (lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            }
        } catch (e: Throwable) {
            AppLogger.e("IME_DEBUG", "Crash in onFinishInputView", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        AppLogger.d("IME_DEBUG", "onDestroy")
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        store.clear()
    }

    protected fun setupComposeViewTree(view: View) {
        AppLogger.d("IME_DEBUG", "setupComposeViewTree view=$view")
        view.setViewTreeLifecycleOwner(this)
        view.setViewTreeViewModelStoreOwner(this)
        view.setViewTreeSavedStateRegistryOwner(this)
    }
}
