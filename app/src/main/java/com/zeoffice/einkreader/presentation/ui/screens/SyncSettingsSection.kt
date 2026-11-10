package com.zeoffice.einkreader.presentation.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.zeoffice.einkreader.cloud.SyncTransportFactory
import com.zeoffice.einkreader.domain.model.CloudAccount
import com.zeoffice.einkreader.domain.model.sync.*
import com.zeoffice.einkreader.presentation.viewmodel.MainViewModel

/**
 * 设置页内的「书库同步」配置区。
 *
 * 同步方式、账户、同步目录、方向、冲突策略、同步范围、立即同步按钮与自动同步开关
 * 全部集中在此：不再有独立的同步页，导入页也不再放同步入口。
 * 同步执行仍走 MainViewModel.syncLibrary -> CloudSyncManager，同步与导入的行为不变。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncSettingsSection(nav: NavHostController, vm: MainViewModel) {
    val allAccounts by vm.accounts.collectAsState()
    val settings by vm.settings.collectAsState()
    val syncStatus by vm.syncStatus.collectAsState()
    val savedProvider by vm.syncProvider.collectAsState()

    val providers = SyncTransportFactory.providers
    val provider = when {
        savedProvider in providers -> savedProvider
        else -> providers.firstOrNull { p -> allAccounts.any { it.provider == p } }
            ?: providers.first()
    }
    val providerName = SyncTransportFactory.displayName(provider)
    val accounts = allAccounts.filter { it.provider == provider }

    var selectedId by remember { mutableStateOf<Int?>(null) }
    val selectedAccount = accounts.firstOrNull { it.id == selectedId } ?: accounts.firstOrNull()
    LaunchedEffect(provider, accounts) {
        if (selectedId == null || accounts.none { it.id == selectedId }) {
            selectedId = accounts.firstOrNull()?.id
        }
    }

    var direction by remember { mutableStateOf(SyncDirection.TWO_WAY) }
    var showPolicy by remember { mutableStateOf(false) }
    val policy = runCatching { ConflictPolicy.valueOf(settings.syncPolicy) }
        .getOrDefault(ConflictPolicy.NEWER_WINS)
    val isSyncing = syncStatus is SyncStatus.Syncing

    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("书库同步", style = MaterialTheme.typography.titleMedium)
            Text(
                "同步方式、账户与同步操作集中在此配置；导入图书仍从书架右下角「+」进入。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // ===== 同步方式（只列出当前支持的后端，不新增未经确认的方式）=====
            SectionLabel("同步方式")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                providers.forEach { p ->
                    FilterChip(
                        selected = provider == p,
                        onClick = { vm.setSyncProvider(p); selectedId = null },
                        label = { Text(SyncTransportFactory.displayName(p)) }
                    )
                }
            }
            Text(
                "仅列出当前支持的方式；未对个人开发者开放的网盘后端不再列入。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            HorizontalDivider()

            // ===== 同步账户 =====
            SectionLabel("同步账户")
            if (accounts.isEmpty()) {
                Text("尚未添加「$providerName」账户", style = MaterialTheme.typography.bodyMedium)
                Button(
                    onClick = { nav.navigate(if (provider == "webdav") "cloud" else "baidupan") }
                ) {
                    Text(if (provider == "webdav") "添加 WebDAV 服务器" else "扫码登录百度网盘")
                }
                Text(
                    "在对应的导入页添加账户后即可回到此处同步，导入功能保持不变。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                if (accounts.size > 1) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        accounts.forEach { acc ->
                            FilterChip(
                                selected = acc.id == selectedAccount?.id,
                                onClick = { selectedId = acc.id },
                                label = { Text(acc.name.ifBlank { providerName }) }
                            )
                        }
                    }
                } else {
                    Text(
                        "当前账户：${selectedAccount?.name?.ifBlank { providerName } ?: providerName}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                SyncRootSection(selectedAccount) { acc, root ->
                    vm.updateAccount(acc.copy(extra = root))
                }

                HorizontalDivider()

                // ===== 同步方向 =====
                SectionLabel("同步方向")
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
                    "双向：本地与云端互相同步；仅上传 / 仅下载：单向覆盖。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                HorizontalDivider()

                // ===== 冲突策略（手动与自动同步共用同一份设置）=====
                SectionLabel("冲突处理（双向且两端均改动时）")
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
                                onClick = {
                                    vm.saveSettings(settings.copy(syncPolicy = p.name))
                                    showPolicy = false
                                }
                            )
                        }
                    }
                }
                Text(
                    "「以较新者为准」按逻辑时钟裁决进度，书签与笔记按条目归并；手动与自动同步共用此策略。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // ===== 同步范围 =====
                SwitchRow(
                    title = "同步书籍文件",
                    checked = settings.syncFiles,
                    desc = "关闭后仅同步阅读进度与书签（适合大文件 / 省流量）",
                    onChange = { vm.saveSettings(settings.copy(syncFiles = it)) }
                )
                SwitchRow(
                    title = "同步书签与笔记",
                    checked = settings.syncBookmarks,
                    desc = "关闭后仅同步书籍与进度，不同步划线 / 批注",
                    onChange = { vm.saveSettings(settings.copy(syncBookmarks = it)) }
                )

                // ===== 立即同步（原导入页顶栏「同步」入口迁移至此）=====
                Button(
                    onClick = {
                        selectedAccount?.let { acc ->
                            // 策略与范围已直接写入设置，自动同步复用同一份配置
                            vm.syncLibrary(acc, direction, policy, settings.syncFiles, settings.syncBookmarks)
                        }
                    },
                    enabled = !isSyncing && selectedAccount != null,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (isSyncing) "同步中…" else "立即同步") }
                Text(
                    "同步结果在此卡片内显示；导入功能区不再提供同步入口。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // ===== 同步进度 / 结果 =====
            if (syncStatus is SyncStatus.Syncing) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        (syncStatus as SyncStatus.Syncing).msg,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            when (val st = syncStatus) {
                is SyncStatus.Done -> SyncResultCard(st.result) { vm.clearSyncStatus() }
                is SyncStatus.Error -> Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "同步失败：${st.msg}",
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        TextButton(onClick = { vm.clearSyncStatus() }) { Text("关闭") }
                    }
                }
                else -> {}
            }

            HorizontalDivider()

            // ===== 自动同步 =====
            SectionLabel("自动同步")
            Text(
                "开启后：进入程序自动从所选同步方式拉取并合并书籍与进度；退出前自动推送。可在书架页查看同步状态。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            SwitchRow(
                title = "启动自动同步",
                checked = settings.autoSyncOnStartup,
                desc = "进入程序时自动从默认同步账户拉取云端书籍与进度并合并",
                onChange = { vm.saveSettings(settings.copy(autoSyncOnStartup = it)) }
            )
            SwitchRow(
                title = "退出自动同步",
                checked = settings.autoSyncOnExit,
                desc = "退出程序前自动将本地书籍与进度推送至默认同步账户",
                onChange = { vm.saveSettings(settings.copy(autoSyncOnExit = it)) }
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall)
}

@Composable
private fun SwitchRow(
    title: String,
    checked: Boolean,
    desc: String,
    onChange: (Boolean) -> Unit
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** 同步目录说明；百度网盘需按开放平台应用目录改写，提供输入框直接保存 */
@Composable
private fun SyncRootSection(account: CloudAccount?, onSaveRoot: (CloudAccount, String) -> Unit) {
    val acc = account ?: return
    var rootText by remember(acc.id, acc.extra) {
        mutableStateOf(
            if (acc.provider == "baidu") {
                acc.extra.takeIf { it.startsWith("/apps/") } ?: "/apps/EasyReader"
            } else ""
        )
    }
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("同步目录", style = MaterialTheme.typography.titleSmall)
            if (acc.provider == "webdav") {
                Text(
                    "${acc.host.trimEnd('/')}/EasyReader/library.json 与 books/",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                OutlinedTextField(
                    value = rootText,
                    onValueChange = { rootText = it },
                    label = { Text("百度应用数据目录") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Text(
                    "百度开放平台只允许第三方应用读写「我的网盘 / 我的应用数据」目录。" +
                        "请把上面的目录改成开放平台分配的应用目录（形如 /apps/<应用名>），" +
                        "否则同步会返回目录权限错误。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(
                    onClick = { onSaveRoot(acc, rootText.trim()) },
                    enabled = rootText.startsWith("/apps/") && rootText.length > "/apps/".length
                ) { Text("保存目录") }
            }
        }
    }
}

@Composable
private fun SyncResultCard(result: SyncResult, onDismiss: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
            TextButton(onClick = onDismiss) { Text("完成") }
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
    ConflictPolicy.REMOTE_WINS -> "以云端为准"
}
