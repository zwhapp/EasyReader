package com.zeoffice.einkreader.presentation.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zeoffice.einkreader.AppConfig
import com.zeoffice.einkreader.cloud.AliyunDriveClient
import com.zeoffice.einkreader.cloud.BaiduTokenPoll
import com.zeoffice.einkreader.cloud.CloudProviderFactory
import com.zeoffice.einkreader.data.db.BookEntity
import com.zeoffice.einkreader.data.db.EinkDatabase
import com.zeoffice.einkreader.data.repository.BookRepository
import com.zeoffice.einkreader.data.repository.BookmarkRepository
import com.zeoffice.einkreader.data.repository.CloudRepository
import com.zeoffice.einkreader.data.repository.DictionaryRepository
import com.zeoffice.einkreader.data.repository.SettingsRepository
import com.zeoffice.einkreader.data.toDomain
import com.zeoffice.einkreader.utils.EinkDeviceDetector
import com.zeoffice.einkreader.utils.ReaderKeyDispatcher
import com.zeoffice.einkreader.domain.import.BookImporter
import com.zeoffice.einkreader.domain.import.CoverFactory
import com.zeoffice.einkreader.webdav.WebDavClient
import java.io.File
import com.zeoffice.einkreader.domain.model.BookFormat
import com.zeoffice.einkreader.domain.model.Bookmark
import com.zeoffice.einkreader.domain.model.CloudAccount
import com.zeoffice.einkreader.domain.model.CloudFile
import com.zeoffice.einkreader.domain.model.ParsedBook
import com.zeoffice.einkreader.domain.model.ReadingSettings
import com.zeoffice.einkreader.domain.model.sync.ConflictPolicy
import com.zeoffice.einkreader.domain.model.sync.SyncDirection
import com.zeoffice.einkreader.domain.model.sync.SyncStatus
import com.zeoffice.einkreader.reader.TextCleaner
import com.zeoffice.einkreader.updater.GitHubRelease
import com.zeoffice.einkreader.updater.GitHubUpdater
import com.zeoffice.einkreader.updater.InstallResult
import com.zeoffice.einkreader.webdav.WebDavSyncManager
import com.zeoffice.einkreader.webdav.SyncDeviceId
import com.zeoffice.einkreader.webdav.SyncMerge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.util.Log

sealed class ImportStatus {
    object Idle : ImportStatus()
    data class Importing(val msg: String) : ImportStatus()
    object Done : ImportStatus()
    data class Error(val msg: String) : ImportStatus()
}

/** 自动同步（启动拉取 / 退出推送）轻量提示 — 供书架页横幅与 Toast 使用 */
sealed class AutoSyncNotice {
    abstract val msg: String
    data class Syncing(override val msg: String) : AutoSyncNotice()
    data class Done(override val msg: String) : AutoSyncNotice()
    data class Error(override val msg: String) : AutoSyncNotice()
}

/** 百度网盘扫码授权状态机 */
sealed class BaiduLoginState {
    object Idle : BaiduLoginState()

    /** 已生成二维码, 等待用户扫码确认 */
    data class Qr(
        val qrUrl: String,
        val userCode: String,
        val verificationUrl: String,
        val expiresInSeconds: Long
    ) : BaiduLoginState()

    /** 授权成功 (账户已保存) */
    data class Success(val account: CloudAccount) : BaiduLoginState()

    /** 失败 (含具体原因) */
    data class Failed(val msg: String) : BaiduLoginState()
}

data class UpdateInfo(
    val release: GitHubRelease? = null,
    val apkUrl: String? = null,
    val hasNew: Boolean = false,
    val mirror: Boolean = false,
    val error: String? = null
)

class MainViewModel(private val app: Application) : AndroidViewModel(app) {

    private val db = EinkDatabase.get(app)
    private val bookRepo = BookRepository(db)
    private val settingsRepo = SettingsRepository(db)
    private val bookmarkRepo = BookmarkRepository(db)
    private val cloudRepo = CloudRepository(db)
    val dictRepo = DictionaryRepository(app)

    private val _books = MutableStateFlow<List<BookEntity>>(emptyList())
    val books: StateFlow<List<BookEntity>> = _books.asStateFlow()

    private val _settings = MutableStateFlow(ReadingSettings())
    val settings: StateFlow<ReadingSettings> = _settings.asStateFlow()

    private val _importStatus = MutableStateFlow<ImportStatus>(ImportStatus.Idle)
    val importStatus: StateFlow<ImportStatus> = _importStatus.asStateFlow()

    private val _parsedBook = MutableStateFlow<ParsedBook?>(null)
    val parsedBook: StateFlow<ParsedBook?> = _parsedBook.asStateFlow()

    /** 打开书籍失败的原因 (ReaderScreen 显示错误并返回) */
    private val _openError = MutableStateFlow<String?>(null)
    val openError: StateFlow<String?> = _openError.asStateFlow()

    private val _accounts = MutableStateFlow<List<CloudAccount>>(emptyList())
    val accounts: StateFlow<List<CloudAccount>> = _accounts.asStateFlow()

    private val _baiduLogin = MutableStateFlow<BaiduLoginState>(BaiduLoginState.Idle)
    val baiduLogin: StateFlow<BaiduLoginState> = _baiduLogin.asStateFlow()

    private var baiduLoginJob: kotlinx.coroutines.Job? = null
    private var cloudImportJob: kotlinx.coroutines.Job? = null

    private val _bookmarks = MutableStateFlow<List<Bookmark>>(emptyList())
    val bookmarks: StateFlow<List<Bookmark>> = _bookmarks.asStateFlow()

    private val _updateInfo = MutableStateFlow<UpdateInfo?>(null)
    val updateInfo: StateFlow<UpdateInfo?> = _updateInfo.asStateFlow()

    private val _checkingUpdate = MutableStateFlow(false)
    val checkingUpdate: StateFlow<Boolean> = _checkingUpdate.asStateFlow()

    /** 下载/安装更新的进度与结果提示 (null = 空闲) */
    private val _updateProgress = MutableStateFlow<String?>(null)
    val updateProgress: StateFlow<String?> = _updateProgress.asStateFlow()

    /** 是否正在下载/安装更新 (用于禁用按钮、显示进度条) */
    private val _updateRunning = MutableStateFlow(false)
    val updateRunning: StateFlow<Boolean> = _updateRunning.asStateFlow()
    private var updateJob: Job? = null

    /** WebDAV 同步状态 (进度 / 结果 / 错误) */
    private val _syncStatus = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
    val syncStatus: StateFlow<SyncStatus> = _syncStatus.asStateFlow()
    private var syncJob: Job? = null

    /** 自动同步（启动拉取 / 退出推送）的轻量提示, 供书架页横幅与 Toast 使用 */
    private val _autoSyncNotice = MutableStateFlow<AutoSyncNotice?>(null)
    val autoSyncNotice: StateFlow<AutoSyncNotice?> = _autoSyncNotice.asStateFlow()
    fun clearAutoSyncNotice() { _autoSyncNotice.value = null }

    /** 本次进程生命周期内是否已执行过"启动自动拉取" (保证仅拉取一次, 避免反复联网) */
    private var autoPulledThisSession = false

    /**
     * 全局未捕获协程异常通道。
     * 此前本类大量 vmScope.launch 未加 try-catch, 任一未捕获异常都会直接杀掉进程
     * (表现为"点一下就退出程序")。现统一用带 CoroutineExceptionHandler 的 vmScope 启动协程:
     * 异常只记录日志 + 轻量提示, 不再崩溃。
     */
    private val _globalError = MutableStateFlow<String?>(null)
    val globalError: StateFlow<String?> = _globalError.asStateFlow()
    fun clearGlobalError() { _globalError.value = null }

    private val crashHandler = CoroutineExceptionHandler { _, e ->
        Log.e("EasyReader", "拦截未捕获的协程异常, 避免崩溃: ${e.message}", e)
        _globalError.value = "操作出错：${e.message ?: e.javaClass.simpleName}（已记录）"
    }
    private val vmScope = CoroutineScope(viewModelScope.coroutineContext + crashHandler)

    init {
        vmScope.launch {
            val entity = settingsRepo.getEntity()
            // 首次启动（无存档）时，若识别到墨水屏设备则默认开启电纸书模式与音量键翻页
            val s = entity?.toDomain()
                ?: ReadingSettings().copy(
                    einkMode = EinkDeviceDetector.recommendEink(),
                    volumeKeyPageTurn = EinkDeviceDetector.recommendEink()
                )
            _settings.value = s
            applyKeyConfig(s)
            if (entity == null) settingsRepo.saveSettings(s)
            // 设置加载完成后, 若开启了"启动自动同步", 触发一次自动拉取 (仅当尚未触发, 由 flag 去重)
            if (s.autoSyncOnStartup) tryAutoPull()
        }
        loadBooks()
        loadAccounts()
        ensureCovers()
    }

    fun loadBooks() = vmScope.launch { _books.value = bookRepo.getAllBooks() }

    /** 老书补封面: coverPath 为空的书按格式生成 (EPUB 取内嵌图, PDF 渲染首页, 其余占位封面) */
    private fun ensureCovers() = vmScope.launch(Dispatchers.IO) {
        val list = bookRepo.getAllBooks()
        var changed = false
        for (b in list) {
            if (b.coverPath.isNotBlank() && File(b.coverPath).exists()) continue
            val format = runCatching { BookFormat.valueOf(b.format) }.getOrDefault(BookFormat.TXT)
            val dest = CoverFactory.coverFile(app, b.id)
            val ok = runCatching {
                if (format == BookFormat.PDF && File(b.filePath).exists()) {
                    CoverFactory.fromPdf(File(b.filePath), dest) ||
                            CoverFactory.placeholder(app, b.id, b.title, b.author, format)
                } else {
                    val parsed = runCatching { BookImporter.parse(b.filePath, format) }.getOrNull()
                    (parsed?.coverData != null && CoverFactory.fromBytes(app, b.id, parsed.coverData)) ||
                            CoverFactory.placeholder(app, b.id, b.title, b.author, format)
                }
            }.getOrDefault(false)
            if (ok) {
                bookRepo.update(b.copy(coverPath = dest.absolutePath))
                changed = true
            }
        }
        if (changed) loadBooks()
    }
    fun loadAccounts() = vmScope.launch { _accounts.value = cloudRepo.getAll() }

    fun saveSettings(s: ReadingSettings) = vmScope.launch {
        // 「正文智能整理」开关会改变章节文本（清洗后长度不同），必须让当前书重新解析，
        // 否则阅读页仍拿旧的缓存文本，进度/书签偏移也会对不上。
        val textPolicyChanged = _settings.value.cleanText != s.cleanText
        _settings.value = s
        applyKeyConfig(s)
        settingsRepo.saveSettings(s)
        if (textPolicyChanged) refreshParsed()
    }

    /** 把自定义兼容键值 / 诊断开关同步到物理翻页键分发器 */
    private fun applyKeyConfig(s: ReadingSettings) {
        ReaderKeyDispatcher.debugRaw = s.keyDebug
        ReaderKeyDispatcher.customPrevKeys = parseKeyList(s.customPrevKeys)
        ReaderKeyDispatcher.customNextKeys = parseKeyList(s.customNextKeys)
    }

    /** 将逗号分隔的整数串解析为键值集合（忽略非法项） */
    private fun parseKeyList(text: String): Set<Int> =
        text.split(',').mapNotNull { it.trim().toIntOrNull() }.toSet()

    /** 导入: 本机 (SAF ACTION_OPEN_DOCUMENT 单文件) */
    fun importUri(uri: Uri, displayName: String) = vmScope.launch(Dispatchers.IO) {
        try {
            _importStatus.value = ImportStatus.Importing("正在导入 $displayName …")
            importSingle(uri, displayName, source = "local")
            loadBooks()
            _importStatus.value = ImportStatus.Done
        } catch (e: Exception) {
            _importStatus.value = ImportStatus.Error(e.message ?: "导入失败")
        }
    }

    /**
     * 导入: SAF 文件夹批量 (ACTION_OPEN_DOCUMENT_TREE)
     * 用户可选中百度网盘等 DocumentsProvider 的目录, 逐个复制到私有目录后加入书架
     */
    fun importTree(treeUri: Uri) = vmScope.launch(Dispatchers.IO) {
        try {
            _importStatus.value = ImportStatus.Importing("正在扫描文件夹 …")
            val tree = androidx.documentfile.provider.DocumentFile.fromTreeUri(app, treeUri)
            if (tree == null || !tree.canRead()) {
                _importStatus.value = ImportStatus.Error("无法访问所选文件夹，请重新选择")
                return@launch
            }
            val supported = tree.listFiles()
                .filter { !it.isDirectory && BookFormat.fromFilename(it.name ?: "") != null }
                .sortedBy { it.name ?: "" }
            if (supported.isEmpty()) {
                _importStatus.value = ImportStatus.Error("所选文件夹中未找到电子书 (支持 epub/txt/mobi/azw/pdf)")
                return@launch
            }
            var ok = 0
            var fail = 0
            supported.forEachIndexed { i, doc ->
                val name = doc.name ?: "book_$i"
                _importStatus.value = ImportStatus.Importing("正在导入 (${i + 1}/${supported.size}) $name …")
                try {
                    importSingle(doc.uri, name, source = "local")
                    ok++
                } catch (_: Exception) {
                    fail++
                }
            }
            loadBooks()
            _importStatus.value = if (fail == 0) ImportStatus.Done
            else ImportStatus.Error("批量导入完成：成功 $ok 本，失败 $fail 本")
        } catch (e: Exception) {
            _importStatus.value = ImportStatus.Error(e.message ?: "批量导入失败")
        }
    }

    /** 单本导入: openInputStream → 复制到私有目录 → 解析元数据 → 入库 */
    private suspend fun importSingle(uri: Uri, displayName: String, source: String) {
        val format = BookFormat.fromFilename(displayName) ?: BookFormat.TXT
        val file = BookImporter.materializeUri(app, uri, displayName)
        val parsed = BookImporter.parse(file.absolutePath, format)
        // 解析失败的书不再静默入库（旧版会生成一本打不开的占位书）
        if (format != BookFormat.PDF && !hasReadableContent(parsed)) {
            runCatching { file.delete() }
            throw Exception("无法解析此书（文件可能损坏、编码不支持或受 DRM 保护）")
        }
        val title = parsed?.title?.takeIf { it.isNotBlank() }
            ?: displayName.substringBeforeLast('.')
        val entity = BookEntity(
            title = title,
            author = parsed?.author ?: "",
            format = format.name,
            filePath = file.absolutePath,
            fileSize = file.length(),
            importedAt = System.currentTimeMillis(),
            source = source,
            chapterCount = parsed?.chapters?.size ?: 0
        )
        val id = bookRepo.insert(entity).toInt()
        generateCover(id, file.absolutePath, format, title, parsed?.coverData)
    }

    /** 生成并保存书籍封面: EPUB 内嵌图 / PDF 首页 > 样式化占位图 */
    private suspend fun generateCover(
        bookId: Int,
        filePath: String,
        format: BookFormat,
        title: String,
        coverData: ByteArray?
    ) {
        val dest = CoverFactory.coverFile(app, bookId)
        val ok = runCatching {
            when {
                coverData != null -> CoverFactory.fromBytes(app, bookId, coverData)
                format == BookFormat.PDF && File(filePath).exists() ->
                    CoverFactory.fromPdf(File(filePath), dest)
                else -> false
            } || CoverFactory.placeholder(app, bookId, title, "", format)
        }.getOrDefault(false)
        if (ok) {
            bookRepo.getBook(bookId)?.let { bookRepo.update(it.copy(coverPath = dest.absolutePath)) }
        }
    }

    /** 导入: 云盘 / WebDAV */
    fun importCloud(account: CloudAccount, file: CloudFile) {
        cloudImportJob?.cancel()
        cloudImportJob = vmScope.launch(Dispatchers.IO) {
            var dest: File? = null
            try {
                _importStatus.value = ImportStatus.Importing("正在下载 ${file.name} …")
                val provider = CloudProviderFactory.create(account)
                val format = BookFormat.fromFilename(file.name) ?: BookFormat.TXT
                dest = BookImporter.uniqueName(app, file.name)
                var lastPercent = -1
                val ok = provider.download(file, dest) { downloaded, total ->
                    // 进度回调 (IO 线程): 百分比变化才刷新, 避免刷屏
                    val msg = if (total > 0) {
                        val pct = (downloaded * 100 / total).toInt().coerceIn(0, 100)
                        if (pct == lastPercent) return@download
                        lastPercent = pct
                        "正在下载 ${file.name} ${pct}%"
                    } else {
                        val mb = downloaded / 1024.0 / 1024.0
                        "正在下载 ${file.name} (已下载 %.1f MB)".format(mb)
                    }
                    _importStatus.value = ImportStatus.Importing(msg)
                }
                if (provider is AliyunDriveClient) {
                    cloudRepo.save(provider.updatedAccount())
                    loadAccounts()
                }
                if (!ok) {
                    _importStatus.value = ImportStatus.Error("下载失败")
                    return@launch
                }
                val parsed = BookImporter.parse(dest.absolutePath, format)
                if (format != BookFormat.PDF && !hasReadableContent(parsed)) {
                    runCatching { dest.delete() }
                    throw Exception("无法解析此书（文件可能损坏、编码不支持或受 DRM 保护）")
                }
                val title = parsed?.title?.takeIf { it.isNotBlank() }
                    ?: file.name.substringBeforeLast('.')
                val entity = BookEntity(
                    title = title,
                    author = parsed?.author ?: "",
                    format = format.name,
                    filePath = dest.absolutePath,
                    fileSize = dest.length(),
                    importedAt = System.currentTimeMillis(),
                    source = account.provider,
                    remotePath = file.path,
                    chapterCount = parsed?.chapters?.size ?: 0
                )
                val newId = bookRepo.insert(entity).toInt()
                generateCover(newId, dest.absolutePath, format, title, parsed?.coverData)
                loadBooks()
                _importStatus.value = ImportStatus.Done
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 用户主动取消导入: 清理半成品文件并回Idle, 不报错
                runCatching { dest?.delete() }
                _importStatus.value = ImportStatus.Idle
                throw e
            } catch (e: Exception) {
                _importStatus.value = ImportStatus.Error(e.message ?: "导入失败")
            }
        }
    }

    /** 取消当前云盘/WebDAV 导入 (退出确认时调用) */
    fun cancelCloudImport() {
        cloudImportJob?.cancel()
        cloudImportJob = null
    }

    /** 已成功解析的书路径：配置变更（旋转/分屏）时 ViewModel 仍存活，避免整本书重复解析 */
    private var openedBookPath: String? = null
    private var lastOpenedBook: BookEntity? = null

    private fun hasReadableContent(p: ParsedBook?): Boolean =
        p != null && p.chapters.any { it.text.isNotBlank() }

    /** 对所有章节做正文整理；过滤清洗后变空的章节并重新连续编号 */
    private fun cleanParsed(p: ParsedBook): ParsedBook {
        val chapters = p.chapters
            .map { it.copy(text = TextCleaner.clean(it.text)) }
            .filter { it.text.isNotBlank() }
            .mapIndexed { i, ch -> ch.copy(orderIndex = i) }
        return p.copy(chapters = chapters.ifEmpty { p.chapters })
    }

    /** 打开书籍 (PDF 返回 null, 由阅读器用 PdfRenderer) */
    fun openBook(book: BookEntity) = vmScope.launch(Dispatchers.IO) {
        _openError.value = null
        try {
            val format = BookFormat.valueOf(book.format)
            lastOpenedBook = book
            if (format == BookFormat.PDF) {
                _parsedBook.value = null
                openedBookPath = null
                return@launch
            }
            // 配置变更时 ViewModel 不重建，parsedBook 已缓存；跳过重复解析避免竞态
            if (openedBookPath == book.filePath && _parsedBook.value != null) return@launch
            val parsed = BookImporter.parse(book.filePath, format)
            if (!hasReadableContent(parsed)) {
                _parsedBook.value = null
                openedBookPath = null
                _openError.value = "无法解析此书 (${format.label})：文件可能损坏或受 DRM 保护"
            } else {
                val result = if (_settings.value.cleanText) cleanParsed(parsed!!) else parsed
                _parsedBook.value = result
                openedBookPath = book.filePath
            }
        } catch (e: Exception) {
            _parsedBook.value = null
            openedBookPath = null
            _openError.value = "无法打开此书：${e.message ?: "解析失败"}"
        }
    }

    /** 正文整理开关变化后重新解析当前书 */
    fun refreshParsed() {
        openedBookPath = null
        lastOpenedBook?.let { openBook(it) }
    }

    fun clearOpenError() {
        _openError.value = null
    }

    fun clearParsed() {
        _parsedBook.value = null
        openedBookPath = null
        lastOpenedBook = null
    }

    fun clearImportIdle() {
        if (_importStatus.value !is ImportStatus.Idle) _importStatus.value = ImportStatus.Idle
    }

    /** 快速翻页时串行化写进度：新值覆盖旧值，避免多个协程乱序写导致进度回退 */
    private var savePositionJob: Job? = null

    fun savePosition(
        bookId: Int,
        chapterIndex: Int,
        charOffset: Int,
        progressPercent: Int
    ) {
        savePositionJob?.cancel()
        savePositionJob = vmScope.launch(Dispatchers.IO) {
            val b = bookRepo.getBook(bookId) ?: return@launch
            val now = System.currentTimeMillis()
            val stamp = SyncMerge.next(
                now, b.progressStamp.ifBlank { null }, SyncDeviceId.get(app)
            )
            val updated = b.copy(
                chapterIndex = chapterIndex,
                charOffset = charOffset,
                progressPercent = progressPercent,
                progressUpdatedAt = now,
                progressStamp = stamp
            )
            bookRepo.update(updated)
            // 只更新内存中的这一条，不再 loadBooks() 全表刷新（会触发阅读页重组/卡顿）
            _books.value = _books.value.map { if (it.id == bookId) updated else it }
        }
    }

    fun deleteBook(book: BookEntity) = vmScope.launch(Dispatchers.IO) {
        runCatching { File(book.filePath).delete() }
        bookRepo.delete(book)
        loadBooks()
    }

    /** 书签 */
    fun loadBookmarks(bookId: Int) = vmScope.launch(Dispatchers.IO) {
        _bookmarks.value = bookmarkRepo.getByBook(bookId)
    }

    fun addBookmark(b: Bookmark) = vmScope.launch(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val uid = b.uid.ifBlank { SyncMerge.bookmarkId(b.chapterIndex, b.charOffset, b.quote) }
        // 若同 uid 已有记录（含墓碑），沿用它的时钟继续递增，保证「复活」一定晚于「删除」
        val existing = bookmarkRepo.getAllByBook(b.bookId).firstOrNull {
            it.uid.ifBlank { SyncMerge.bookmarkId(it.chapterIndex, it.charOffset, it.quote) } == uid
        }
        val stamp = SyncMerge.next(
            now, existing?.stamp?.ifBlank { null }, SyncDeviceId.get(app)
        )
        bookmarkRepo.addOrRevive(
            b.copy(
                createdAt = if (b.createdAt > 0) b.createdAt else now,
                updatedAt = now,
                uid = uid,
                stamp = stamp,
                deleted = false
            )
        )
        loadBookmarks(b.bookId)
    }

    fun removeBookmark(id: Int, bookId: Int) = vmScope.launch(Dispatchers.IO) {
        // 软删除写墓碑：物理删除会让其他设备把这条书签重新同步回来
        bookmarkRepo.remove(id, System.currentTimeMillis(), SyncDeviceId.get(app))
        loadBookmarks(bookId)
    }

    /**
     * 百度网盘扫码授权登录 — 生成二维码并轮询, 成功后自动保存账户。
     * AppKey/SecretKey 内置于应用 (BaiduConfig), 用户无需填写。
     */
    fun startBaiduQrLogin() {
        baiduLoginJob?.cancel()
        _baiduLogin.value = BaiduLoginState.Idle
        baiduLoginJob = vmScope.launch(Dispatchers.IO) {
            try {
                val oauth = com.zeoffice.einkreader.cloud.BaiduOAuth()
                val dc = oauth.requestDeviceCode()
                _baiduLogin.value = BaiduLoginState.Qr(
                    qrUrl = dc.qrCodeUrl,
                    userCode = dc.userCode,
                    verificationUrl = dc.verificationUrl,
                    expiresInSeconds = dc.expiresInSeconds
                )
                val deadline = System.currentTimeMillis() + dc.expiresInSeconds * 1000
                val interval = (dc.intervalSec.coerceIn(2, 15)) * 1000L
                while (System.currentTimeMillis() < deadline) {
                    kotlinx.coroutines.delay(interval)
                    when (val r = oauth.pollToken(dc.deviceCode)) {
                        is BaiduTokenPoll.Pending -> { /* 继续轮询 */ }
                        is BaiduTokenPoll.Done -> {
                            val acc = CloudAccount(
                                provider = "baidu",
                                name = "百度网盘",
                                accessToken = r.accessToken,
                                refreshToken = r.refreshToken,
                                expiresAt = System.currentTimeMillis() + r.expiresInSeconds * 1000
                            )
                            val id = cloudRepo.save(acc)
                            loadAccounts()
                            _baiduLogin.value = BaiduLoginState.Success(acc.copy(id = id.toInt()))
                            return@launch
                        }
                        is BaiduTokenPoll.Expired -> {
                            _baiduLogin.value = BaiduLoginState.Failed(r.msg)
                            return@launch
                        }
                        is BaiduTokenPoll.Error -> {
                            _baiduLogin.value = BaiduLoginState.Failed(r.msg)
                            return@launch
                        }
                    }
                }
                _baiduLogin.value = BaiduLoginState.Failed("二维码已过期，请刷新后重新扫码")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _baiduLogin.value = BaiduLoginState.Failed(e.message ?: "扫码登录失败")
            }
        }
    }

    fun resetBaiduLogin() {
        baiduLoginJob?.cancel()
        _baiduLogin.value = BaiduLoginState.Idle
    }

    /** 云盘账户 — 返回该行 id (新建时为插入后的 id) */
    suspend fun saveAccount(a: CloudAccount): Long {
        val id = cloudRepo.save(a)
        loadAccounts()
        return id
    }

    fun deleteAccount(id: Int) = vmScope.launch(Dispatchers.IO) {
        cloudRepo.delete(id)
        loadAccounts()
    }

    /**
     * WebDAV 测试连接 — 验证地址 / 账号 / 密码, 返回结果描述 (成功或具体失败原因)
     */
    suspend fun testWebDavConnection(host: String, username: String, password: String): String =
        withContext(Dispatchers.IO) {
            runCatching { WebDavClient(host, username, password).testConnection() }
                .getOrDefault("测试异常，请重试")
        }

    /**
     * 手动触发同步（WebDAV 同步页调用）— 书籍文件 + 阅读进度 + 书签笔记。
     * @param syncFiles true=同步书籍文件与进度; false=仅同步阅读进度
     */
    fun syncLibrary(
        account: CloudAccount,
        direction: SyncDirection,
        policy: ConflictPolicy,
        syncFiles: Boolean,
        syncBookmarks: Boolean = true
    ) {
        runSync(account, direction, policy, syncFiles, syncBookmarks, isAuto = false)
    }

    /**
     * 执行一次同步（手动与自动共用）。
     * 自动同步采用双向合并（一次同步同时完成拉取与推送, 仅传变化, 冲突以修改时间为准）,
     * 网络瞬时异常由 [WebDavSyncManager] 内部重试 3 次指数退避; 最终失败写入 [_autoSyncNotice] 供提示。
     * @param isAuto true=自动同步（启动拉取/退出推送）: 进度与结果额外写入 [_autoSyncNotice], 供书架横幅/Toast 轻提示
     */
    private fun runSync(
        account: CloudAccount,
        direction: SyncDirection,
        policy: ConflictPolicy,
        syncFiles: Boolean,
        syncBookmarks: Boolean,
        isAuto: Boolean
    ) {
        if (syncJob?.isActive == true) return
        syncJob = vmScope.launch(Dispatchers.IO) {
            _syncStatus.value = SyncStatus.Syncing("正在连接服务器…", 0, 0)
            if (isAuto) _autoSyncNotice.value = AutoSyncNotice.Syncing("WebDAV 自动同步中…")
            try {
                val manager = WebDavSyncManager(app, bookRepo, bookmarkRepo, db.syncStateDao())
                val result = manager.sync(account, direction, policy, syncFiles, syncBookmarks) { msg, done, total ->
                    _syncStatus.value = SyncStatus.Syncing(msg, done, total)
                }
                loadBooks()
                _syncStatus.value = SyncStatus.Done(result)
                if (isAuto) {
                    _autoSyncNotice.value = if (result.success)
                        AutoSyncNotice.Done("自动同步完成：${result.message}")
                    else
                        AutoSyncNotice.Error("自动同步完成（部分失败）：${result.message}")
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                _syncStatus.value = SyncStatus.Idle
                if (isAuto) _autoSyncNotice.value = null
                throw e
            } catch (e: Throwable) {
                Log.e("EasyReader", "同步失败", e)
                val msg = e.message ?: e.javaClass.simpleName
                _syncStatus.value = SyncStatus.Error(msg)
                if (isAuto) _autoSyncNotice.value = AutoSyncNotice.Error("自动同步失败：$msg")
            }
        }
    }

    /**
     * 启动进入后自动拉取：采用双向合并（一次同步同时完成拉取与推送，仅传变化）。
     * 仅当开启"启动自动同步"且本次进程尚未拉取过时执行；无 WebDAV 账户则静默跳过。
     */
    fun tryAutoPull() {
        if (autoPulledThisSession) return
        val s = _settings.value
        if (!s.autoSyncOnStartup) return
        autoPulledThisSession = true
        launchAutoSync()
    }

    /**
     * 退出程序前自动推送：在 ProcessLifecycle onStop 钩子中调用。
     * 复用双向合并，仅推送本地变化；无 WebDAV 账户或开关关闭时静默跳过。
     */
    fun tryAutoPush() {
        val s = _settings.value
        if (!s.autoSyncOnExit) return
        launchAutoSync()
    }

    /** 读取首个 WebDAV 账户，并以设置中配置的策略/范围发起一次自动双向同步 */
    private fun launchAutoSync() {
        if (syncJob?.isActive == true) return
        vmScope.launch(Dispatchers.IO) {
            val account = runCatching { cloudRepo.getAll() }.getOrNull()
                ?.firstOrNull { it.provider == "webdav" } ?: return@launch
            val s = _settings.value
            val policy = runCatching { ConflictPolicy.valueOf(s.syncPolicy) }
                .getOrDefault(ConflictPolicy.NEWER_WINS)
            runSync(account, SyncDirection.TWO_WAY, policy, s.syncFiles, s.syncBookmarks, isAuto = true)
        }
    }

    /** 取消同步 / 清除同步状态 */
    fun clearSyncStatus() {
        syncJob?.cancel()
        syncJob = null
        _syncStatus.value = SyncStatus.Idle
    }

    /** 更新检查 — 无论成功失败都给出明确结果 (有新版 / 已最新 / 失败原因) */
    fun checkUpdate(useMirror: Boolean) = vmScope.launch(Dispatchers.IO) {
        _checkingUpdate.value = true
        try {
            val updater = GitHubUpdater(app)
            val release = try {
                updater.checkLatest(useMirror)
            } catch (e: Exception) {
                _updateInfo.value = UpdateInfo(error = e.message ?: "检查更新失败", mirror = useMirror)
                return@launch
            }
            // 优先使用 checkLatest 已解析的 APK 地址 (同一次成功请求),
            // 仅在为空时 fallback 二次查询, 避免镜像下二次请求失败导致「未找到APK下载地址」
            val apk = release.apkUrl ?: try {
                updater.findApkAsset(release, useMirror)
            } catch (_: Exception) {
                null
            }
            val hasNew = compareVersion(release.tag, AppConfig.VERSION_NAME) > 0
            _updateInfo.value = UpdateInfo(release, apk, hasNew, useMirror)
        } finally {
            _checkingUpdate.value = false
        }
    }

    /**
     * 下载并安装更新。
     *
     * 历史缺陷 (点更新即崩溃/无反应):
     *  1) 旧代码用 DownloadManager 异步下载, enqueue 后立刻校验签名 —— 文件尚未落盘,
     *     校验必然失败, 安装永远不触发;
     *  2) installApk 从 Application context 启动 Activity 却漏了 FLAG_ACTIVITY_NEW_TASK,
     *     直接抛 AndroidRuntimeException 导致应用退出。
     *
     * 现在: 全程 IO 线程, OkHttp 下载完成后再校验, 安装结果以文案反馈而非崩溃。
     */
    fun downloadAndInstallUpdate(apkUrl: String) {
        updateJob?.cancel()
        updateJob = vmScope.launch(Dispatchers.IO) {
            _updateRunning.value = true
            _updateProgress.value = "正在下载…"
            try {
                val updater = GitHubUpdater(app)
                val file = updater.downloadApk(apkUrl) { downloaded, total ->
                    _updateProgress.value = if (total > 0) {
                        "正在下载… ${(downloaded * 100 / total).toInt()}%"
                    } else {
                        "正在下载… ${downloaded / 1024} KB"
                    }
                }
                _updateProgress.value = "正在校验签名…"
                if (!updater.isSignatureValid(file.absolutePath)) {
                    file.delete()
                    _updateProgress.value = "校验失败：安装包签名与当前应用不一致，已取消安装"
                    return@launch
                }
                _updateProgress.value = when (val r = updater.installApk(file)) {
                    is InstallResult.Started -> "已唤起安装界面，请在系统界面确认"
                    is InstallResult.NeedPermission ->
                        "请先在系统设置中允许本应用「安装未知应用」，然后重新点击更新"
                    is InstallResult.Failed -> "安装失败：${r.msg}"
                }
            } catch (e: Exception) {
                _updateProgress.value = "更新失败：${e.message ?: "未知错误"}"
            } finally {
                _updateRunning.value = false
            }
        }
    }

    private fun compareVersion(tag: String, current: String): Int {
        val a = tag.trim().trimStart('v', 'V').split('.').map { it.toIntOrNull() ?: 0 }
        val b = current.trim().trimStart('v', 'V').split('.').map { it.toIntOrNull() ?: 0 }
        val n = maxOf(a.size, b.size)
        for (i in 0 until n) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x - y
        }
        return 0
    }
}
