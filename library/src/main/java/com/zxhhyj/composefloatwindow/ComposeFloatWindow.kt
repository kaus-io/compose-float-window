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

/**
 * 一个基于 Compose 的系统级悬浮窗容器。
 *
 * 把 Compose UI 挂到系统窗口上，并对外暴露完整的生命周期能力 —— 跟宿主 Activity 一样，
 * 在里面使用 `rememberSaveable` / `viewModel` / `LifecycleResumeEffect` / `BackHandler`
 * 等 API 都不会污染宿主 Activity 的状态。
 *
 * 用法示例：
 * ```
 * val window = ComposeFloatWindow(context)
 * window.setContent { MyFloatingUI() }
 * window.show()      // 显示
 * window.hide()      // 隐藏但保留状态
 * window.reset()     // 重建（清空 ViewModel 等）
 * window.release()   // 彻底释放，之后不可再用
 * ```
 *
 * @param context Context。
 * @param windowParams 浮窗的布局参数；非 Activity 上下文时会自动加上系统级悬浮窗的类型标记，
 *   因此需要先在系统设置里授予「显示在其他应用上层」权限。
 */
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

    /** 浮窗当前是否正在显示。 */
    val showing = _showing.asStateFlow()

    val decorView: ViewGroup = ParentLayout(context)

    private val composition: CompositionController = CompositionController()

    private var pendingContent: (@Composable () -> Unit)? = null

    private var onBackInvokedCallback: OnBackInvokedCallback? = null

    private val windowManager = context.getSystemService(WindowManager::class.java)

    /**
     * 设置或替换浮窗里的 Compose 内容。
     *
     * 调用此方法会**完整重置**浮窗内部状态。
     *
     * @param content 浮窗内的根 Composable；
     */
    @MainThread
    fun setContent(content: @Composable () -> Unit) {
        pendingContent = content
        val wasShowing = _showing.value
        if (wasShowing) {
            windowManager.removeViewImmediate(decorView)
            composition.detach()
        }
        composition.close()
        viewModelStore.clear()
        if (decorView.isNotEmpty()) {
            decorView.removeAllViews()
        }
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
        if (wasShowing) {
            windowManager.addView(decorView, windowParams)
            composition.attach()
        }
    }

    /**
     * 取出内部承载 Compose 内容的 View。
     * @throws NullPointerException 当 [setContent] 还没被调用时。
     * @see getContentViewOrNull
     */
    fun getContentView(): View = getContentViewOrNull()!!

    /**
     * 取出内部承载 Compose 内容的 View。[setContent] 还没被调用时返回 null。
     */
    fun getContentViewOrNull(): View? = decorView.getChildAt(0)

    /**
     * 显示浮窗。
     *
     * - 如果没有系统级悬浮窗权限（[isAvailable] 为 false），此方法什么也不做；
     * - 否则挂载到 WindowManager 上，浮窗开始可见并响应生命周期。
     */
    @MainThread
    fun show() {
        if (!isAvailable()) return
        require(decorView.isNotEmpty()) {
            "Content view cannot be empty"
        }
        if (_showing.value) {
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

    /**
     * 用最新的 [windowParams] 刷新浮窗布局，例如拖动后保存的新位置或透明度变化。
     * 当前没有显示时此方法什么也不做。
     */
    @MainThread
    fun update() {
        if (!_showing.value) return
        windowManager.updateViewLayout(decorView, windowParams)
    }

    /**
     * 隐藏浮窗，但**保留**所有状态。
     */
    @MainThread
    fun hide() {
        if (!_showing.value) return
        _showing.value = false
        windowManager.removeViewImmediate(decorView)
        composition.detach()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
    }

    /**
     * 重建浮窗的内部状态。
     */
    @MainThread
    fun reset() {
        val content = pendingContent ?: return
        setContent(content)
    }

    /**
     * 彻底释放浮窗，释放所有资源。
     */
    @MainThread
    fun release() {
        if (lifecycleRegistry.currentState == Lifecycle.State.DESTROYED) return
        if (_showing.value) {
            windowManager.removeViewImmediate(decorView)
            _showing.value = false
        }
        composition.close()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            unregisterOnBackInvokedCallbackIfPossible()
        }
        if (lifecycleRegistry.currentState != Lifecycle.State.DESTROYED) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        }
        viewModelStore.clear()
    }

    /**
     * 当前是否已获得「显示在其他应用上层」权限。
     *
     * Android 6.0+ 之后该权限必须由用户去系统设置里手动授予。
     * [show] 在权限缺失时会静默跳过，因此调用方最好在 UI 层先检查这个标志并提示用户开启。
     */
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
            if (_showing.value && decorView.parent != null) {
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
        /**
         * 在浮窗的 Composable 树里拿到当前 [ComposeFloatWindow] 实例。
         *
         * 通过 [setContent] 自动注入，调用方无须显式包装 CompositionLocalProvider：
         * ```
         * ComposeFloatWindow(context).apply {
         *     setContent {
         *         val window = LocalFloatWindow.current
         *         Button(onClick = { window.hide() }) { Text("关闭") }
         *     }
         * }
         * ```
         */
        val LocalFloatWindow = compositionLocalOf<ComposeFloatWindow> {
            error("ComposeFloatWindow not provided. Please ensure you have supplied a value in the Composition tree via CompositionLocalProvider(LocalFloatWindow).")
        }
    }
}
