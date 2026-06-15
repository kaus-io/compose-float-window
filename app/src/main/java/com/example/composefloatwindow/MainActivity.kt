package com.example.composefloatwindow

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import com.example.composefloatwindow.ui.theme.ComposeFloatWindowTheme
import com.zxhhyj.composefloatwindow.ComposeFloatWindow

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ComposeFloatWindowTheme {
                DemoScreen(
                    onDisposeFloatWindow = { /* demo 只在屏幕内展示 */ }
                )
            }
        }
    }
}

@Composable
private fun DemoScreen(
    onDisposeFloatWindow: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val activityState by lifecycleOwner.lifecycle.currentStateAsState()

    val floatWindowHolder = remember { FloatWindowHolder() }
    val logs = remember { mutableStateListOf<String>() }

    val refreshTrigger = remember { mutableStateOf(0) }
    val isAvailable = remember(refreshTrigger.value) { floatWindowHolder.window?.isAvailable() == true }

    fun appendLog(msg: String) {
        logs.add("[${(logs.size + 1).toString().padStart(2, '0')}] $msg")
    }

    val overlayPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        refreshTrigger.value++
        val ok = floatWindowHolder.window?.isAvailable() == true
        appendLog("权限设置返回  isAvailable=$ok")
    }

    DisposableEffectOnLifecycleDispose {
        floatWindowHolder.window?.dispose()
        onDisposeFloatWindow()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            "ComposeFloatWindow 演示",
            style = MaterialTheme.typography.headlineSmall
        )
        Text(
            "Activity lifecycle: $activityState",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(Modifier.padding(12.dp)) {
                Text("状态", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    "isAvailable = $isAvailable",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp
                )
                Text(
                    "floatWindow = ${if (floatWindowHolder.window == null) "null" else floatWindowHolder.window!!::class.simpleName}",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp
                )
                floatWindowHolder.window?.let { w ->
                    val showing by w.showing.collectAsState()
                    Text(
                        "showing = $showing",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp
                    )
                    Text(
                        "lifecycle = ${w.lifecycle.currentState}",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp
                    )
                }
            }
        }

        Text("API 操作", style = MaterialTheme.typography.titleSmall)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(
                    onClick = {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}")
                        )
                        overlayPermissionLauncher.launch(intent)
                    }
                ) { Text("请求权限") }
                OutlinedButton(
                    onClick = {
                        refreshTrigger.value++
                        val w = floatWindowHolder.window
                        val ok = w?.isAvailable() == true
                        appendLog("isAvailable() = $ok")
                    }
                ) { Text("isAvailable()") }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(
                    onClick = {
                        if (floatWindowHolder.window == null) {
                            val w = ComposeFloatWindow(
                                context,
                                WindowManager.LayoutParams(
                                    WindowManager.LayoutParams.WRAP_CONTENT,
                                    WindowManager.LayoutParams.WRAP_CONTENT,
                                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                                            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                                    PixelFormat_Translucent
                                ).apply {
                                    gravity = Gravity.START or Gravity.TOP
                                    x = 100
                                    y = 200
                                    windowAnimations = android.R.style.Animation_Dialog
                                }
                            ).apply {
                                setContent { FloatWindowContent() }
                            }
                            floatWindowHolder.window = w
                            refreshTrigger.value++
                            appendLog("创建 + setContent { FloatWindowContent() }")
                        } else {
                            appendLog("已存在实例，跳过创建")
                        }
                    }
                ) { Text("创建 setContent") }
                OutlinedButton(
                    onClick = {
                        val w = floatWindowHolder.window
                        if (w == null) {
                            appendLog("show() 失败：尚未 setContent")
                        } else {
                            w.show()
                            refreshTrigger.value++
                            appendLog("show()  showing=${w.showing.value}  lifecycle=${w.lifecycle.currentState}")
                        }
                    }
                ) { Text("show()") }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(
                    onClick = {
                        val w = floatWindowHolder.window
                        if (w == null) {
                            appendLog("hide() 失败：尚未 setContent")
                        } else {
                            w.hide()
                            refreshTrigger.value++
                            appendLog("hide()  showing=${w.showing.value}  lifecycle=${w.lifecycle.currentState}")
                        }
                    }
                ) { Text("hide()") }
                OutlinedButton(
                    onClick = {
                        val w = floatWindowHolder.window
                        if (w == null || !w.showing.value) {
                            appendLog("update() 失败：未显示")
                        } else {
                            w.windowParams.x = (w.windowParams.x + 40) % 800
                            w.windowParams.y = (w.windowParams.y + 40) % 1200
                            w.update()
                            appendLog("update()  位置=(${w.windowParams.x}, ${w.windowParams.y})")
                        }
                    }
                ) { Text("update() 位移") }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(
                    onClick = {
                        val w = floatWindowHolder.window
                        if (w == null) {
                            appendLog("rebuild() 失败：尚未 setContent")
                        } else {
                            w.rebuild()
                            refreshTrigger.value++
                            appendLog("rebuild()  ViewModel/Recomposer/composition 已重建")
                        }
                    },
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.tertiary
                    )
                ) { Text("rebuild()") }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(
                    onClick = {
                        val w = floatWindowHolder.window
                        if (w == null) {
                            appendLog("getContentView() 失败：尚未 setContent")
                        } else {
                            val v = w.getContentViewOrNull()
                            appendLog("getContentViewOrNull() = ${v?.javaClass?.simpleName ?: "null"}")
                        }
                    }
                ) { Text("getContentView()") }
                OutlinedButton(
                    onClick = {
                        val w = floatWindowHolder.window
                        if (w == null) {
                            appendLog("dispose() 失败：尚未 setContent")
                        } else {
                            w.dispose()
                            floatWindowHolder.window = null
                            refreshTrigger.value++
                            appendLog("dispose()  lifecycle=DESTROYED  引用已清空")
                        }
                    },
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) { Text("dispose()") }
            }
        }

        HorizontalDivider()
        Text("操作日志 (${logs.size})", style = MaterialTheme.typography.titleSmall)
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                items(logs) { line ->
                    Text(
                        line,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

private const val PixelFormat_Translucent = android.graphics.PixelFormat.TRANSLUCENT

private class FloatWindowHolder {
    var window: ComposeFloatWindow? = null
}

@Composable
private fun DisposableEffectOnLifecycleDispose(onDispose: () -> Unit) {
    val owner = LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(owner) {
        onDispose { onDispose() }
    }
}
