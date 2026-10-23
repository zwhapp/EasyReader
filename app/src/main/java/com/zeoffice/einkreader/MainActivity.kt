package com.zeoffice.einkreader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
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
import com.zeoffice.einkreader.presentation.ui.theme.ReaderColors
import com.zeoffice.einkreader.presentation.ui.theme.READER_THEMES
import com.zeoffice.einkreader.presentation.viewmodel.MainViewModel

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
            MaterialTheme(colorScheme = colorScheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val nav: NavHostController = rememberNavController()
                    val vm: MainViewModel = viewModel()
                    NavHost(navController = nav, startDestination = "library") {
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
