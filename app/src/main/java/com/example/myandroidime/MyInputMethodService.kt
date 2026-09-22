package com.example.myandroidime

import android.content.Context
import android.inputmethodservice.InputMethodService
import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import android.widget.FrameLayout
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * InputMethodService is a Service, not a ComponentActivity, so the window
 * created by the input-method framework has no ViewTreeLifecycleOwner.  A
 * ComposeView needs that owner to create its window recomposer; without it the
 * framework crashes while attaching the view and immediately hides the IME.
 */
class MyInputMethodService : InputMethodService(), SavedStateRegistryOwner {
    private lateinit var lifecycleRegistry: LifecycleRegistry
    private lateinit var savedStateRegistryController: SavedStateRegistryController

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    override fun onCreate() {
        super.onCreate()
        lifecycleRegistry = LifecycleRegistry(this)
        savedStateRegistryController = SavedStateRegistryController.create(this)
        savedStateRegistryController.performAttach()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }

    override fun onCreateInputView(): View {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        return ImeRootView(this, this).apply {
            addView(ComposeView(context).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                )
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                setContent { KeyboardScreen(::handleKey) }
            })
        }
    }

    override fun onDestroy() {
        if (::lifecycleRegistry.isInitialized) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        }
        super.onDestroy()
    }

    /**
     * The IME framework inserts the view below a private window hierarchy. Its
     * content child (where Compose looks for the owner) is created only after
     * onCreateInputView returns, so install the owner on that hierarchy before
     * ComposeView's attach callback creates the composition.
     */
    private class ImeRootView(
        context: Context,
        private val owner: SavedStateRegistryOwner,
    ) : FrameLayout(context) {
        override fun onAttachedToWindow() {
            var root: View = this
            while (root.parent is View) {
                root = root.parent as View
            }
            root.setViewTreeLifecycleOwner(owner)
            root.setViewTreeSavedStateRegistryOwner(owner)
            super.onAttachedToWindow()
        }
    }

    private fun handleKey(key: String) {
        val c = currentInputConnection ?: return
        when (key) {
            "⌫" -> c.deleteSurroundingText(1, 0)
            "↵" -> c.commitText("\n", 1)
            "空格" -> c.commitText(" ", 1)
            else -> c.commitText(key, 1)
        }
    }
}
