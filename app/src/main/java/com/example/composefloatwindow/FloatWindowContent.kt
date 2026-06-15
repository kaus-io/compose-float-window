package com.example.composefloatwindow

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.zxhhyj.composefloatwindow.ComposeFloatWindow.Companion.LocalFloatWindow
import kotlin.math.roundToInt

@Composable
fun FloatWindowContent() {
    val window = LocalFloatWindow.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val lifecycleState by lifecycleOwner.lifecycle.currentStateAsState()

    val factory = remember(window) {
        viewModelFactory {
            initializer {
                FloatWindowViewModel(createSavedStateHandle())
            }
        }
    }
    val vm: FloatWindowViewModel = viewModel(
        key = "FloatWindowViewModel",
        factory = factory
    )
    val counter by vm.counter.collectAsStateWithLifecycle()
    val logs by vm.logs.collectAsStateWithLifecycle()

    var input by rememberSaveable { mutableStateOf("hello") }
    var dragX by remember { mutableIntStateOf(0) }
    var dragY by remember { mutableIntStateOf(0) }

    LifecycleEventEffect(Lifecycle.Event.ON_CREATE) { vm.appendLog("lifecycle: ON_CREATE") }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { vm.appendLog("lifecycle: ON_START") }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.appendLog("lifecycle: ON_RESUME") }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { vm.appendLog("lifecycle: ON_PAUSE") }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.appendLog("lifecycle: ON_STOP") }
    DisposableEffect(Unit) {
        onDispose { vm.appendLog("lifecycle: ON_DESTROY (via DisposableEffect)") }
    }

    LifecycleResumeEffect(Unit) {
        vm.appendLog("LifecycleResumeEffect: started (只在 RESUMED 时执行)")
        onPauseOrDispose {
            vm.appendLog("LifecycleResumeEffect: cleaned up (离开 RESUMED)")
        }
    }

    BackHandler(enabled = true) {
        vm.appendLog("BackHandler: 在浮窗内拦截了 back key")
    }

    Card(
        modifier = Modifier
            .fillMaxSize()
            .padding(8.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "悬浮窗  lifecycle=$lifecycleState",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.primary
                )
                IconButton(onClick = { window.hide() }) {
                    Icon(Icons.Default.Close, contentDescription = "hide()")
                }
            }
            Spacer(Modifier.height(8.dp))

            Text("rememberSaveable 输入框:")
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))

            Text("ViewModel 计数器: $counter")
            Row {
                Button(onClick = { vm.increment() }) { Text("vm.increment()") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = { vm.appendLog("manual log @ ${System.currentTimeMillis() % 100000}") }) {
                    Text("vm.appendLog()")
                }
            }
            Spacer(Modifier.height(8.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.secondaryContainer)
                    .pointerInput(Unit) {
                        detectDragGestures { _, drag ->
                            dragX += drag.x.roundToInt()
                            dragY += drag.y.roundToInt()
                            window.windowParams.x = dragX
                            window.windowParams.y = dragY
                            window.update()
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "拖动这里 → 修改 windowParams.x/y 后 update()\n当前偏移: ($dragX, $dragY)",
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    fontSize = 12.sp
                )
            }
            Spacer(Modifier.height(8.dp))

            Text(
                "Recent logs (来自 ViewModel + Lifecycle):",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Column(modifier = Modifier.fillMaxWidth()) {
                logs.takeLast(6).forEach { line ->
                    Text(
                        "· $line",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            Row {
                Button(onClick = { window.hide() }) { Text("hide()") }
            }
        }
    }
}
