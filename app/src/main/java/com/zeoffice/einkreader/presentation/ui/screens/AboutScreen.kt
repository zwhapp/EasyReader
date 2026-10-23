package com.zeoffice.einkreader.presentation.ui.screens

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.zeoffice.einkreader.AppConfig
import com.zeoffice.einkreader.presentation.viewmodel.MainViewModel
import com.zeoffice.einkreader.presentation.viewmodel.UpdateInfo

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(nav: NavHostController, vm: MainViewModel) {
    val context = LocalContext.current
    val updateInfo by vm.updateInfo.collectAsState()
    val checking by vm.checkingUpdate.collectAsState()
    val updateProgress by vm.updateProgress.collectAsState()
    val updateRunning by vm.updateRunning.collectAsState()
    var useMirror by remember { mutableStateOf(true) }

    // 进入页面即检查一次
    LaunchedEffect(Unit) {
        vm.checkUpdate(useMirror)
    }

    // 检查结果的明确反馈 (Toast + 界面文字)
    LaunchedEffect(updateInfo) {
        val info = updateInfo ?: return@LaunchedEffect
        when {
            info.error != null ->
                Toast.makeText(context, "检查更新失败：${info.error}", Toast.LENGTH_LONG).show()
            info.hasNew -> Toast.makeText(
                context, "发现新版本 ${info.release?.tag}", Toast.LENGTH_LONG
            ).show()
            else -> Toast.makeText(context, "当前已是最新版本", Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("关于") },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.Filled.ArrowBack, "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("EasyReader 简单阅读", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "当前版本：${AppConfig.VERSION_NAME} (${AppConfig.VERSION_CODE})",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "纯本地电子书阅读器 · 无广告 · 无埋点",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 更新检查
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("软件更新", style = MaterialTheme.typography.titleMedium)

                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("使用加速镜像 (gh-proxy)", style = MaterialTheme.typography.bodyMedium)
                        Switch(checked = useMirror, onCheckedChange = { useMirror = it })
                    }

                    Button(
                        onClick = { vm.checkUpdate(useMirror) },
                        enabled = !checking,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (checking) "正在检查…" else "检查更新")
                    }

                    val info = updateInfo
                    when {
                        info?.error != null -> Text(
                            "检查失败：${info.error}",
                            color = MaterialTheme.colorScheme.error
                        )
                        info != null && info.hasNew -> {
                            info.release?.let {
                                Text(
                                    "发现新版本：${it.tag}（当前 ${AppConfig.VERSION_NAME}）",
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Text(
                                    it.body?.take(300) ?: "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Button(
                                onClick = {
                                    info.apkUrl?.let { url -> vm.downloadAndInstallUpdate(url) }
                                        ?: Toast.makeText(context, "未找到 APK 下载地址", Toast.LENGTH_SHORT).show()
                                },
                                enabled = !updateRunning,
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary
                                )
                            ) {
                                Text(
                                    if (updateRunning) "处理中…"
                                    else "下载并安装 (${if (info.mirror) "镜像" else "官方"})"
                                )
                            }
                            // 下载/安装进度与结果提示 (不再因异常直接崩溃)
                            if (updateRunning) {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            }
                            updateProgress?.let { msg ->
                                Text(
                                    msg,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (msg.contains("失败") || msg.contains("不一致"))
                                        MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        info != null -> Text(
                            "当前已是最新版本（${AppConfig.VERSION_NAME}）",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        else -> Text(
                            "点击「检查更新」获取最新版本信息",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // 隐私与分发说明
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("隐私与合规", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "• 本应用为纯本地阅读器，不收集任何个人信息，无后台联网、无广告、无埋点统计。\n" +
                                "• 仅在你主动「检查更新」时连接 GitHub；仅在你配置并主动导入时连接你自己的网盘 / WebDAV。\n" +
                                "• 书籍文件、阅读进度、书签均仅存储于本机私有目录，不会上传。\n" +
                                "• 自动更新会校验下载 APK 与当前应用的签名一致，防止被替换为恶意包。\n" +
                                "• 已关闭系统自动备份（allowBackup=false），避免隐私数据落入云端。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Text(
                "开源地址：github.com/zeoffice/einkreader",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
