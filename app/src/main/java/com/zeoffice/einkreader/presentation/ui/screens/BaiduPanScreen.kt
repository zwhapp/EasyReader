package com.zeoffice.einkreader.presentation.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.zeoffice.einkreader.cloud.CloudProviderFactory
import com.zeoffice.einkreader.domain.model.BookFormat
import com.zeoffice.einkreader.domain.model.CloudAccount
import com.zeoffice.einkreader.domain.model.CloudFile
import com.zeoffice.einkreader.presentation.viewmodel.BaiduLoginState
import com.zeoffice.einkreader.presentation.viewmodel.ImportStatus
import com.zeoffice.einkreader.presentation.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 百度网盘 — 扫码授权登录 + 目录浏览导入。
 * 登录方式: OAuth 设备码, 用户用百度 App 扫二维码即可, 无需填写任何 AppKey。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BaiduPanScreen(nav: NavHostController, vm: MainViewModel) {
    val accounts by vm.accounts.collectAsState()
    val loginState by vm.baiduLogin.collectAsState()
    val importStatus by vm.importStatus.collectAsState()
    val context = LocalContext.current

    val baiduAccount: CloudAccount? = accounts.firstOrNull { it.provider == "baidu" && it.accessToken.isNotBlank() }
    // 默认进入 /apps: 百度开放平台仅允许第三方应用下载「我的应用数据」目录下的文件
    var currentPath by remember { mutableStateOf("/apps") }
    var files by remember { mutableStateOf<List<CloudFile>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var browseError by remember { mutableStateOf<String?>(null) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var showExitConfirm by remember { mutableStateOf(false) }

    // 退出确认: 拦截系统返回键与顶栏返回
    val requestExit = {
        showExitConfirm = true
        Unit
    }
    androidx.activity.compose.BackHandler(enabled = true) { requestExit() }

    // 登录成功 → 进入浏览
    LaunchedEffect(loginState) {
        if (loginState is BaiduLoginState.Success) refreshKey++
    }

    // 浏览目录 (授权后)
    LaunchedEffect(baiduAccount?.id, currentPath, refreshKey) {
        val acc = baiduAccount ?: return@LaunchedEffect
        loading = true
        browseError = null
        files = emptyList()
        try {
            val provider = CloudProviderFactory.create(acc)
            files = provider.listFiles(currentPath)
        } catch (e: Exception) {
            browseError = e.message ?: "加载失败"
        } finally {
            loading = false
        }
    }

    // 导入完成提示
    LaunchedEffect(importStatus) {
        when (importStatus) {
            is ImportStatus.Done -> {
                android.widget.Toast.makeText(context, "导入完成", android.widget.Toast.LENGTH_SHORT).show()
                vm.clearImportIdle()
                nav.popBackStack()
            }
            is ImportStatus.Error -> {
                android.widget.Toast.makeText(context, (importStatus as ImportStatus.Error).msg, android.widget.Toast.LENGTH_LONG).show()
                vm.clearImportIdle()
            }
            else -> {}
        }
    }

    // 退出二次确认: 确认→取消导入并返回; 取消→留在本页, 导入继续
    if (showExitConfirm) {
        AlertDialog(
            onDismissRequest = { showExitConfirm = false },
            title = { Text("退出网盘导入") },
            text = { Text("确认退出将取消正在进行的导入，已下载的临时文件会被丢弃。") },
            confirmButton = {
                TextButton(onClick = {
                    showExitConfirm = false
                    vm.cancelCloudImport()
                    nav.popBackStack()
                }) { Text("确认", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showExitConfirm = false }) { Text("取消") }
            }
        )
    }

    DisposableEffect(Unit) {
        onDispose { vm.resetBaiduLogin() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("百度网盘") },
                navigationIcon = {
                    IconButton(onClick = { requestExit() }) {
                        Icon(Icons.Filled.ArrowBack, "返回")
                    }
                },
                actions = {
                    if (baiduAccount != null) {
                        TextButton(onClick = {
                            // 刷新目录
                            refreshKey++
                        }) { Icon(Icons.Filled.Refresh, "刷新"); Text("刷新") }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                when {
                    // 未登录 → 扫码授权
                    baiduAccount == null -> QrLoginContent(
                        loginState = loginState,
                        onStart = { vm.startBaiduQrLogin() },
                        onRetry = { vm.startBaiduQrLogin() }
                    )
                    else -> {
                        // 目录权限说明 (百度开放平台限制)
                        Text(
                            "仅可下载「我的应用数据」目录下的文件（百度开放平台权限限制）",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                        // 面包屑 / 上级
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (currentPath.isNotBlank() && currentPath != "/") {
                                TextButton(onClick = {
                                    val idx = currentPath.trimEnd('/').lastIndexOf('/')
                                    currentPath = if (idx <= 0) "/" else currentPath.substring(0, idx)
                                }) { Text("← 上级目录") }
                            }
                            Spacer(Modifier.weight(1f))
                            if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        }
                        when {
                            loading && files.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator()
                            }
                            browseError != null -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        browseError!!,
                                        color = MaterialTheme.colorScheme.error,
                                        textAlign = TextAlign.Center,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Spacer(Modifier.height(12.dp))
                                    TextButton(onClick = { refreshKey++ }) { Text("重试") }
                                }
                            }
                            files.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("此目录为空", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            else -> LazyColumn(
                                contentPadding = PaddingValues(12.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                items(files, key = { it.path + it.name }) { f ->
                                    FileRow(
                                        file = f,
                                        onClick = {
                                            if (f.isDir) {
                                                currentPath = if (currentPath.isBlank() || currentPath == "/") {
                                                    "/" + f.name
                                                } else {
                                                    currentPath.trimEnd('/') + "/" + f.name
                                                }
                                            } else if (BookFormat.fromFilename(f.name) != null) {
                                                vm.importCloud(baiduAccount, f)
                                            } else {
                                                android.widget.Toast.makeText(context, "不支持的格式", android.widget.Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
            if (importStatus is ImportStatus.Importing) {
                ImportProgressOverlay((importStatus as ImportStatus.Importing).msg)
            }
        }
    }
}

/** 扫码授权内容区 */
@Composable
private fun QrLoginContent(
    loginState: BaiduLoginState,
    onStart: () -> Unit,
    onRetry: () -> Unit
) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        when (val s = loginState) {
            is BaiduLoginState.Idle -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("使用百度 App 扫码授权登录", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                Spacer(Modifier.height(8.dp))
                Text(
                    "无需输入账号密码，也无需填写 AppKey",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(20.dp))
                Button(onClick = onStart) { Text("生成登录二维码") }
            }
            is BaiduLoginState.Qr -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                QrImage(url = s.qrUrl, fallbackText = s.userCode, verificationUrl = s.verificationUrl)
                Spacer(Modifier.height(16.dp))
                Text("请用百度 App 扫描二维码", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(6.dp))
                Text(
                    "或电脑访问 ${s.verificationUrl} 输入验证码：",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Text(
                    s.userCode,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("等待扫码确认…", style = MaterialTheme.typography.bodySmall)
                }
            }
            is BaiduLoginState.Success -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("登录成功 ✓", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
            is BaiduLoginState.Failed -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    s.msg,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = onRetry) { Text("重新生成二维码") }
            }
        }
    }
}

/** 加载并显示百度返回的二维码图片 */
@Composable
private fun QrImage(url: String, fallbackText: String, verificationUrl: String) {
    val qr by produceState<Bitmap?>(initialValue = null, key1 = url) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                if (url.isBlank()) return@runCatching null
                val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                conn.inputStream.use { BitmapFactory.decodeStream(it) }
            }.getOrNull()
        }
    }
    val size = 220.dp
    Box(
        Modifier.size(size).background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center
    ) {
        val bmp = qr
        if (bmp != null) {
            Image(bitmap = bmp.asImageBitmap(), contentDescription = "登录二维码", modifier = Modifier.size(size - 16.dp))
        } else {
            // 二维码图加载失败时, 展示备用说明 (仍可用验证码方式)
            Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "二维码加载中或不可见\n请用上方验证码方式登录",
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun FileRow(file: CloudFile, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (file.isDir) Icons.Filled.Folder else Icons.Filled.InsertDriveFile,
            contentDescription = null,
            tint = if (file.isDir) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(file.name, style = MaterialTheme.typography.bodyMedium)
            if (!file.isDir && file.sizeBytes > 0) {
                Text(
                    formatCloudSize(file.sizeBytes),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun formatCloudSize(bytes: Long): String {
    val kb = bytes / 1024.0
    return when {
        kb < 1024 -> String.format("%.1f KB", kb)
        kb < 1024 * 1024 -> String.format("%.1f MB", kb / 1024)
        else -> String.format("%.2f GB", kb / 1024 / 1024)
    }
}
