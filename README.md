# ComposeFloatWindow

一个基于 Jetpack Compose 的 Android 悬浮窗库。把 Compose UI 挂到 `WindowManager` 上，并对外暴露完整的 `Lifecycle` / `ViewModelStore` / `SavedStateRegistry` / `OnBackPressedDispatcher`，与 Android 标准的 Lifecycle.State 契约一致。

> **致谢 / Acknowledgements**
> 本库的 `ComposeFloatWindow` 核心代码源自 [only52607/compose-floating-window](https://github.com/only52607/compose-floating-window)（也可见于 [Transtation-KMP](https://github.com/FunnySaltyFish/Transtation-KMP) 的 `composeApp` 模块），在原实现基础上对生命周期、API 命名空间与发布配置做了较大幅度的重构。
> Original `ComposeFloatingWindow` implementation copied from [only52607/compose-floating-window](https://github.com/only52607/compose-floating-window/blob/main/library/src/main/java/com/github/only52607/compose/window/ComposeFloatingWindow.kt) (also mirrored in [Transtation-KMP](https://github.com/FunnySaltyFish/Transtation-KMP)).

## 特性

- **Compose 原生**：直接在浮窗里写 `@Composable`，无需自定义 `View`。
- **标准生命周期**：完整实现 `INITIALIZED → CREATED → STARTED → RESUMED → STARTED → CREATED → DESTROYED` 的状态机，可与 `LifecycleResumeEffect`、`repeatOnLifecycle`、`viewModel()`、`rememberSaveable`、`BackHandler` 无缝协作。
- **完整所有权**：自己持有 `ViewModelStore` / `SavedStateRegistry` / `OnBackPressedDispatcher`，不污染宿主 Activity 的状态。
- **权限检测封装**：`isAvailable()` 包装 `Settings.canDrawOverlays`。
- **轻量依赖**：仅依赖 `androidx.activity` / `androidx.core` / `androidx.appcompat`，不强制带入 Compose BOM。
- **`Recomposer` 自管理**：内部保存的 `Recomposer` 在 `show()` / `hide()` 复用，避免协程泄漏；在 `setContent()` / `reset()` / `release()` 时会关闭并重建。

## 要求

- **Android 8**（API 26）

## 安装

库发布在 Maven Central：

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

// app/build.gradle.kts
dependencies {
    implementation("com.zxhhyj:compose-float-window:<version>")
}
```

请将 `<version>` 替换为 GitHub Releases 中最新的 tag（例如 `1.0.0`）。

## 权限

悬浮窗依赖 Android 的 `SYSTEM_ALERT_WINDOW` 权限。宿主 app 的 `AndroidManifest.xml` 必须声明：

```xml
<uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />
```

并引导用户到设置页授予（Android 6.0+ 需要运行时跳转）：

```kotlin
val intent = Intent(
    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
    Uri.parse("package:${packageName}")
)
startActivityForResult(intent, REQ_OVERLAY)
```

`show()` 在无权限时会**静默返回**。在调用前可自行用 `isAvailable()` 自检。

## 快速上手

```kotlin
val floatWindow = ComposeFloatWindow(context).apply {
    setContent {
        Card(modifier = Modifier.padding(16.dp)) {
            Text("你好，悬浮窗！")
        }
    }
}

if (floatWindow.isAvailable()) {
    floatWindow.show()    // 浮窗出现，Lifecycle 推到 RESUMED
}
```

`setContent` 内部会把 `LocalFloatWindow` 注入到 CompositionTree，子组件可以反向拿到窗口对象：

```kotlin
@Composable
fun CloseButton() {
    val window = LocalFloatWindow.current
    IconButton(onClick = { window.hide() }) {
        Icon(Icons.Default.Close, contentDescription = "关闭")
    }
}
```

## API 速查

### `ComposeFloatWindow`

| 成员 | 类型 | 说明 |
| --- | --- | --- |
| `context` | `Context`（构造参数） | 任意 `Context`（Application / Activity / Service 均可） |
| `windowParams` | `WindowManager.LayoutParams`（构造参数） | 位置、宽高、动画等。可在外部直接修改后调用 `update()` 刷新 |
| `showing` | `StateFlow<Boolean>` | 当前是否显示 |
| `decorView` | `ViewGroup` | 实际挂到 `WindowManager` 的根容器 |
| `setContent(content)` | 方法 | 设置或替换 Compose 内容。每次调用都会**完整重置**内部状态（清空 `ViewModelStore`、关闭并重建 `Recomposer`），并复用上次传入的闭包作为后续 `reset()` 的内容。 |
| `show()` | 方法 | 添加到 `WindowManager`，Lifecycle 推到 `RESUMED` |
| `hide()` | 方法 | 从 `WindowManager` 移除，Lifecycle 收缩到 `CREATED` |
| `update()` | 方法 | 重新提交 `windowParams`（拖动后调用） |
| `reset()` | 方法 | 重建 `Recomposer` / `ViewModelStore` / 组合树（等同于再次调用 `setContent(上次的内容)`），原状态显示时自动恢复显示。**`show` / `hide` 保留状态；`reset` 重置状态；`release` 释放。** |
| `release()` | 方法 | 释放资源，推到 `DESTROYED`，清理 `ViewModelStore` / `Recomposer` |
| `isAvailable()` | 方法 | 检查 `SYSTEM_ALERT_WINDOW` 权限 |
| `getContentView()` / `getContentViewOrNull()` | 方法 | 取得内部 `ComposeView` |

### `LocalFloatWindow`

```kotlin
val LocalFloatWindow: ProvidableCompositionLocal<ComposeFloatWindow>
```

在 `setContent { ... }` 闭包内可拿到当前 `ComposeFloatWindow` 实例，便于内部调用 `show()` / `hide()` / `update()` / `release()`。

## 生命周期

| 调用 | 派发事件 | Lifecycle 状态变化 |
| --- | --- | --- |
| 构造 | `ON_CREATE` | `INITIALIZED → CREATED` |
| `show()` | `ON_START`、`ON_RESUME` | `CREATED → STARTED → RESUMED` |
| `hide()` | `ON_PAUSE`、`ON_STOP` | `RESUMED → STARTED → CREATED` |
| `reset()` | 重新构造 | 状态不变（若显示则重新走 `STARTED → RESUMED`，内部 `Recomposer` / `ViewModelStore` 全部清空） |
| `release()` | `ON_DESTROY` | `CREATED → DESTROYED`（+ 清理 `ViewModelStore` / `Recomposer`） |

注意：

- `lifecycleScope` 会随 `DESTROYED` 自动取消，挂在其上的协程也会停。
- `release()` 之后 Lifecycle 已到 `DESTROYED`，**不可再 `show()`**。如需复用请新建一个 `ComposeFloatWindow` 实例（与 `Activity` 销毁后不能复活同义）。
- `release()` 不会自动调用 `show()` 或 `hide()`，调用方需自行保证顺序。

## 拖动示例

库本身不内置拖动手势，但配合 `pointerInput` + `windowParams` + `update()` 即可实现：

```kotlin
val window = LocalFloatWindow.current
var offsetX by remember { mutableIntStateOf(0) }
var offsetY by remember { mutableIntStateOf(0) }

Box(
    modifier = Modifier
        .offset { IntOffset(offsetX, offsetY) }
        .pointerInput(Unit) {
            detectDragGestures { _, drag ->
                offsetX += drag.x.toInt()
                offsetY += drag.y.toInt()
                window.windowParams.x = offsetX
                window.windowParams.y = offsetY
                window.update()
            }
        }
) {
    /* 浮窗内容 */
}
```

贴边吸顶可结合 `Animatable` 平滑插值 `windowParams.x` / `windowParams.y` 再 `update()`。

## 完整示例

参见仓库的 `app/` 模块。最小形态：

```kotlin
class FloatService : Service() {
    private lateinit var floatWindow: ComposeFloatWindow

    override fun onCreate() {
        super.onCreate()
        floatWindow = ComposeFloatWindow(this).apply {
            setContent {
                MaterialTheme {
                    Surface {
                        Text(
                            text = "服务里跑的浮窗",
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (floatWindow.isAvailable()) floatWindow.show()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        floatWindow.release()
        super.onDestroy()
    }
}
```

## 协议

Apache License 2.0 — 详见 [LICENSE](LICENSE)。
