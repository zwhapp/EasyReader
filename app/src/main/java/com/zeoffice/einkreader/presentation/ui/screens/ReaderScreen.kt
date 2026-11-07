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
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.zeoffice.einkreader.reader.TextHit
import com.zeoffice.einkreader.utils.ReaderKeyDispatcher
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

    // 阅读现场用 rememberSaveable：旋转 / 分屏 / 系统字号变化后不丢章节与页码
    val chapterIdx = rememberSaveable { mutableStateOf(book?.chapterIndex ?: 0) }
    val pageIdx = rememberSaveable { mutableStateOf(0) }
    /** 当前页首字符偏移：比页码更可靠，重排（屏幕/字号变化）后用它精确还原位置 */
    val anchorOffset = rememberSaveable { mutableStateOf(-1) }
    var restored by rememberSaveable { mutableStateOf(false) }
    var lastChapterIdx by rememberSaveable { mutableStateOf(chapterIdx.value) }
    val menuOpen = rememberSaveable { mutableStateOf(false) }
    val readerViewRef = remember { mutableStateOf<ReaderView?>(null) }
    val pdfViewRef = remember { mutableStateOf<PdfReaderView?>(null) }
    val sheet = rememberSaveable { mutableStateOf<String?>(null) } // settings / toc / bookmarks
    // Triple(命中的词, 释义, 点选字)
    val dict = remember { mutableStateOf<Triple<String, String, Char>?>(null) }
    var showExit by rememberSaveable { mutableStateOf(false) }
    var pdfError by rememberSaveable { mutableStateOf<String?>(null) }
    // 显式跳转（翻页/目录/书签）已自行 applyReader，跳过随后由 chapterIdx 触发的重复分页
    var suppressNextApply by remember { mutableStateOf(false) }
    // 亮度拖动时的即时预览（不写 DB、不触发重排），松手后才持久化
    var brightnessPreview by rememberSaveable { mutableStateOf(settings.brightness) }
    LaunchedEffect(settings.brightness) { brightnessPreview = settings.brightness }

    // 只在这些字段变化时才重新分页；亮度/常亮/按键/同步等无关设置不再触发全章重排
    val layoutKey = listOf(
        settings.fontSizeSp, settings.lineSpacing, settings.marginHorizontalDp,
        settings.marginVerticalDp, settings.fontFamily, settings.textAlign,
        settings.theme, settings.einkMode, settings.firstLineIndent, settings.cleanText
    )

    // 进入阅读：打开书籍 + 载入书签 (离开时清掉错误状态)
    LaunchedEffect(bookId) {
        vm.clearOpenError()
        vm.loadBookmarks(bookId)
        book?.let { vm.openBook(it) }
    }
    DisposableEffect(Unit) {
        onDispose { vm.clearOpenError() }
    }

    // 应用阅读器排版：单一入口，一次分页 + 按字符偏移定位（章节/设置/屏幕变化共用）
    fun applyReader(gotoLast: Boolean = false, targetOffset: Int? = null) {
        val rv = readerViewRef.value ?: return
        val p = vm.parsedBook.value ?: return
        if (p.chapters.isEmpty()) return
        lastChapterIdx = chapterIdx.value
        val ch = p.chapters.getOrElse(chapterIdx.value) { p.chapters.first() }
        val sameChapter = rv.currentChapterIndex == chapterIdx.value
        // 同章且未指定目标（如仅改字号）：沿用当前页锚点，避免跳回页首
        val anchor = if (targetOffset == null && !gotoLast && sameChapter) rv.currentCharOffset() else null
        rv.show(
            index = chapterIdx.value,
            text = ch.text,
            s = vm.settings.value,
            bg = theme.background.toArgb(),
            fg = theme.onBackground.toArgb(),
            eink = settings.einkMode,
            targetOffset = targetOffset ?: anchor,
            gotoLast = gotoLast
        )
        pageIdx.value = rv.currentPageIndex
        anchorOffset.value = rv.currentCharOffset()
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
            anchorOffset.value = offset
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
                suppressNextApply = true
                chapterIdx.value += 1
                pageIdx.value = 0
                applyReader()
            }
        } else {
            if (pageIdx.value > 0) {
                pageIdx.value -= 1
                rv.setPage(pageIdx.value)
            } else if (chapterIdx.value > 0) {
                suppressNextApply = true
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
            suppressNextApply = true
            chapterIdx.value = target
            pageIdx.value = 0
            applyReader()
            saveProgress()
        }
    }

    // 物理翻页键：阅读态注册处理器，按设置映射为翻页；菜单/弹窗打开时屏蔽翻页但消费按键
    // 映射（标准 + 自定义，符合"只优化不魔改"）：
    //   标准：VOLUME_UP / PAGE_UP / DPAD_UP / DPAD_LEFT = 上一页；其余 = 下一页
    //   自定义：设置里 customPrevKeys / customNextKeys（适配厂商非常规键值）
    // 仅 eink 模式 + 对应开关开启时拦截（普通手机/其它界面按键照常工作）；
    // 仅 ACTION_DOWN 且 repeatCount==0 时翻页（长按不连翻），DOWN/UP/repeat 全部消费，避免音量面板弹出
    DisposableEffect(Unit) {
        ReaderKeyDispatcher.handler = handler@{ event ->
            val s = vm.settings.value
            if (!s.einkMode) return@handler false
            val dir = ReaderKeyDispatcher.keyDir(event.keyCode)
            if (dir == 0) return@handler false
            val isVolume = ReaderKeyDispatcher.isVolumeKey(event.keyCode)
            // 该按键对应的开关未开启 → 不放行交由系统处理（如音量面板）
            if (isVolume && !s.volumeKeyPageTurn) return@handler false
            if (!isVolume && !s.pageKeyPageTurn) return@handler false
            // 菜单/弹窗打开：消费按键但不翻页，避免穿透到系统
            if (menuOpen.value || sheet.value != null || showExit) return@handler true
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                if (isPdf) turnPdf(dir) else turnPage(dir)
            }
            true
        }
        onDispose { ReaderKeyDispatcher.handler = null }
    }

    // 只依赖影响排版的字段；亮度/常亮/按键/同步开关不再触发全章重排
    LaunchedEffect(parsed, chapterIdx.value, layoutKey) {
        if (isPdf || parsed == null) return@LaunchedEffect
        if (suppressNextApply) {
            suppressNextApply = false
            return@LaunchedEffect
        }
        val chapterChanged = chapterIdx.value != lastChapterIdx
        val target = when {
            !restored -> book?.charOffset ?: 0           // 首次进入：数据库里的上次阅读位置
            chapterChanged -> 0                          // 换章：从章首开始
            anchorOffset.value >= 0 -> anchorOffset.value // 设置/配置变化：按偏移精确还原
            else -> 0
        }
        applyReader(targetOffset = target)
        restored = true
    }

    BackHandler(enabled = true) { showExit = true }

    // 离开 PDF 阅读页时释放 PdfRenderer / ParcelFileDescriptor / 位图，避免 fd 与 native 内存泄漏
    DisposableEffect(Unit) {
        onDispose {
            pdfViewRef.value?.release()
            pdfViewRef.value = null
        }
    }

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
                if (pdfError != null) {
                    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                pdfError ?: "无法打开 PDF",
                                color = MaterialTheme.colorScheme.error,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                            Spacer(Modifier.height(16.dp))
                            Button(onClick = { nav.popBackStack() }) { Text("返回书库") }
                        }
                    }
                } else {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            PdfReaderView(ctx).also { view ->
                                view.onPageTurn = { turnPdf(it) }
                                view.onCenterTap = { menuOpen.value = !menuOpen.value }
                                val b = book
                                // open() 内部捕获异常并返回错误信息，不再让损坏/加密 PDF 冲出 Composition 崩溃
                                pdfError = if (b == null) "书籍不存在"
                                else view.open(File(b.filePath), b.charOffset.coerceAtLeast(0))
                                pdfViewRef.value = view
                            }
                        }
                    )
                }
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
                            // 重排后把真实页码/偏移同步回 Compose，避免屏幕旋转后页码脱节
                            it.onLayoutChanged = { pi, _, off ->
                                pageIdx.value = pi
                                anchorOffset.value = off
                            }
                            it.onWordTap = { word, clickIdx ->
                                // 长按取词: 以点选字为锚枚举候选词，且必须用精确查询。
                                // 旧实现把整段送进 lookupEntry（内部带中文最长前缀匹配），
                                // 结果总是命中片段开头的词，与手指点中的字无关。
                                val picked = word[clickIdx.coerceIn(0, word.lastIndex)]
                                dict.value = TextHit.resolveWord(
                                    word, clickIdx, lookup = { q -> vm.dictRepo.lookupExact(q) }
                                ) ?: Triple(
                                    picked.toString(),
                                    "内置词典暂未收录该字，可在 assets/dict.txt 扩充词库后重新构建。",
                                    picked
                                )
                            }
                            readerViewRef.value = it
                        }
                    }
                )
            }
        }

        // 应用内亮度调暗（覆盖在阅读区之上）
        // 电纸书模式下禁用：半透明黑层会在墨水屏上留下明显灰影，且调暗意义不大（交予系统亮度）
        if (brightnessPreview < 1f && !settings.einkMode) {
            Box(
                Modifier.fillMaxSize()
                    .background(Color.Black.copy(alpha = (1f - brightnessPreview).coerceIn(0f, 0.9f)))
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
    dict.value?.let { (w, d, picked) ->
        AlertDialog(
            onDismissRequest = { dict.value = null },
            title = { Text(w) },
            text = {
                Column {
                    Text(
                        "点选字：$picked",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(d)
                }
            },
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
                "settings" -> ReaderSettingsSheet(
                    vm, settings,
                    onBrightnessPreview = { brightnessPreview = it }
                ) { sheet.value = null }
                "toc" -> TocSheet(parsed, chapterIdx.value) { i ->
                    suppressNextApply = true
                    chapterIdx.value = i
                    pageIdx.value = 0
                    applyReader(targetOffset = 0)
                    saveProgress()
                    sheet.value = null
                }
                "bookmarks" -> BookmarksSheet(
                    bookmarks = bookmarks,
                    bookId = bookId,
                    vm = vm,
                    onJump = { bm ->
                        if (!isPdf) {
                            suppressNextApply = true
                            chapterIdx.value = bm.chapterIndex
                            pageIdx.value = 0
                            applyReader(targetOffset = bm.charOffset)
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
    onBrightnessPreview: (Float) -> Unit,
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

        // 正文智能整理（v0.4.8）
        Text("正文整理与排版", style = MaterialTheme.typography.titleSmall)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("智能整理正文", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "自动删除多余空行/空格，合并 TXT 硬换行为自然段；诗歌、代码类书籍可关闭",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = settings.cleanText,
                onCheckedChange = { vm.saveSettings(settings.copy(cleanText = it)) }
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("段落首行缩进", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "每段首行缩进两个汉字宽，宽屏自动限制行宽并居中",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = settings.firstLineIndent,
                onCheckedChange = { vm.saveSettings(settings.copy(firstLineIndent = it)) }
            )
        }

        // 亮度：拖动只改本地预览（不写 DB、不触发全章重排），松手才持久化
        var brightnessValue by remember(settings.brightness) { mutableStateOf(settings.brightness) }
        Text("亮度", style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = brightnessValue,
            onValueChange = { brightnessValue = it; onBrightnessPreview(it) },
            onValueChangeFinished = { vm.saveSettings(settings.copy(brightness = brightnessValue)) },
            valueRange = 0.3f..1f
        )

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
