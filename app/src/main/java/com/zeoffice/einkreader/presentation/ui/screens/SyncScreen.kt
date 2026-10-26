package com.zeoffice.einkreader.presentation.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.zeoffice.einkreader.domain.model.sync.*
import com.zeoffice.einkreader.presentation.viewmodel.MainViewModel

/**
 * WebDAV 双向同步 — 书籍文件、阅读进度与书签/笔记。
 * 支持: 账户选择 / 方向(双向·仅上传·仅下载) / 冲突策略 / 书籍文件开关 / 书签与笔记开关 / 结果汇总 / 重试。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncScreen(nav: NavHostController, vm: MainViewModel) {
    val allAccounts by vm.accounts.collectAsState()
    val accounts = allAccounts.filter { it.provider == "webdav" }
    val syncStatus by vm.syncStatus.collectAsState()

    var selectedId by remember { mutableStateOf<Int?>(accounts.firstOrNull()?.id) }
    val selectedAccount = accounts.firstOrNull { it.id == selectedId } ?: accounts.firstOrNull()
    LaunchedEffect(accounts) {
        if (selectedId == null || accounts.none { it.id == selectedId }) {
            selectedId = accounts.firstOrNull()?.id
        }
    }

    var direction by remember { mutableStateOf(SyncDirection.TWO_WAY) }
    var policy by remember { mutableStateOf(ConflictPolicy.NEWER_WINS) }
    var syncFiles by remember { mutableStateOf(true) }
    var syncBookmarks by remember { mutableStateOf(true) }
    var showPolicy by remember { mutableStateOf(false) }

    val isSyncing = syncStatus is SyncStatus.Syncing

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("WebDAV 同步") },
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
        Box(Modifier.padding(padding).fillMaxSize()) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (accounts.isEmpty()) {
                    Card(Modifier.fillMaxWidth()) {
                        Column(
                            Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                "尚未添加 WebDAV 服务器",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                "请先在「从 WebDAV 导入」中添加并测试服务器，再回来同步书库。",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Button(onClick = { nav.popBackStack() }) { Text("返回添加") }
                        }
                    }
                } else {
                    // 账户选择
                    Text("同步账户", style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        accounts.forEach { acc ->
                            FilterChip(
                                selected = acc.id == selectedAccount?.id,
                                onClick = { selectedId = acc.id },
                                label = { Text(acc.name.ifBlank { "WebDAV" }) }
                            )
                        }
                    }

                    HorizontalDivider()

                    // 同步方向
                    Text("同步方向", style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SyncDirection.values().forEach { d ->
                            FilterChip(
                                selected = direction == d,
                                onClick = { direction = d },
                                label = { Text(directionLabel(d)) }
                            )
                        }
                    }
                    Text(
                        "双向：本地与服务器互相同步；仅上传/仅下载：单向覆盖。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    HorizontalDivider()

                    // 冲突策略
                    Text("冲突处理（双向且两端均改动时）", style = MaterialTheme.typography.titleSmall)
                    ExposedDropdownMenuBox(
                        expanded = showPolicy,
                        onExpandedChange = { showPolicy = it }
                    ) {
                        OutlinedTextField(
                            value = policyLabel(policy),
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("冲突解决策略") },
                            modifier = Modifier.menuAnchor().fillMaxWidth()
                        )
                        ExposedDropdownMenu(
                            expanded = showPolicy,
                            onDismissRequest = { showPolicy = false }
                        ) {
                            ConflictPolicy.values().forEach { p ->
                                DropdownMenuItem(
                                    text = { Text(policyLabel(p)) },
                                    onClick = { policy = p; showPolicy = false }
                                )
                            }
                        }
                    }

                    HorizontalDivider()

                    // 同步书籍文件开关
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("同步书籍文件", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "关闭后仅同步阅读进度（适合大文件 / 省流量）",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(checked = syncFiles, onCheckedChange = { syncFiles = it })
                    }

                    HorizontalDivider()

                    // 同步书签与笔记开关
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("同步书签与笔记", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "关闭后仅同步书籍与进度，不同步划线/批注",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(checked = syncBookmarks, onCheckedChange = { syncBookmarks = it })
                    }

                    HorizontalDivider()

                    Button(
                        onClick = {
                            selectedAccount?.let { acc ->
                                vm.syncLibrary(acc, direction, policy, syncFiles, syncBookmarks)
                            }
                        },
                        enabled = !isSyncing && selectedAccount != null,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("开始同步") }
                }

                // 结果 / 错误展示
                when (val st = syncStatus) {
                    is SyncStatus.Done -> SyncResultCard(st.result) { vm.clearSyncStatus() }
                    is SyncStatus.Error -> {
                        Card(
                            Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer
                            )
                        ) {
                            Column(
                                Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    "同步失败：${st.msg}",
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                                Button(onClick = { vm.clearSyncStatus() }) { Text("关闭") }
                            }
                        }
                    }
                    else -> {}
                }
            }

            if (isSyncing) {
                ImportProgressOverlay((syncStatus as SyncStatus.Syncing).msg)
            }
        }
    }
}

@Composable
private fun SyncResultCard(result: SyncResult, onDismiss: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                if (result.success) "同步成功" else "同步完成（部分失败）",
                style = MaterialTheme.typography.titleMedium
            )
            Text(result.message, style = MaterialTheme.typography.bodyMedium)
            if (result.conflictsDetail.isNotEmpty()) {
                Text("冲突处理：", style = MaterialTheme.typography.labelMedium)
                result.conflictsDetail.forEach {
                    Text("· $it", style = MaterialTheme.typography.bodySmall)
                }
            }
            if (result.errors.isNotEmpty()) {
                Text(
                    "错误：",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error
                )
                result.errors.forEach {
                    Text(
                        "· $it",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
            Button(onClick = onDismiss) { Text("完成") }
        }
    }
}

private fun directionLabel(d: SyncDirection): String = when (d) {
    SyncDirection.TWO_WAY -> "双向"
    SyncDirection.PUSH -> "仅上传"
    SyncDirection.PULL -> "仅下载"
}

private fun policyLabel(p: ConflictPolicy): String = when (p) {
    ConflictPolicy.NEWER_WINS -> "以较新者为准"
    ConflictPolicy.LOCAL_WINS -> "以本地为准"
    ConflictPolicy.REMOTE_WINS -> "以服务器为准"
}
