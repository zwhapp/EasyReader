package com.zeoffice.einkreader.presentation.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import com.zeoffice.einkreader.domain.model.BookFormat
import com.zeoffice.einkreader.domain.model.CloudAccount
import com.zeoffice.einkreader.domain.model.CloudFile
import com.zeoffice.einkreader.presentation.viewmodel.ImportStatus
import com.zeoffice.einkreader.presentation.viewmodel.MainViewModel
import com.zeoffice.einkreader.webdav.WebDavClient
import kotlinx.coroutines.launch

/**
 * 从 WebDAV 导入 — 添加/选择 WebDAV 账户, 按目录浏览并导入电子书。
 * (网盘导入已独立到「百度网盘扫码登录」入口, 本页仅保留 WebDAV)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudScreen(nav: NavHostController, vm: MainViewModel) {
    val allAccounts by vm.accounts.collectAsState()
    val importStatus by vm.importStatus.collectAsState()
    val context = LocalContext.current

    // 本页仅展示 WebDAV 账户
    val accounts = allAccounts.filter { it.provider == "webdav" }
    var selectedId by remember { mutableStateOf<Int?>(accounts.firstOrNull()?.id) }
    val selectedAccount = accounts.firstOrNull { it.id == selectedId } ?: accounts.firstOrNull()
    val effectiveId = selectedAccount?.id
    // 保证选中态与列表同步
    LaunchedEffect(accounts) {
        if (selectedId == null || accounts.none { it.id == selectedId }) {
            selectedId = accounts.firstOrNull()?.id
        }
    }

    var currentPath by remember { mutableStateOf("") }
    var files by remember { mutableStateOf<List<CloudFile>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var browseError by remember { mutableStateOf<String?>(null) }
    var showAdd by remember { mutableStateOf(false) }
    var refreshKey by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    // 浏览当前账户的目录 — 失败时给出具体错误, 不再显示"此目录为空"
    LaunchedEffect(effectiveId, currentPath, refreshKey) {
        val acc = selectedAccount ?: return@LaunchedEffect
        loading = true
        browseError = null
        files = emptyList()
        try {
            val provider = WebDavClient(acc.host, acc.username, acc.password)
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("从 WebDAV 导入") },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.Filled.ArrowBack, "返回")
                    }
                },
                actions = {
                    TextButton(onClick = { showAdd = true }) { Text("添加服务器") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            // 账户列表
            if (accounts.isEmpty()) {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text("尚未添加 WebDAV 服务器", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.heightIn(max = 160.dp)
                ) {
                    items(accounts, key = { it.id }) { acc ->
                        AccountChip(
                            account = acc,
                            selected = acc.id == effectiveId,
                            onClick = {
                                selectedId = acc.id
                                currentPath = ""
                            },
                            onDelete = { vm.deleteAccount(acc.id) }
                        )
                    }
                }
            }

            HorizontalDivider()

            // 目录浏览
            if (selectedAccount == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("请点右上角「添加服务器」", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                // 面包屑 / 上级
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (currentPath.isNotBlank() &&
                        currentPath.trimEnd('/') != selectedAccount.host.trimEnd('/')
                    ) {
                        TextButton(onClick = {
                            val idx = currentPath.trimEnd('/').lastIndexOf('/')
                            currentPath = if (idx <= 7) selectedAccount.host.trimEnd('/')
                            else currentPath.substring(0, idx)
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
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
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
                                        currentPath = f.path
                                    } else if (BookFormat.fromFilename(f.name) != null) {
                                        vm.importCloud(selectedAccount, f)
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

    if (showAdd) {
        AddAccountDialog(
            onDismiss = { showAdd = false },
            onSave = { acc ->
                scope.launch {
                    val id = vm.saveAccount(acc)
                    selectedId = id.toInt()
                    currentPath = ""
                    showAdd = false
                }
            },
            onTestWebDav = { host, user, pass -> vm.testWebDavConnection(host, user, pass) }
        )
    }
}

@Composable
private fun AccountChip(
    account: CloudAccount,
    selected: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        tonalElevation = if (selected) 4.dp else 0.dp,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onClick).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(account.name.ifBlank { "WebDAV" }, style = MaterialTheme.typography.bodyMedium)
                Text(
                    account.host.ifBlank { "WebDAV" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, "删除") }
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
                Text(formatSize(file.sizeBytes), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddAccountDialog(
    onDismiss: () -> Unit,
    onSave: (CloudAccount) -> Unit,
    onTestWebDav: suspend (String, String, String) -> String
) {
    var name by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<Pair<Boolean, String>?>(null) } // (成功?, 消息)
    val scope = rememberCoroutineScope()

    fun runTest() {
        if (host.isBlank()) {
            testResult = false to "请先填写服务器地址"
            return
        }
        testResult = null
        testing = true
        scope.launch {
            val msg = onTestWebDav(host.trim(), username.trim(), password)
            testResult = msg.startsWith("连接成功") to msg
            testing = false
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加 WebDAV 服务器") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(host, { host = it; testResult = null }, label = { Text("服务器地址 (含 http(s)://)") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(username, { username = it }, label = { Text("用户名") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(password, { password = it }, label = { Text("密码") }, modifier = Modifier.fillMaxWidth(), visualTransformation = PasswordVisualTransformation())
                // 测试连接: 验证地址 / 账号 / 密码是否可用
                OutlinedButton(
                    onClick = { runTest() },
                    enabled = !testing,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (testing) "正在测试…" else "测试连接")
                }
                testResult?.let { (ok, msg) ->
                    Text(
                        msg,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (ok) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error
                    )
                }
                OutlinedTextField(name, { name = it }, label = { Text("备注名（可选）") }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(
                    CloudAccount(
                        provider = "webdav",
                        name = name.ifBlank { "WebDAV" },
                        host = host.trim(),
                        username = username.trim(),
                        password = password
                    )
                )
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

private fun formatSize(bytes: Long): String {
    val kb = bytes / 1024.0
    return when {
        kb < 1024 -> String.format("%.1f KB", kb)
        kb < 1024 * 1024 -> String.format("%.1f MB", kb / 1024)
        else -> String.format("%.2f GB", kb / 1024 / 1024)
    }
}
