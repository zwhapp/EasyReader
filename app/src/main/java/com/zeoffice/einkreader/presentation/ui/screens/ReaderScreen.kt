package com.zeoffice.einkreader.presentation.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavHostController
import android.view.KeyEvent
import com.zeoffice.einkreader.domain.model.BookFormat
import com.zeoffice.einkreader.domain.model.Bookmark
import com.zeoffice.einkreader.presentation.ui.theme.READER_THEMES
import com.zeoffice.einkreader.presentation.viewmodel.MainViewModel
import com.zeoffice.einkreader.reader.PdfReaderView
import com.zeoffice.einkreader.reader.ReaderView
import com.zeoffice.einkreader.utils.PageKeyBridge
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(nav: NavHostController, vm: MainViewModel, bookId: Int) {
    val books by vm.books.collectAsState()
    val parsed by vm.parsedBook.collectAsState()
    val openError by vm.openError.collectAsState()
    val settings by vm.settings.collectAsState()
    val bookmarks by vm.bookmarks.collectAsState()

    val book = books.firstOrNull { it.id == bookId }
    val isPdf = book?.format == BookFormat.PDF.name
    val theme = READER_THEMES[settings.theme] ?: READER_THEMES["day"]!!

    // 状态持有者 (供稳定触摸回调读取最新值)
    val chapterIdx = remember { mutableIntStateOf(book?.chapterIndex ?: 0) }
    val pageIdx = remember { mutableIntStateOf(0) }
    val menuOpen = remember { mutableStateOf(false) }
    val readerViewRef = remember { mutableStateOf<ReaderView?>(null) }
    val pdfViewRef = remember { mutableStateOf<PdfReaderView?>(null) }
    val sheet = remember { mutableStateOf<String?>(null) }      // settings / toc / bookmarks
    val dict = remember { mutableStateOf<Pair<String, String>?>(null) }
    var showExit by remember { mutableStateOf(false) }

    // 进入阅读：打开书籍 + 载入书签 (离开时清掉错误状态)
    LaunchedEffect(bookId) {
        vm.clearOpenError()
        vm.loadBookmarks(bookId)
        book?.let { vm.openBook(it) }
    }
    DisposableEffect(Unit) {
        onDispose { vm.clearOpenError() }
    }

    // 应用阅读器排版（随章节 / 设置变化重排）
    fun applyReader(gotoLast: Boolean = false) {
        val rv = readerViewRef.value ?: return
        val p = vm.parsedBook.value ?: return
        if (p.chapters.isEmpty()) return
        val ch = p.chapters.getOrElse(chapterIdx.value) { p.chapters.first() }
        rv.configure(vm.settings.value, theme.background.toArgb(), theme.onBackground.toArgb(), settings.einkMode)
        rv.setChapter(chapterIdx.value, ch.text)
        if (gotoLast) rv.setPage(rv.totalPages - 1)
        else rv.setPage(pageIdx.value.coerceIn(0, (rv.totalPages - 1).coerceAtLeast(0)))
    }

    fun saveProgress() {
        val b = vm.books.value.firstOrNull { it.id == bookId } ?: return
        if (b.format == BookFormat.PDF.name) {
            val p = pdfViewRef.value ?: return
            val count = (p.pageCountValue).coerceAtLeast(1)
            val cur = p.currentPageIndex
            vm.savePosition(b.id, 0, cur, ((cur + 1) * 100 / count).coerceIn(0, 100))
        } else {
            val p = vm.parsedBook.value ?: return
            val total = p.chapters.sumOf { it.text.length }.coerceAtLeast(1)
            val before = p.chapters.take(chapterIdx.value).sumOf { it.text.length }
            val offset = readerViewRef.value?.currentCharOffset() ?: 0
            val percent = ((before + offset) * 100 / total).coerceIn(0, 100)
            vm.savePosition(b.id, chapterIdx.value, offset, percent)
        }
    }

    fun turnPage(delta: Int) {
        if (menuOpen.value) { menuOpen.value = false; return }
        val p = vm.parsedBook.value ?: return
        val rv = readerViewRef.value ?: return
        if (delta > 0) {
            if (pageIdx.value < rv.totalPages - 1) {
                pageIdx.value += 1
                rv.setPage(pageIdx.value)
            } else if (chapterIdx.value < p.chapters.lastIndex) {
                chapterIdx.value += 1
                pageIdx.value = 0
                applyReader()
            }
        } else {
            if (pageIdx.value > 0) {
                pageIdx.value -= 1
                rv.setPage(pageIdx.value)
            } else if (chapterIdx.value > 0) {
                chapterIdx.value -= 1
                pageIdx.value = 0
                applyReader(gotoLast = true)
            }
        }
        saveProgress()
    }

    fun turnPdf(delta: Int) {
        if (menuOpen.value) { menuOpen.value = false; return }
        val p = pdfViewRef.value ?: return
        if (delta > 0 && p.currentPageIndex < p.pageCountValue - 1) p.setPage(p.currentPageIndex + 1)
        else if (delta < 0 && p.currentPageIndex > 0) p.setPage(p.currentPageIndex - 1)
        saveProgress()
    }

    fun gotoChapter(delta: Int) {
        val p = vm.parsedBook.value ?: return
        val target = (chapterIdx.value + delta).coerceIn(0, p.chapters.lastIndex)
        if (target != chapterIdx.value) {
            chapterIdx.value = target
            pageIdx.value = 0
            applyReader()
            saveProgress()
        }
    }

    // 物理翻页键：阅读态注册处理器，按设置映射上下键为翻页；菜单/弹窗打开时屏蔽翻页但消费按键
    DisposableEffect(Unit) {
        PageKeyBridge.handler = handler@{ keyCode, isDown, repeat ->
            val s = vm.settings.value
            if (s.pageKeyAction == "off") return@handler false
            // 菜单/弹窗打开时不翻页，但消费按键避免穿透到系统
            if (menuOpen.value || sheet.value != null || showExit) return@handler true
            val isPrev = when (keyCode) {
                KeyEvent.KEYCODE_PAGE_UP -> s.pageKeyAction == "prev_next"
                KeyEvent.KEYCODE_PAGE_DOWN -> s.pageKeyAction != "prev_next"
                else -> return@handler false
            }
            val delta = if (isPrev) -1 else 1
            if (isDown && !repeat) {
                if (isPdf) turnPdf(delta) else turnPage(delta)
            }
            true
        }
        onDispose { PageKeyBridge.handler = null }
    }

    // 文本阅读器：随 parsed / settings / 章节 重排（页内翻页不触发整章重排）
    LaunchedEffect(parsed, settings, chapterIdx.value) {
        if (!isPdf) applyReader()
    }
    // 首次定位到上次阅读位置
    var restored by remember { mutableStateOf(false) }
    LaunchedEffect(parsed) {
        if (!isPdf && parsed != null && !restored && book != null) {
            readerViewRef.value?.gotoOffset(book.charOffset)
            pageIdx.value = readerViewRef.value?.currentPageIndex ?: 0
            restored = true
        }
    }

    BackHandler(enabled = true) { showExit = true }

    // 保持屏幕常亮
    val view = LocalView.current
    DisposableEffect(settings.keepScreenOn) {
        view.keepScreenOn = settings.keepScreenOn
        onDispose { view.keepScreenOn = false }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(theme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        when {
            book == null -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("书籍不存在", color = theme.onBackground)
                }
            }
            isPdf -> {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        PdfReaderView(ctx).also {
                            it.onPageTurn = { turnPdf(it) }
                            it.onCenterTap = { menuOpen.value = !menuOpen.value }
                            book?.let { b -> it.open(File(b.filePath)) }
                            pdfViewRef.value = it
                        }
                    }
                )
            }
            parsed == null && openError == null -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("正在打开…", color = theme.onBackground)
                }
            }
            openError != null -> {
                Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            openError ?: "打开失败",
                            color = MaterialTheme.colorScheme.error,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = { nav.popBackStack() }) { Text("返回书库") }
                    }
                }
            }
            else -> {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        ReaderView(ctx).also {
                            it.onPageTurn = { turnPage(it) }
                            it.onCenterTap = { menuOpen.value = !menuOpen.value }
                            it.onWordTap = { word, clickIdx ->
                                // 长按取词: word 是连续字词片段, 依次尝试所有包含
                                // 点击字符的子串 (长->短), 命中即弹词典
                                var hit: Pair<String, String>? = null
                                outer@ for (len in word.length downTo 1) {
                                    var start = minOf(clickIdx, word.length - len)
                                    while (start >= 0) {
                                        val entry = vm.dictRepo.lookupEntry(word.substring(start, start + len))
                                        if (entry != null) {
                                            hit = entry
                                            break@outer
                                        }
                                        start--
                                    }
                                }
                                if (hit != null) dict.value = hit
                            }
                            readerViewRef.value = it
                        }
                    }
                )
            }
        }

        // 应用内亮度调暗（覆盖在阅读区之上）
        // 电纸书模式下禁用：半透明黑层会在墨水屏上留下明显灰影，且调暗意义不大（交予系统亮度）
        if (settings.brightness < 1f && !settings.einkMode) {
            Box(
                Modifier.fillMaxSize()
                    .background(Color.Black.copy(alpha = (1f - settings.brightness).coerceIn(0f, 0.9f)))
            )
        }

        // 菜单浮层（点击空白关闭）
        if (menuOpen.value) {
            Box(
                Modifier.fillMaxSize().clickable { menuOpen.value = false }
            ) {
                // 顶栏
                Row(
                    Modifier.align(Alignment.TopCenter).fillMaxWidth()
                        .background(theme.surface).padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { showExit = true }) {
                        Icon(Icons.Filled.ArrowBack, "返回", tint = theme.onBackground)
                    }
                    Text(
                        book?.title ?: "",
                        color = theme.onBackground,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                        maxLines = 1
                    )
                    IconButton(onClick = { sheet.value = "toc" }) {
                        Icon(Icons.Filled.MenuBook, "目录", tint = theme.onBackground)
                    }
                    IconButton(onClick = { sheet.value = "bookmarks" }) {
                        Icon(Icons.Filled.Bookmark, "书签", tint = theme.onBackground)
                    }
                    IconButton(onClick = { sheet.value = "settings" }) {
                        Icon(Icons.Filled.Settings, "设置", tint = theme.onBackground)
                    }
                }
                // 底栏
                Row(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .background(theme.surface).padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { if (isPdf) turnPdf(-1) else gotoChapter(-1) }) {
                        Icon(Icons.Filled.ChevronLeft, "上一章/页", tint = theme.onBackground)
                    }
                    Text(
                        progressText(isPdf, parsed, chapterIdx.value, pageIdx.value, pdfViewRef.value, readerViewRef.value),
                        color = theme.onBackground,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center
                    )
                    IconButton(onClick = { if (isPdf) turnPdf(1) else gotoChapter(1) }) {
                        Icon(Icons.Filled.ChevronRight, "下一章/页", tint = theme.onBackground)
                    }
                    IconButton(onClick = { sheet.value = "settings" }) {
                        Icon(Icons.Filled.FormatSize, "排版", tint = theme.onBackground)
                    }
                }
            }
        }
    }

    // 退出确认
    if (showExit) {
        AlertDialog(
            onDismissRequest = { showExit = false },
            title = { Text("返回书库") },
            text = { Text("阅读进度已自动保存，确定返回？") },
            confirmButton = {
                TextButton(onClick = {
                    showExit = false
                    vm.clearParsed()
                    nav.popBackStack()
                }) { Text("返回书库") }
            },
            dismissButton = { TextButton(onClick = { showExit = false }) { Text("继续阅读") } }
        )
    }

    // 词典弹窗
    dict.value?.let { (w, d) ->
        AlertDialog(
            onDismissRequest = { dict.value = null },
            title = { Text(w) },
            text = { Text(d) },
            confirmButton = { TextButton(onClick = { dict.value = null }) { Text("关闭") } }
        )
    }

    // 底部设置 / 目录 / 书签
    val sheetType = sheet.value
    if (sheetType != null) {
        ModalBottomSheet(
            onDismissRequest = { sheet.value = null },
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            when (sheetType) {
                "settings" -> ReaderSettingsSheet(vm, settings) { sheet.value = null }
                "toc" -> TocSheet(parsed, chapterIdx.value) { i ->
                    chapterIdx.value = i
                    pageIdx.value = 0
                    applyReader()
                    saveProgress()
                    sheet.value = null
                }
                "bookmarks" -> BookmarksSheet(
                    bookmarks = bookmarks,
                    bookId = bookId,
                    vm = vm,
                    onJump = { bm ->
                        if (!isPdf) {
                            chapterIdx.value = bm.chapterIndex
                            pageIdx.value = 0
                            applyReader()
                            readerViewRef.value?.gotoOffset(bm.charOffset)
                            pageIdx.value = readerViewRef.value?.currentPageIndex ?: 0
                            saveProgress()
                        }
                        sheet.value = null
                    },
                    onAdd = {
                        val quote = readerViewRef.value?.currentLineText() ?: ""
                        val offset = readerViewRef.value?.currentCharOffset() ?: 0
                        vm.addBookmark(
                            Bookmark(
                                bookId = bookId,
                                chapterIndex = chapterIdx.value,
                                charOffset = offset,
                                quote = quote
                            )
                        )
                    },
                    onRemove = { id -> vm.removeBookmark(id, bookId) }
                )
            }
        }
    }
}

@Composable
private fun progressText(
    isPdf: Boolean,
    parsed: com.zeoffice.einkreader.domain.model.ParsedBook?,
    chapterIdx: Int,
    pageIdx: Int,
    pdf: PdfReaderView?,
    reader: ReaderView?
): String {
    return if (isPdf) {
        val count = (pdf?.pageCountValue ?: 1).coerceAtLeast(1)
        "第 ${(pdf?.currentPageIndex ?: 0) + 1} / $count 页"
    } else {
        val total = reader?.totalPages ?: 1
        "第 ${chapterIdx + 1} 章 · 第 ${(reader?.currentPageIndex ?: 0) + 1} / ${total.coerceAtLeast(1)} 页"
    }
}

@Composable
private fun ReaderSettingsSheet(
    vm: MainViewModel,
    settings: com.zeoffice.einkreader.domain.model.ReadingSettings,
    onClose: () -> Unit
) {
    Column(
        Modifier.fillMaxWidth().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("阅读设置", style = MaterialTheme.typography.titleMedium)

        // 主题
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            READER_THEMES.forEach { (key, c) ->
                Box(
                    Modifier.size(44.dp).background(c.background, RoundedCornerShape(8.dp))
                        .clickable { vm.saveSettings(settings.copy(theme = key)) },
                    contentAlignment = Alignment.Center
                ) {
                    Box(Modifier.size(14.dp).background(c.onBackground, RoundedCornerShape(4.dp)))
                }
            }
        }

        Stepper("字号", "${settings.fontSizeSp.toInt()} pt",
            { vm.saveSettings(settings.copy(fontSizeSp = (settings.fontSizeSp - 1f).coerceIn(12f, 30f))) },
            { vm.saveSettings(settings.copy(fontSizeSp = (settings.fontSizeSp + 1f).coerceIn(12f, 30f))) })

        Stepper("行距", String.format("%.1f", settings.lineSpacing),
            { vm.saveSettings(settings.copy(lineSpacing = (settings.lineSpacing - 0.1f).coerceIn(1.2f, 2.4f))) },
            { vm.saveSettings(settings.copy(lineSpacing = (settings.lineSpacing + 0.1f).coerceIn(1.2f, 2.4f))) })

        Stepper("页边距", "${settings.marginHorizontalDp} dp",
            { vm.saveSettings(settings.copy(marginHorizontalDp = (settings.marginHorizontalDp - 2).coerceIn(8, 48))) },
            { vm.saveSettings(settings.copy(marginHorizontalDp = (settings.marginHorizontalDp + 2).coerceIn(8, 48))) })

        // 字体
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("sans" to "黑体", "serif" to "宋体", "song" to "楷体").forEach { (k, l) ->
                FilterChip(selected = settings.fontFamily == k, onClick = { vm.saveSettings(settings.copy(fontFamily = k)) }, label = { Text(l) })
            }
        }

        // 对齐
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("justify" to "两端对齐", "left" to "左对齐").forEach { (k, l) ->
                FilterChip(selected = settings.textAlign == k, onClick = { vm.saveSettings(settings.copy(textAlign = k)) }, label = { Text(l) })
            }
        }

        // 亮度
        Text("亮度", style = MaterialTheme.typography.bodyMedium)
        Slider(value = settings.brightness, onValueChange = { vm.saveSettings(settings.copy(brightness = it)) }, valueRange = 0.3f..1f)

        // 保持常亮
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("保持屏幕常亮", style = MaterialTheme.typography.bodyMedium)
            Switch(checked = settings.keepScreenOn, onCheckedChange = { vm.saveSettings(settings.copy(keepScreenOn = it)) })
        }

        TextButton(onClick = onClose, modifier = Modifier.align(Alignment.End)) { Text("完成") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TocSheet(
    parsed: com.zeoffice.einkreader.domain.model.ParsedBook?,
    current: Int,
    onPick: (Int) -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text("目录", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
            items(parsed?.chapters ?: emptyList()) { ch ->
                val idx = ch.orderIndex
                Text(
                    "${idx + 1}. ${ch.title.ifBlank { "（无标题）" }}",
                    modifier = Modifier.fillMaxWidth().clickable { onPick(idx) }.padding(12.dp),
                    color = if (idx == current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BookmarksSheet(
    bookmarks: List<Bookmark>,
    bookId: Int,
    vm: MainViewModel,
    onJump: (Bookmark) -> Unit,
    onAdd: () -> Unit,
    onRemove: (Int) -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("书签", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = onAdd) { Text("添加当前页") }
        }
        Spacer(Modifier.height(8.dp))
        if (bookmarks.isEmpty()) {
            Text("暂无书签", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                items(bookmarks, key = { it.id }) { bm ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onJump(bm) }.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(bm.quote.ifBlank { "（空白书签）" }, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                            Text("第 ${bm.chapterIndex + 1} 章", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { onRemove(bm.id) }) { Icon(Icons.Filled.Delete, "删除") }
                    }
                }
            }
        }
    }
}

@Composable
private fun Stepper(label: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onMinus) { Text("－", style = MaterialTheme.typography.titleMedium) }
            Text(value, Modifier.width(64.dp), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
            IconButton(onClick = onPlus) { Text("＋", style = MaterialTheme.typography.titleMedium) }
        }
    }
}
