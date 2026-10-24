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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
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
import com.zeoffice.einkreader.presentation.viewmodel.MainViewModel
import com.zeoffice.einkreader.utils.PageKeyBridge

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            // 用「白天」阅读主题作为全局基础配色 (书库/设置/关于), 阅读页内部另行套用所选主题
            val base: ReaderColors = READER_THEMES["day"]!!
            val colorScheme = androidx.compose.material3.lightColorScheme(
                background = base.background,
                onBackground = base.onBackground,
                surface = base.surface,
                primary = base.primary,
                onSurface = base.onBackground,
                surfaceVariant = base.surface,
                onSurfaceVariant = base.onBackground
            )
            val vm: MainViewModel = viewModel()
            val settings by vm.settings.collectAsState()
            MaterialTheme(colorScheme = colorScheme) {
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

    /**
     * 物理翻页键转发：仅当阅读界面已注册处理器且识别为翻页键时才消费，
     * 避免影响书库/设置等其它界面。长按连发(repeat)在阅读界面侧统一忽略。
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (PageKeyBridge.isPageKey(keyCode) && PageKeyBridge.handler != null) {
            if (PageKeyBridge.handler!!.invoke(keyCode, true, event.repeatCount > 0)) return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (PageKeyBridge.isPageKey(keyCode) && PageKeyBridge.handler != null) {
            if (PageKeyBridge.handler!!.invoke(keyCode, false, event.repeatCount > 0)) return true
        }
        return super.onKeyUp(keyCode, event)
    }
}
