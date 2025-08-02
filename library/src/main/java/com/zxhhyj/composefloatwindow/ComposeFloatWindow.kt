package com.zxhhyj.composefloatwindow

import android.app.Activity
import android.app.Application
import android.content.Context
import android.graphics.PixelFormat
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.setViewTreeOnBackPressedDispatcherOwner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.compositionContext
import androidx.core.view.isNotEmpty
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.SAVED_STATE_REGISTRY_OWNER_KEY
import androidx.lifecycle.SavedStateViewModelFactory
import androidx.lifecycle.VIEW_MODEL_STORE_OWNER_KEY
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.enableSavedStateHandles
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.lifecycle.viewmodel.MutableCreationExtras
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// copied from https://github.com/only52607/compose-floating-window/blob/main/library/src/main/java/com/github/only52607/compose/window/ComposeFloatingWindow.kt
// copied form https://github.com/FunnySaltyFish/Transtation-KMP/composeApp/src/androidMain/kotlin/com/github/only52607/compose/window/ComposeFloatingWindow.kt

class ComposeFloatWindow(
    private val context: Context,
    val windowParams: WindowManager.LayoutParams = WindowManager.LayoutParams().apply {
        height = WindowManager.LayoutParams.WRAP_CONTENT
        width = WindowManager.LayoutParams.WRAP_CONTENT
        format = PixelFormat.TRANSLUCENT
        gravity = Gravity.START or Gravity.TOP
        windowAnimations = android.R.style.Animation_Dialog
        flags = (WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        if (context !is Activity) {
            type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        }
    }
) : SavedStateRegistryOwner,
    ViewModelStoreOwner,
    HasDefaultViewModelProviderFactory,
    OnBackPressedDispatcherOwner {

    override val defaultViewModelProviderFactory: ViewModelProvider.Factory by lazy {
        SavedStateViewModelFactory(
            context.applicationContext as Application,
            this@ComposeFloatWindow,
            null
        )
    }

    override val defaultViewModelCreationExtras = MutableCreationExtras().apply {
        val application = context.applicationContext as Application?
        if (application != null) {
            set(
                ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY,
                application
            )
        }
        set(SAVED_STATE_REGISTRY_OWNER_KEY, this@ComposeFloatWindow)
        set(VIEW_MODEL_STORE_OWNER_KEY, this@ComposeFloatWindow)
    }

    override val viewModelStore: ViewModelStore = ViewModelStore()

    private var lifecycleRegistry: LifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    private var savedStateRegistryController: SavedStateRegistryController =
        SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry
    override val onBackPressedDispatcher: OnBackPressedDispatcher =
        OnBackPressedDispatcher()

    private var _showing = MutableStateFlow(false)

    val showing: StateFlow<Boolean>
        get() = _showing.asStateFlow()

    var decorView: ViewGroup = ParentLayout(context)
    private lateinit var composeView: ComposeView

    private val windowManager = context.getSystemService(WindowManager::class.java)

    fun setContent(content: @Composable () -> Unit) {
        setContentView(ComposeView(context).apply {
            setViewTreeLifecycleOwner(this@ComposeFloatWindow)
            setViewTreeViewModelStoreOwner(this@ComposeFloatWindow)
            setViewTreeSavedStateRegistryOwner(this@ComposeFloatWindow)
            setViewTreeOnBackPressedDispatcherOwner(this@ComposeFloatWindow)
            setContent {
                CompositionLocalProvider(LocalFloatWindow provides this@ComposeFloatWindow) {
                    content()
                }
            }
        })
    }

    private fun setContentView(view: ComposeView) {
        if (decorView.isNotEmpty()) {
            decorView.removeAllViews()
        }
        composeView = view
        decorView.addView(
            view,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        )
        update()
    }

    fun getContentView() = getContentViewOrNull()!!

    fun getContentViewOrNull(): View? {
        return decorView.getChildAt(0)
    }

    fun show() {
        if (isAvailable().not()) return
        require(decorView.isNotEmpty()) {
            "Content view cannot be empty"
        }
        if (_showing.value) {
            update()
            return
        }
        decorView.getChildAt(0)?.takeIf { it is ComposeView }?.let { composeView ->
            val reComposer = Recomposer(AndroidUiDispatcher.CurrentThread)
            composeView.compositionContext = reComposer
            lifecycleScope.launch(AndroidUiDispatcher.CurrentThread) {
                reComposer.runRecomposeAndApplyChanges()
            }
        }
        if (decorView.parent != null) {
            windowManager.removeViewImmediate(decorView)
        }
        windowManager.addView(decorView, windowParams)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        _showing.update { true }
    }

    fun update() {
        if (!_showing.value) return
        windowManager.updateViewLayout(decorView, windowParams)
    }

    fun hide() {
        if (!_showing.value) return
        _showing.update { false }
        windowManager.removeViewImmediate(decorView)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
    }

    fun isAvailable(): Boolean = Settings.canDrawOverlays(context)

    init {
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        enableSavedStateHandles()
    }

    private inner class ParentLayout(context: Context) : FrameLayout(context) {
        override fun dispatchKeyEvent(event: KeyEvent?): Boolean {
            return super.dispatchKeyEvent(event)
        }

        override fun dispatchKeyEventPreIme(event: KeyEvent?): Boolean {
            if (event?.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_BACK) {
                onBackPressedDispatcher.onBackPressed()
            }
            return super.dispatchKeyEventPreIme(event)
        }
    }
}

val LocalFloatWindow = compositionLocalOf<ComposeFloatWindow> {
    error("ComposeFloatWindow not provided. Please ensure you have supplied a value in the Composition tree via CompositionLocalProvider(LocalFloatWindow).")
}