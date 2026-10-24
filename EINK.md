# 电纸书（墨水屏）适配说明 — EasyReader

> 目标机型：**Bigme B751C**（7″ Kaleido 3 彩墨屏 / Android 11 / 300PPI）
> 适配原则：**只优化不魔改** —— 不模拟 Partial Refresh（厂商固件层 xRapid 自理）、不破坏普通手机体验。
> 版本：自 v0.4.0 起引入，v0.4.2 重构按键层与显示层。

---

## 一、为什么 B751C 翻页键之前不生效

B751C 这类电纸书的物理按键在系统里派发的键值五花八门，且**音量键若不在 Activity 层拦截就会掉进系统音量面板**：

- `KEYCODE_VOLUME_UP` / `KEYCODE_VOLUME_DOWN`（多数电纸书直接复用音量键做翻页）
- `KEYCODE_PAGE_UP` / `KEYCODE_PAGE_DOWN`（实体翻页键）
- `KEYCODE_DPAD_UP` / `DOWN` / `LEFT` / `RIGHT`（方向键）

初版（v0.4.0）只拦截了 `PAGE_UP/DOWN` 且用 `onKeyDown`，音量键完全没拦，方向键/翻页键在 B751C 上取不到标准值，因此"翻页键不起作用"。v0.4.2 改为在 `Activity.dispatchKeyEvent` 最早阶段统一分发，覆盖上述全部键值。

---

## 二、三层适配架构

### ① 按键层（物理翻页键统一拦截）

| 文件 | 职责 |
|------|------|
| `utils/ReaderKeyDispatcher.kt` | 单例分发器。`handler` 由阅读界面注册；`isInterceptableKey()` 识别音量 / PAGE / DPAD 共 8 个键值。 |
| `MainActivity.dispatchKeyEvent()` | 在 Activity 最早阶段拦截：`handler != null` 且键可被接管时，先问阅读页是否要消费，**消费则直接 `return true` 不再下传系统**（避免音量面板）。其它界面（书库/设置）与系统音量、媒体键完全不受影响。 |

**键值 → 翻页方向（固定映射，与厂商无关）**

| 键值 | 方向 |
|------|------|
| `VOLUME_UP` `PAGE_UP` `DPAD_UP` `DPAD_LEFT` | 上一页 |
| `VOLUME_DOWN` `PAGE_DOWN` `DPAD_DOWN` `DPAD_RIGHT` | 下一页 |

**触发与消费规则**

- 仅 `ACTION_DOWN` 且 `repeatCount == 0` 时翻页 —— **长按不连翻**。
- DOWN / UP / 重复事件一律消费（`return true`），保证长按期间不弹出系统音量面板。
- 菜单 / 弹窗（目录、书签、排版、退出确认）打开时：消费按键但**不翻页**，避免穿透。
- 两个独立开关（持久化，仅电纸书模式可见）：
  - **翻页键 / 方向键翻页**（`pageKeyPageTurn`，默认开）
  - **音量键翻页**（`volumeKeyPageTurn`，默认关 —— 避免误触影响系统音量）
- 阅读界面注册 `DisposableEffect`，离开即注销 `handler`，不影响系统其它界面。

### ② 显示层（双管齐下降残影、提对比）

**应用层（Material3 全局）**

- `theme/EinkTheme.kt` → `einkColorScheme()`：把全部 Material3 颜色角色降为灰阶（纯白底 `0xFFFFFFFF` / 纯黑字 `0xFF000000`），去除彩色与色调阴影，提升对比、降低残影。电纸书模式下应用，普通模式仍是彩色白天主题。
- `MainActivity`：电纸书模式关闭 **窗口切换动画**（`window.setWindowAnimations(0)`）与 **NavHost 界面转场**（enter/exit/pop 全部 `None`）。
- 去除 **滚动过冲光晕**：`CompositionLocalProvider(LocalOverscrollConfiguration provides null)`（eink 模式）。

**内容层（阅读正文）**

- 本项目正文渲染走 **Canvas（`ReaderView`，txt/epub）** 与 **PdfRenderer（`PdfReaderView`，pdf）**，**不含 WebView**，因此"内容层 CSS / WebView OVER_SCROLL"方案不适用。
- 等效优化已覆盖：`ReaderView.configure(..., eink)` 在 eink 模式下关闭文本抗锯齿与抖动（`textPaint.isAntiAlias = false`、`isDither = false`），文字边缘更锐利、残影更少。
- 阅读页亮度遮罩在 eink 模式下禁用（`if (brightness < 1f && !einkMode)`），避免半透明黑层在墨水屏留下灰影。

> 说明：如未来引入 WebView 渲染（如某种富文本格式），可再加 `body.eink` CSS：纯白底纯黑字、衬线 + 字重 500、去除阴影、`filter: contrast(1.12) grayscale(1)`、搜索高亮反色、滚动即时无平滑动画。当前版本无需。

### ③ 识别层（按厂商自动识别并提示）

- `utils/EinkDeviceDetector.kt`：读取 `Build.BRAND / MODEL / DEVICE`，匹配已知墨水屏品牌（`bigme/onyx/boox/kindle/hisense/...`）与机型关键字（如 `b751`）。
- `recommendEink()`：首次启动（无存档）且识别到墨水屏时，默认开启电纸书模式 + 音量键翻页。
- `deviceLabel()`：在「阅读设置 → 电纸书适配」分区展示识别结果提示（如"已识别为 Bigme B751C…已默认开启"）。
- 不申请任何权限、不采集隐私；识别不到也完全不影响功能，用户可随时手动开关。

---

## 三、在现有架构的落点

延续既有「设置模型 → Room → ViewModel → 屏幕」链路，零侵入扩展：

| 层 | 文件 | 改动 |
|----|------|------|
| 数据模型 | `domain/model/ReadingSettings.kt` | 新增 `volumeKeyPageTurn` / `pageKeyPageTurn`（保留历史 `pageKeyAction` 仅用于老数据迁移读取） |
| 持久化 | `data/db/ReadingSettingsEntity.kt` + `Mappers.kt` + `EinkDatabase.kt` | 新增两列；`MIGRATION_5_6`（v5→v6，幂等 ALTER + 按老 `pageKeyAction` 兼容赋值） |
| ViewModel | `presentation/viewmodel/MainViewModel.kt` | 首启按设备识别初始化电纸书开关 |
| 按键 | `utils/ReaderKeyDispatcher.kt`（新增）、`MainActivity.kt` | 全局分发 + Activity 层拦截 |
| 显示 | `theme/EinkTheme.kt`、`MainActivity.kt`、`reader/ReaderView.kt`、`ReaderScreen.kt` | 灰阶主题、关动画/过滚动、关抗锯齿抖动、禁用亮度遮罩 |
| 识别 | `utils/EinkDeviceDetector.kt` | 品牌/机型匹配 + 首启推荐 + 提示文案 |
| 设置 | `presentation/ui/screens/SettingsScreen.kt` | 电纸书分区：总开关 + 两个翻页键开关（仅 eink 模式可见） |

---

## 四、如何扩展到新机型 / 新键值

1. **新机型识别**：在 `EinkDeviceDetector.EINK_BRANDS` 或 `EINK_MODEL_KEYWORDS` 增加品牌/关键字即可（无需改逻辑）。
2. **新物理键值**：在 `ReaderKeyDispatcher.isInterceptableKey()` 的 `when` 中补 `KeyEvent.KEYCODE_*`；若该键应归入"上一页/下一页"，无需改映射（已统一方向）。
3. **键值与标准不符**（个别厂商用自定义键）：在 `isInterceptableKey()` 放行该键值，并在 `ReaderScreen` 的 handler 里按实测值追加映射分支即可，不影响其它设备。

---

## 五、已知边界（"不魔改"声明）

- Partial Refresh 由 **厂商固件层（Bigme xRapid）** 自理，App 不模拟、不干预。
- 音量键翻页默认关闭，避免误触系统音量；需要时在设置开启。
- 翻页键/方向键翻页默认开启（仅 eink 模式），普通手机（eink 关闭）完全走系统默认行为，App 不拦截任何按键。
- 数据库迁移严格幂等，覆盖安装老版本不会丢失书籍/进度/书签。
