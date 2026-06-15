package com.zxhhyj.composefloatwindow

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.setViewTreeOnBackPressedDispatcherOwner
import androidx.annotation.MainThread
import androidx.annotation.RequiresApi
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ComposeFloatWindow(
    val context: Context,
    val windowParams: WindowManager.LayoutParams = WindowManager.LayoutParams().apply {
        height = WindowManager.LayoutParams.WRAP_CONTENT
        width = WindowManager.LayoutParams.WRAP_CONTENT
        format = PixelFormat.TRANSLUCENT
        gravity = Gravity.START or Gravity.TOP
        windowAnimations = android.R.style.Animation_Dialog
        flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
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

    private val lifecycleRegistry: LifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    private val savedStateRegistryController: SavedStateRegistryController =
        SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    override val onBackPressedDispatcher: OnBackPressedDispatcher = OnBackPressedDispatcher()

    private val _showing: MutableStateFlow<Boolean> = MutableStateFlow(false)
    val showing = _showing.asStateFlow()

    val decorView: ViewGroup = ParentLayout(context)

    private val composition = CompositionController()

    private var pendingContent: (@Composable () -> Unit)? = null

    private var onBackInvokedCallback: OnBackInvokedCallback? = null

    private val windowManager = context.getSystemService(WindowManager::class.java)

    @MainThread
    fun setContent(content: @Composable () -> Unit) {
        pendingContent = content
        val view = ComposeView(context.applicationContext).apply {
            setViewTreeLifecycleOwner(this@ComposeFloatWindow)
            setViewTreeViewModelStoreOwner(this@ComposeFloatWindow)
            setViewTreeSavedStateRegistryOwner(this@ComposeFloatWindow)
            setViewTreeOnBackPressedDispatcherOwner(this@ComposeFloatWindow)
            setContent {
                CompositionLocalProvider(LocalFloatWindow provides this@ComposeFloatWindow, content)
            }
        }
        composition.install(view)
    }

    fun getContentView(): View = getContentViewOrNull()!!

    fun getContentViewOrNull(): View? = decorView.getChildAt(0)

    @MainThread
    fun show() {
        if (!isAvailable()) return
        require(decorView.isNotEmpty()) {
            "Content view cannot be empty"
        }
        if (_showing.value) {
            update()
            return
        }
        composition.attach()
        if (decorView.parent != null) {
            windowManager.removeViewImmediate(decorView)
        }
        windowManager.addView(decorView, windowParams)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        _showing.value = true
    }

    @MainThread
    fun update() {
        if (!_showing.value) return
        windowManager.updateViewLayout(decorView, windowParams)
    }

    @MainThread
    fun hide() {
        if (!_showing.value) return
        _showing.value = false
        windowManager.removeViewImmediate(decorView)
        composition.detach()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
    }

    @MainThread
    fun rebuild() {
        val content = pendingContent ?: return
        val wasShowing = _showing.value
        if (wasShowing) {
            hide()
        }
        composition.close()
        viewModelStore.clear()
        if (decorView.isNotEmpty()) {
            decorView.removeAllViews()
        }
        setContent(content)
        if (wasShowing) {
            show()
        }
    }

    @MainThread
    fun dispose() {
        if (_showing.value) {
            hide()
        }
        composition.close()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            unregisterOnBackInvokedCallbackIfPossible()
        }
        if (lifecycle.currentState != Lifecycle.State.DESTROYED) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        }
        viewModelStore.clear()
        _showing.value = false
    }

    fun isAvailable(): Boolean = Settings.canDrawOverlays(context)

    init {
        savedStateRegistryController.performAttach()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        enableSavedStateHandles()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerOnBackInvokedCallbackIfPossible()
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun registerOnBackInvokedCallbackIfPossible() {
        if (onBackInvokedCallback != null) return
        val activity = context.findActivity() ?: return
        val callback = OnBackInvokedCallback { onBackPressedDispatcher.onBackPressed() }
        activity.onBackInvokedDispatcher.registerOnBackInvokedCallback(
            OnBackInvokedDispatcher.PRIORITY_DEFAULT,
            callback
        )
        onBackInvokedCallback = callback
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun unregisterOnBackInvokedCallbackIfPossible() {
        val callback = onBackInvokedCallback ?: return
        val activity = context.findActivity() ?: return
        activity.onBackInvokedDispatcher.unregisterOnBackInvokedCallback(callback)
        onBackInvokedCallback = null
    }

    private fun Context.findActivity(): Activity? {
        var ctx: Context = this
        while (ctx is ContextWrapper) {
            if (ctx is Activity) return ctx
            ctx = ctx.baseContext
        }
        return null
    }

    private inner class ParentLayout(context: Context) : FrameLayout(context) {
        override fun dispatchKeyEventPreIme(event: KeyEvent?): Boolean {
            if (event?.action == KeyEvent.ACTION_UP && event.keyCode == KeyEvent.KEYCODE_BACK) {
                onBackPressedDispatcher.onBackPressed()
                return true
            }
            return super.dispatchKeyEventPreIme(event)
        }
    }

    private inner class CompositionController {
        private var composeView: ComposeView? = null
        private var recomposer: Recomposer? = null
        private var recomposeJob: Job? = null

        fun install(view: ComposeView) {
            composeView?.disposeComposition()
            if (decorView.isNotEmpty()) {
                decorView.removeAllViews()
            }
            composeView = view
            decorView.addView(
                view,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
            )
            if (_showing.value) {
                attach()
                update()
            }
        }

        fun attach() {
            val view = (decorView.getChildAt(0) as? ComposeView) ?: return
            val current = recomposer ?: Recomposer(AndroidUiDispatcher.CurrentThread).also {
                recomposer = it
            }
            if (view.compositionContext !== current) {
                view.compositionContext = current
            }
            if (recomposeJob?.isActive != true) {
                recomposeJob = lifecycleScope.launch(AndroidUiDispatcher.CurrentThread) {
                    current.runRecomposeAndApplyChanges()
                }
            }
        }

        fun detach() {
            recomposeJob?.cancel()
            recomposeJob = null
        }

        fun close() {
            recomposer?.close()
            recomposer = null
            composeView?.disposeComposition()
            composeView = null
            recomposeJob?.cancel()
            recomposeJob = null
        }
    }

    companion object {
        val LocalFloatWindow = compositionLocalOf<ComposeFloatWindow> {
            error("ComposeFloatWindow not provided. Please ensure you have supplied a value in the Composition tree via CompositionLocalProvider(LocalFloatWindow).")
        }
    }
}
