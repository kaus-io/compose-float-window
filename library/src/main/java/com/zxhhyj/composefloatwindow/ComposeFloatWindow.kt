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
 * 一个悬浮窗 Compose 容器，基于 [WindowManager] 显示系统级悬浮窗。
 *
 * 与 Activity 不同的是，浮窗本身不持有 [android.view.View] 树意义上的「宿主 Activity」，
 * 但本类完整实现了 [Lifecycle]、[ViewModelStoreOwner]、[SavedStateRegistryOwner]、
 * [OnBackPressedDispatcherOwner] 这一整套与 Compose / AndroidX 生命周期协作的接口，
 * 因此可以直接在 [setContent] 中使用 `rememberSaveable` / `viewModel` /
 * `LifecycleResumeEffect` / `BackHandler` 等 API，不会污染宿主 Activity 的状态。
 *
 * 用法示例：
 * ```
 * val window = ComposeFloatWindow(context)
 * window.setContent { MyFloatingUI() }
 * window.show()      // 显示
 * window.hide()      // 隐藏但保留状态
 * window.rebuild()   // 重建（清空 ViewModel 等）
 * window.dispose()   // 彻底销毁
 * ```
 *
 * @param context 任意 [Context]，首选 Activity（用于 Android 13+ 的返回键回调注册）。
 * @param windowParams 浮窗布局参数，默认是 WRAP_CONTENT + FLAG_NOT_TOUCH_MODAL；
 *   当 [context] 不是 Activity 时会自动加上 `TYPE_APPLICATION_OVERLAY`（需要 SYSTEM_ALERT_WINDOW 权限）。
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

    /** 浮窗当前是否处于「显示」状态（已调用 [show] 且未调用 [hide] / [dispose]）。 */
    val showing = _showing.asStateFlow()

    /** 浮窗根 [ViewGroup]，所有 Compose 内容挂在这上面；默认是 [FrameLayout] 子类并拦截返回键。 */
    val decorView: ViewGroup = ParentLayout(context)

    private val composition = CompositionController()

    private var pendingContent: (@Composable () -> Unit)? = null

    private var onBackInvokedCallback: OnBackInvokedCallback? = null

    private val windowManager = context.getSystemService(WindowManager::class.java)

    /**
     * 设置或替换浮窗里的 Compose 内容。
     *
     * 多次调用会替换上一次的 [Composable]，但**不会**清空已有的 [viewModelStore] /
     * [rememberSaveable] 状态。如需彻底重建，请使用 [rebuild]。
     *
     * 必须在主线程调用。
     *
     * @param content 浮窗内的根 Composable；内部会自动通过 [LocalFloatWindow] 暴露当前 [ComposeFloatWindow] 实例。
     */
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

    /**
     * 取出内部 [ComposeView]，未调用 [setContent] 时抛 [NullPointerException]。
     * @see getContentViewOrNull
     */
    fun getContentView(): View = getContentViewOrNull()!!

    /**
     * 取出内部 [ComposeView]，未调用 [setContent] 时返回 null。
     */
    fun getContentViewOrNull(): View? = decorView.getChildAt(0)

    /**
     * 显示浮窗。
     *
     * 行为：
     * - 权限检查失败（[isAvailable] 返回 false）时静默 no-op；
     * - 已经在显示时退化为 [update]（刷新 [windowParams]）；
     * - 否则把 [decorView] 挂到 [WindowManager]，生命周期走到 `ON_START → ON_RESUME`，
     *   内部 [Recomposer] 开始工作。
     *
     * 必须在主线程调用。
     */
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

    /**
     * 用最新的 [windowParams] 刷新浮窗布局，未在显示时 no-op。
     * 适用于用户拖动后保存位置、半透明动画等场景。
     *
     * 必须在主线程调用。
     */
    @MainThread
    fun update() {
        if (!_showing.value) return
        windowManager.updateViewLayout(decorView, windowParams)
    }

    /**
     * 隐藏浮窗，但**保留** [ViewModelStore] / [rememberSaveable] / [Recomposer] 等状态。
     * 再次调用 [show] 会从相同状态恢复显示。
     *
     * 生命周期走到 `ON_PAUSE → ON_STOP`。
     *
     * 必须在主线程调用。
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
     * 重建浮窗内部状态：清空 [viewModelStore]、关闭旧的 [Recomposer] 与 composition，
     * 然后用最近一次 [setContent] 提供的 Composable 重新挂载。
     *
     * 显示中调用会自动 [hide] → 重建 → 再 [show]，重建结束后仍然处于显示状态。
     * 隐藏中调用同样适用（直接重建）。
     *
     * 与 [hide] 的区别：[hide] 保留所有状态，本方法**全部丢弃**。
     *
     * 注意：因为 [viewModelStore] 会被清空，
     * 依赖 [androidx.lifecycle.viewmodel.compose.viewModel] 创建的 ViewModel 会丢失。
     * 建议在 ViewModel 内使用 `viewModelFactory { initializer { createSavedStateHandle() } }`
     * 等新 API（而不是旧的 `AbstractSavedStateViewModelFactory`），以避免重建时 SavedStateProvider 冲突。
     *
     * 必须在主线程调用。
     */
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

    /**
     * 彻底销毁浮窗，释放所有资源：composition、[Recomposer]、[viewModelStore]、
     * [savedStateRegistry]、Android 13+ 的 [OnBackInvokedCallback]，并将生命周期走到 `ON_DESTROY`。
     *
     * 调用之后**不应再使用**本实例。需要在 [Lifecycle.State.DESTROYED] 之后复用请重新 [setContent]。
     *
     * 必须在主线程调用。
     */
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

    /**
     * 当前是否有「系统级悬浮窗」权限。Android 6.0+ 需要用户去系统设置里授权 `SYSTEM_ALERT_WINDOW`。
     * [show] 在权限缺失时会静默 no-op，因此调用方应在 UI 层先检查这个标志。
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
        /**
         * 在浮窗的 Composable 树里拿到当前 [ComposeFloatWindow] 实例。
         *
         * 通过 [setContent] 注入，调用方无须显式使用 [androidx.compose.runtime.CompositionLocalProvider]：
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
