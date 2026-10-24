package com.zeoffice.einkreader

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalOverscrollConfiguration
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.zeoffice.einkreader.presentation.ui.screens.AboutScreen
import com.zeoffice.einkreader.presentation.ui.screens.BaiduPanScreen
import com.zeoffice.einkreader.presentation.ui.screens.CloudScreen
import com.zeoffice.einkreader.presentation.ui.screens.LibraryScreen
import com.zeoffice.einkreader.presentation.ui.screens.ReaderScreen
import com.zeoffice.einkreader.presentation.ui.screens.SettingsScreen
import com.zeoffice.einkreader.presentation.ui.screens.SyncScreen
import com.zeoffice.einkreader.presentation.ui.theme.READER_THEMES
import com.zeoffice.einkreader.presentation.ui.theme.ReaderColors
import com.zeoffice.einkreader.presentation.ui.theme.einkColorScheme
import com.zeoffice.einkreader.presentation.viewmodel.MainViewModel
import com.zeoffice.einkreader.utils.ReaderKeyDispatcher

@OptIn(ExperimentalFoundationApi::class)
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val vm: MainViewModel = viewModel()
            val settings by vm.settings.collectAsState()
            val eink = settings.einkMode
            // 电纸书模式：全局灰阶主题（所有 Material3 颜色角色降为灰阶），降低刷新残影、提升对比
            val colorScheme = if (eink) einkColorScheme() else {
                val base: ReaderColors = READER_THEMES["day"]!!
                androidx.compose.material3.lightColorScheme(
                    background = base.background,
                    onBackground = base.onBackground,
                    surface = base.surface,
                    primary = base.primary,
                    onSurface = base.onBackground,
                    surfaceVariant = base.surface,
                    onSurfaceVariant = base.onBackground
                )
            }
            // 电纸书模式关闭窗口切换动画（系统级 Activity 转场残影主因之一）
            LaunchedEffect(settings.einkMode) {
                if (settings.einkMode) window.setWindowAnimations(0)
            }
            MaterialTheme(colorScheme = colorScheme) {
                // 电纸书模式去除滚动过冲光晕（overscroll glow），避免边缘灰影
                CompositionLocalProvider(
                    LocalOverscrollConfiguration provides if (eink) null else LocalOverscrollConfiguration.current
                ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val nav: NavHostController = rememberNavController()
                    LaunchedEffect(Unit) {
                        vm.globalError.collect { msg ->
                            if (msg != null) {
                                android.widget.Toast
                                    .makeText(this@MainActivity, msg, android.widget.Toast.LENGTH_LONG)
                                    .show()
                                vm.clearGlobalError()
                            }
                        }
                    }
                    // 电纸书模式下关闭界面转场动画（残余灰影主因之一）；非电纸书保留轻量淡入淡出
                    val none = settings.einkMode
                    NavHost(
                        navController = nav,
                        startDestination = "library",
                        enterTransition = { if (none) EnterTransition.None else fadeIn(tween(150)) },
                        exitTransition = { if (none) ExitTransition.None else fadeOut(tween(150)) },
                        popEnterTransition = { if (none) EnterTransition.None else fadeIn(tween(150)) },
                        popExitTransition = { if (none) ExitTransition.None else fadeOut(tween(150)) }
                    ) {
                        composable("library") {
                            LibraryScreen(nav = nav, vm = vm)
                        }
                        composable("settings") {
                            SettingsScreen(nav = nav, vm = vm)
                        }
                        composable("about") {
                            AboutScreen(nav = nav, vm = vm)
                        }
                        composable("cloud") {
                            CloudScreen(nav = nav, vm = vm)
                        }
                        composable("sync") {
                            SyncScreen(nav = nav, vm = vm)
                        }
                        composable("baidupan") {
                            BaiduPanScreen(nav = nav, vm = vm)
                        }
                        composable("reader/{bookId}") { backStack ->
                            val bookId = backStack.arguments?.getString("bookId")?.toIntOrNull() ?: -1
                            ReaderScreen(nav = nav, vm = vm, bookId = bookId)
                        }
                    }
                }
                }
            }
        }
    }

    /**
     * 物理翻页键分发：在 Activity 最早阶段拦截电纸书实体翻页键。
     * 音量键若不在 Activity 层消费，会下传到系统弹出音量面板，因此必须在 super 之前 return true。
     * 仅当阅读界面已注册 [ReaderKeyDispatcher.handler] 且该处理器返回 true 时才消费事件，
     * 其余界面（书库/设置）与系统音量、媒体键完全不受影响。
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val h = ReaderKeyDispatcher.handler
        if (h != null && ReaderKeyDispatcher.isInterceptableKey(event.keyCode)) {
            if (h(event)) return true
        }
        return super.dispatchKeyEvent(event)
    }
}
