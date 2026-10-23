package com.zeoffice.einkreader.presentation.ui.screens

import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.zeoffice.einkreader.data.db.BookEntity
import com.zeoffice.einkreader.domain.model.BookFormat
import com.zeoffice.einkreader.presentation.ui.theme.LibraryAccent
import com.zeoffice.einkreader.presentation.viewmodel.ImportStatus
import com.zeoffice.einkreader.presentation.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 支持的电子书 MIME (SAF 文件选择器过滤) */
private val IMPORT_MIME_TYPES = arrayOf(
    "text/plain",
    "application/epub+zip",
    "application/x-mobipocket-ebook",
    "application/vnd.amazon.ebook",
    "application/vnd.amazon.mobi8-ebook",
    "application/pdf"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(nav: NavHostController, vm: MainViewModel) {
    val books by vm.books.collectAsState()
    val importStatus by vm.importStatus.collectAsState()
    val context = LocalContext.current
    var showImport by remember { mutableStateOf(false) }
    var toDelete by remember { mutableStateOf<BookEntity?>(null) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }

    // 每次进入书架强制刷新, 确保从网盘/其他来源导入的书籍及时出现在书架 (#4)
    LaunchedEffect(Unit) { vm.loadBooks() }

    // SAF 单文件导入 (ACTION_OPEN_DOCUMENT): 系统文件选择器, 顶部可切换到百度网盘等 DocumentsProvider
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            // 优先查询真实文件名; lastPathSegment 可能是 "msf:123" 之类的内部 id
            val name = queryDisplayName(context, it) ?: it.lastPathSegment
                ?.substringAfterLast('/')?.substringAfterLast(':') ?: "book_${System.currentTimeMillis()}"
            vm.importUri(it, name)
        }
    }

    // SAF 文件夹批量导入 (ACTION_OPEN_DOCUMENT_TREE)
    val treeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let { vm.importTree(it) }
    }

    LaunchedEffect(importStatus) {
        if (importStatus is ImportStatus.Done) {
            Toast.makeText(context, "导入完成", Toast.LENGTH_SHORT).show()
            vm.clearImportIdle()
        } else if (importStatus is ImportStatus.Error) {
            Toast.makeText(context, (importStatus as ImportStatus.Error).msg, Toast.LENGTH_LONG).show()
            vm.clearImportIdle()
        }
    }

    // 搜索过滤: 书名 / 作者
    val filtered = if (query.isBlank()) books else books.filter {
        it.title.contains(query, ignoreCase = true) || it.author.contains(query, ignoreCase = true)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (searching) {
                        // 搜索模式: 顶栏变搜索框
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            placeholder = { Text("搜索书名 / 作者", fontSize = 14.sp) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                            textStyle = MaterialTheme.typography.bodyMedium
                        )
                    } else {
                        Text("我的书库")
                    }
                },
                navigationIcon = {
                    if (searching) {
                        IconButton(onClick = { searching = false; query = "" }) {
                            Icon(Icons.Default.Close, "取消搜索")
                        }
                    }
                },
                actions = {
                    if (!searching) {
                        IconButton(onClick = { searching = true }) {
                            Icon(Icons.Default.Search, "搜索")
                        }
                        IconButton(onClick = { nav.navigate("settings") }) {
                            Icon(Icons.Default.Settings, "设置")
                        }
                        IconButton(onClick = { nav.navigate("about") }) {
                            Icon(Icons.Default.Info, "关于")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showImport = true },
                containerColor = LibraryAccent
            ) {
                Icon(Icons.Default.Add, "导入图书")
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                books.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("书库为空，点击右下角 + 导入图书", color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f))
                    }
                }
                filtered.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("没有找到与「$query」匹配的书籍", color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f))
                    }
                }
                else -> {
                    LazyColumn(
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(filtered, key = { it.id }) { book ->
                            BookCard(
                                book = book,
                                onClick = { nav.navigate("reader/${book.id}") },
                                onLongClick = { toDelete = book }
                            )
                        }
                    }
                }
            }
            if (importStatus is ImportStatus.Importing) {
                ImportProgressOverlay((importStatus as ImportStatus.Importing).msg)
            }
        }
    }

    if (showImport) {
        ImportSourceDialog(
            onDismiss = { showImport = false },
            onPickSource = { source ->
                showImport = false
                when (source) {
                    "local" -> launcher.launch(IMPORT_MIME_TYPES)
                    "baidu" -> nav.navigate("baidupan")
                }
            },
            onWebDav = {
                showImport = false
                nav.navigate("cloud")
            },
            onBatch = {
                showImport = false
                treeLauncher.launch(null)
            }
        )
    }

    if (toDelete != null) {
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("删除图书") },
            text = { Text("确定删除《${toDelete!!.title}》？将同时删除本地文件。") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteBook(toDelete!!)
                    toDelete = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("取消") } }
        )
    }
}

/**
 * 导入来源对话框 — 三个入口:
 *  1. 从本机 / 网盘选择文件 (二级选择: 本机 SAF / 网盘扫码授权登录)
 *  2. 从 WebDAV 导入 (仅 WebDAV, 浏览目录导入)
 *  3. 批量导入 (选文件夹自动扫描电子书)
 */
@Composable
private fun ImportSourceDialog(
    onDismiss: () -> Unit,
    onPickSource: (String) -> Unit,   // "local" | "baidu"
    onWebDav: () -> Unit,
    onBatch: () -> Unit
) {
    var showLocalOrCloud by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (showLocalOrCloud) "从本机 / 网盘选择文件" else "导入图书") },
        text = {
            if (showLocalOrCloud) {
                // 二级选择: 本机文件 / 网盘 (扫码授权登录)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(
                        onClick = { onPickSource("local") },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column {
                            Text("本机文件", modifier = Modifier.fillMaxWidth())
                            Text(
                                "调用系统文件选择器",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    TextButton(
                        onClick = { onPickSource("baidu") },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column {
                            Text("网盘（扫码授权登录）", modifier = Modifier.fillMaxWidth())
                            Text(
                                "百度网盘扫码登录后浏览并导入，无需填写 AppKey",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    // 方式一: 本机 / 网盘
                    TextButton(
                        onClick = { showLocalOrCloud = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column {
                            Text("从本机 / 网盘选择文件", modifier = Modifier.fillMaxWidth())
                            Text(
                                "本机文件，或扫码登录网盘选择",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    // 方式二: WebDAV
                    TextButton(
                        onClick = onWebDav,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column {
                            Text("从 WebDAV 导入", modifier = Modifier.fillMaxWidth())
                            Text(
                                "连接 WebDAV 服务器，按目录浏览选择",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    // 方式三: 批量导入
                    TextButton(
                        onClick = onBatch,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column {
                            Text("批量导入", modifier = Modifier.fillMaxWidth())
                            Text(
                                "选择文件夹，自动扫描其中所有电子书",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = {
                if (showLocalOrCloud) showLocalOrCloud = false else onDismiss()
            }) { Text(if (showLocalOrCloud) "返回" else "取消") }
        }
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookCard(book: BookEntity, onClick: () -> Unit, onLongClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().combinedClickable(
            onClick = onClick,
            onLongClick = onLongClick
        ),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(Modifier.padding(12.dp)) {
            BookCover(book, Modifier.width(72.dp).height(100.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    book.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "${book.author.ifBlank { "未知作者" }} · ${runCatching { BookFormat.valueOf(book.format).label }.getOrDefault(book.format)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.weight(1f))
                LinearProgressIndicator(
                    progress = { (book.progressPercent.coerceIn(0, 100)) / 100f },
                    modifier = Modifier.fillMaxWidth().height(4.dp)
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "进度 ${book.progressPercent}%  ·  ${sourceLabel(book.source)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** 书籍封面: 优先显示已生成的封面图, 缺失时显示格式占位块 */
@Composable
private fun BookCover(book: BookEntity, modifier: Modifier) {
    val cover by produceState<android.graphics.Bitmap?>(initialValue = null, key1 = book.coverPath) {
        value = withContext(Dispatchers.IO) {
            if (book.coverPath.isNotBlank()) runCatching { BitmapFactory.decodeFile(book.coverPath) }.getOrNull()
            else null
        }
    }
    val formatLabel = runCatching { BookFormat.valueOf(book.format).label }.getOrDefault(book.format)
    if (cover != null) {
        Image(
            bitmap = cover!!.asImageBitmap(),
            contentDescription = "封面",
            modifier = modifier,
            contentScale = ContentScale.Crop
        )
    } else {
        Box(
            modifier.background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Text(
                formatLabel,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

private fun sourceLabel(s: String): String = when (s) {
    "local" -> "本机"
    "webdav" -> "WebDAV"
    "baidu" -> "百度网盘"
    "ali" -> "阿里云盘"
    else -> s
}

/** 查询 SAF 文档的真实显示名; 失败返回 null */
private fun queryDisplayName(context: android.content.Context, uri: android.net.Uri): String? = try {
    context.contentResolver.query(
        uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null
    )?.use { c -> if (c.moveToFirst()) c.getString(0)?.takeIf { it.isNotBlank() } else null }
} catch (_: Exception) {
    null
}
