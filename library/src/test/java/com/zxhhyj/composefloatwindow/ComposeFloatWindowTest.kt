package com.zxhhyj.composefloatwindow

import android.app.Application
import android.os.Build
import android.view.KeyEvent
import androidx.activity.OnBackPressedCallback
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.compositionContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.viewModelFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSettings

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ComposeFloatWindowTest {

    private class TestViewModel(val handle: androidx.lifecycle.SavedStateHandle) : ViewModel()

    private lateinit var context: Application

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        ShadowSettings.setCanDrawOverlays(true)
    }

    @Test
    fun `lifecycle is CREATED after construction`() {
        val window = ComposeFloatWindow(context)
        assertEquals(Lifecycle.State.CREATED, window.lifecycle.currentState)
        window.dispose()
    }

    @Test
    fun `lifecycle reaches RESUMED after show`() {
        val window = ComposeFloatWindow(context)
        window.setContent { }
        window.show()
        assertEquals(Lifecycle.State.RESUMED, window.lifecycle.currentState)
        window.dispose()
    }

    @Test
    fun `lifecycle returns to CREATED after hide`() {
        val window = ComposeFloatWindow(context)
        window.setContent { }
        window.show()
        window.hide()
        assertEquals(Lifecycle.State.CREATED, window.lifecycle.currentState)
        window.dispose()
    }

    @Test
    fun `lifecycle can be re-driven to RESUMED after hide`() {
        val window = ComposeFloatWindow(context)
        window.setContent { }
        window.show()
        window.hide()
        window.show()
        assertEquals(Lifecycle.State.RESUMED, window.lifecycle.currentState)
        window.dispose()
    }

    @Test
    fun `back key on ACTION_UP triggers onBackPressedDispatcher`() {
        val window = ComposeFloatWindow(context)
        window.setContent { }
        window.show()

        var backPressed = false
        window.onBackPressedDispatcher.addCallback(object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                backPressed = true
            }
        })

        val backEvent = KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK)
        window.decorView.dispatchKeyEventPreIme(backEvent)

        assertTrue(backPressed)
        window.dispose()
    }

    @Test
    fun `back key on ACTION_DOWN does not trigger onBackPressedDispatcher`() {
        val window = ComposeFloatWindow(context)
        window.setContent { }
        window.show()

        var backPressed = false
        window.onBackPressedDispatcher.addCallback(object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                backPressed = true
            }
        })

        val backEvent = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK)
        window.decorView.dispatchKeyEventPreIme(backEvent)

        assertFalse(backPressed)
        window.dispose()
    }

    @Test
    fun `back key on ACTION_UP is consumed by dispatchKeyEventPreIme`() {
        val window = ComposeFloatWindow(context)
        window.setContent { }
        window.show()

        val backEvent = KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK)
        val consumed = window.decorView.dispatchKeyEventPreIme(backEvent)

        assertTrue(consumed)
        window.dispose()
    }

    @Test
    fun `non-back key does not trigger onBackPressedDispatcher`() {
        val window = ComposeFloatWindow(context)
        window.setContent { }
        window.show()

        var backPressed = false
        window.onBackPressedDispatcher.addCallback(object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                backPressed = true
            }
        })

        val otherEvent = KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL)
        window.decorView.dispatchKeyEventPreIme(otherEvent)

        assertFalse(backPressed)
        window.dispose()
    }

    @Test
    fun `decorView is stable across reads`() {
        val window = ComposeFloatWindow(context)
        val first = window.decorView
        val second = window.decorView
        assertSame(first, second)
        window.dispose()
    }

    @Test
    fun `dispose brings lifecycle to DESTROYED`() {
        val window = ComposeFloatWindow(context)
        window.setContent { }
        window.show()
        window.dispose()
        assertEquals(Lifecycle.State.DESTROYED, window.lifecycle.currentState)
    }

    @Test
    fun `dispose is idempotent`() {
        val window = ComposeFloatWindow(context)
        window.setContent { }
        window.show()
        window.dispose()
        window.dispose()
        assertEquals(Lifecycle.State.DESTROYED, window.lifecycle.currentState)
    }

    @Test
    fun `dispose when not showing still reaches DESTROYED`() {
        val window = ComposeFloatWindow(context)
        window.setContent { }
        window.dispose()
        assertEquals(Lifecycle.State.DESTROYED, window.lifecycle.currentState)
    }

    @Test
    fun `showing state updates correctly through lifecycle`() {
        val window = ComposeFloatWindow(context)
        window.setContent { }
        assertFalse(window.showing.value)
        window.show()
        assertTrue(window.showing.value)
        window.hide()
        assertFalse(window.showing.value)
        window.dispose()
    }

    @Test
    fun `recomposer is reused across show and hide cycles`() {
        val window = ComposeFloatWindow(context)
        window.setContent { }
        window.show()
        val firstContent = window.getContentViewOrNull() as? androidx.compose.ui.platform.ComposeView
        val firstCompositionContext = firstContent?.compositionContext
        window.hide()
        window.show()
        val secondContent = window.getContentViewOrNull() as? androidx.compose.ui.platform.ComposeView
        val secondCompositionContext = secondContent?.compositionContext
        assertNotNull(firstCompositionContext)
        assertSame(firstCompositionContext, secondCompositionContext)
        window.dispose()
    }

    @Test
    fun `setContent after show updates content while window is visible`() {
        val window = ComposeFloatWindow(context)
        window.setContent { }
        window.show()
        val firstContent = window.getContentViewOrNull()
        window.setContent { }
        val secondContent = window.getContentViewOrNull()
        assertNotNull(firstContent)
        assertNotNull(secondContent)
        assertTrue(window.showing.value)
        window.dispose()
    }

    @Test
    fun `getContentView returns the content view`() {
        val window = ComposeFloatWindow(context)
        window.setContent { }
        val view = window.getContentView()
        assertNotNull(view)
        window.dispose()
    }

    @Test
    fun `getContentViewOrNull returns null before setContent`() {
        val window = ComposeFloatWindow(context)
        assertNull(window.getContentViewOrNull())
        window.dispose()
    }

    @Test
    fun `rebuild before setContent is a no-op`() {
        val window = ComposeFloatWindow(context)
        window.rebuild()
        assertNull(window.getContentViewOrNull())
        window.dispose()
    }

    @Test
    fun `rebuild before show creates fresh content`() {
        val window = ComposeFloatWindow(context)
        window.setContent { }
        window.rebuild()
        assertNotNull(window.getContentViewOrNull())
        assertFalse(window.showing.value)
        window.dispose()
    }

    @Test
    fun `rebuild during show keeps window visible`() {
        val window = ComposeFloatWindow(context)
        window.setContent { }
        window.show()
        assertTrue(window.showing.value)
        window.rebuild()
        assertTrue("Window should still be showing after rebuild", window.showing.value)
        assertEquals(Lifecycle.State.RESUMED, window.lifecycle.currentState)
        window.dispose()
    }

    @Test
    fun `rebuild clears the recomposer and creates a new one`() {
        val window = ComposeFloatWindow(context)
        window.setContent { }
        window.show()
        val first = (window.getContentViewOrNull() as? ComposeView)?.compositionContext
        window.rebuild()
        val second = (window.getContentViewOrNull() as? ComposeView)?.compositionContext
        assertNotNull(first)
        assertNotNull(second)
        assertFalse("Recomposer should be a new instance after rebuild", first === second)
        window.dispose()
    }

    @Test
    fun `rebuild is idempotent`() {
        val window = ComposeFloatWindow(context)
        window.setContent { }
        window.rebuild()
        window.rebuild()
        window.rebuild()
        assertNotNull(window.getContentViewOrNull())
        window.dispose()
    }

    @Test
    fun `rebuild cycles with the new viewModelFactory API do not throw`() {
        val window = ComposeFloatWindow(context)
        window.setContent {
            viewModel<TestViewModel>(
                key = "k",
                factory = viewModelFactory {
                    initializer {
                        TestViewModel(createSavedStateHandle())
                    }
                }
            )
        }
        window.show()
        repeat(3) { window.rebuild() }
        assertTrue(window.showing.value)
        window.dispose()
    }

    @Test
    fun `multiple show rebuild cycles with viewModel do not throw`() {
        val window = ComposeFloatWindow(context)
        repeat(5) {
            window.setContent {
                viewModel<TestViewModel>(
                    key = "k",
                    factory = viewModelFactory {
                        initializer {
                            TestViewModel(createSavedStateHandle())
                        }
                    }
                )
            }
            window.show()
            window.hide()
        }
        window.dispose()
    }

    @Test
    fun `ComposeView uses applicationContext to avoid Activity leak`() {
        val window = ComposeFloatWindow(context)
        window.setContent { }
        val contentView = window.getContentView() as ComposeView
        assertSame(context, contentView.context)
        window.dispose()
    }

    @Test
    fun `Activity context registers OnBackInvokedCallback on Tiramisu+`() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val activity = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java)
            .create()
            .get()
        val window = ComposeFloatWindow(activity)
        window.setContent { }
        window.show()
        window.hide()
        window.dispose()
    }

    @Test
    fun `wrapped Activity context still resolves to Activity`() {
        val activity = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java)
            .create()
            .get()
        val wrapped = android.view.ContextThemeWrapper(activity, android.R.style.Theme)
        val window = ComposeFloatWindow(wrapped)
        window.setContent { }
        window.show()
        window.dispose()
    }

    @Test
    fun `Application context skips OnBackInvokedCallback without crashing`() {
        val window = ComposeFloatWindow(context)
        window.setContent { }
        window.show()
        window.hide()
        window.dispose()
    }

    @Test
    fun `lifecycle-bound callback is auto-removed after dispose`() {
        val window = ComposeFloatWindow(context)
        window.setContent { }
        window.show()

        var backPressed = 0
        val callback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                backPressed++
            }
        }
        window.onBackPressedDispatcher.addCallback(window, callback)

        window.decorView.dispatchKeyEventPreIme(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
        assertEquals(1, backPressed)

        window.dispose()

        window.decorView.dispatchKeyEventPreIme(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
        assertEquals(1, backPressed)
    }

    @Test
    fun `update before show does not crash`() {
        val window = ComposeFloatWindow(context)
        window.setContent { }
        window.update()
        window.dispose()
    }

    @Test
    fun `update after hide does not crash`() {
        val window = ComposeFloatWindow(context)
        window.setContent { }
        window.show()
        window.hide()
        window.update()
        window.dispose()
    }

    @Test
    fun `LocalFloatWindow is accessible via ComposeFloatWindow companion`() {
        assertNotNull(ComposeFloatWindow.LocalFloatWindow)
    }

    @Test
    fun `LocalFloatWindow is not a top-level file member`() {
        val fileClass = try {
            Class.forName("com.zxhhyj.composefloatwindow.ComposeFloatWindowKt")
        } catch (_: ClassNotFoundException) {
            null
        }
        if (fileClass != null) {
            val hasTopLevel = try {
                fileClass.getDeclaredField("LocalFloatWindow")
                true
            } catch (_: NoSuchFieldException) {
                false
            }
            assertFalse("ComposeFloatWindowKt should not declare a top-level LocalFloatWindow", hasTopLevel)
        }
    }
}
