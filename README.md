# EasyReader — 墨屏电子书阅读器（Android）

EasyReader（中文名「简单阅读」）是一款专为墨水屏电纸书设计的 Android 阅读应用，主打**纯黑白高对比渲染**、**多格式本地书库**与**无广告单机阅读**。

## 核心特性

| 特性 | 说明 |
|------|------|
| **墨屏渲染** | 纯黑白 / 高对比度 / 整页刷新去残影 / 无渐变 / 无阴影 / 无动画 |
| **多格式支持** | MOBI / AZW / AZW3 / EPUB / TXT / PDF（PDF 支持双指捏合缩放） |
| **本地书库** | Room 数据库 + 封面展示 + 书名/作者搜索 |
| **导入方式** | 百度网盘（扫码授权）/ 从 WebDAV 导入 / 批量导入文件夹 |
| **WebDAV** | 坚果云 / Nextcloud / 群晖 / 自建 WebDAV，含「测试连接」 |
| **百度网盘** | 扫码授权（Device Code Flow）登录，下载带进度提示，中途退出二次确认 |
| **GitHub 在线更新** | 检查 Release → 下载 APK → 同签名校验 → 引导安装 |
| **纯净体验** | 无社区 / 无广告 / 无支付 / 无 AI 对话，纯本地单机 |

## 导入方式说明

- **百度网盘**：点击后展示二维码，用百度网盘 App 扫码授权；授权后浏览 `/apps/<应用名>/` 目录并下载（中途退出会二次确认取消导入）。
- **从 WebDAV 导入**：填写服务器地址、账号、密码，支持「测试连接」后浏览并下载。
- **批量导入**：选择文件夹，递归扫描常见电子书格式导入书库。
- 底层文件访问统一使用 Android SAF（存储访问框架），无需申请宽泛的存储权限。

> 百度网盘仅允许访问 `/apps/<你的应用名>/` 目录，请先将文件移入该目录再导入。

## 技术架构

```
presentation/   Jetpack Compose UI (Material3) + ViewModel + Navigation
parser/         统一 BookParser 接口
  txt/          TxtParser (UTF-8/16/GBK 自动检测 + 章节识别)
  epub/         EpubParser (ZIP 解包 + spine 解析 + 封面)
  pdf/          PdfParser (系统 PdfRenderer 渲染 + 手势缩放)
  mobi/         MobiParser (PDB 容器 + PalmDOC/MOBI 头 + EXTH)
  azw/          AzwParser (AZW3 走 PDB；纯 AZW 走 EPUB)
data/           Room 数据库 (Book / ReadingSettings) + Repository
domain/         领域模型 (Book / ParsedBook / BookFormat / ReadingSettings)
webdav/         WebDavClient (PROPFIND + GET + Basic Auth)
cloud/          百度网盘 OAuth (Device Code Flow) 与文件客户端
updater/        GitHubUpdater (Releases API + 签名校验 + 安装)
```

- 语言：Kotlin
- UI：Jetpack Compose + Material3
- 最小 / 目标 / 编译 SDK：24 / 35 / 35
- 主要依赖：AndroidX（Room / Lifecycle / Navigation / DocumentFile）、Retrofit + OkHttp、Gson、kotlinx-coroutines

## 构建

```bash
./gradlew assembleRelease
# 产物: app/build/outputs/apk/release/app-release.apk
```

Release 签名从仓库根目录 `keystore.properties` 读取（该文件已加入 `.gitignore`，不会入库）。
若文件缺失，release 构建将退化为未签名 APK。

### 持续集成（GitHub Actions）

仓库根目录 `.github/workflows/ci.yml` 会在推送 `v*` tag 时自动构建并发布为 Release。
如需产物为**签名 APK**，请在仓库 **Settings → Secrets** 中配置以下四项：

| Secret | 说明 |
|--------|------|
| `KEYSTORE_BASE64` | 将签名 keystore 经 Base64 编码后的文本 |
| `KEYSTORE_PASSWORD` | keystore 密码 |
| `KEY_ALIAS` | 密钥别名 |
| `KEY_PASSWORD` | 密钥密码 |

未配置时 CI 仍会构建，但产物为未签名 APK（无法用于应用内签名校验更新）。

### 百度网盘凭据

百度网盘开放平台凭据（AppKey / SecretKey）内置在
`app/src/main/java/com/zeoffice/einkreader/cloud/BaiduConfig.kt`。
这些属于 OAuth 公共客户端凭据，需随 App 分发才能使用扫码登录；若担心公开暴露，可在百度网盘开放平台重置，或替换为你自己的应用凭据。

## 墨屏调优说明

- 翻页 / 大跳跃时整页刷新（`needFullRefresh`）以清除残影
- 文字渲染关闭抗锯齿（`isAntiAlias = false`），墨屏更锐利
- 高亮行使用纯黑底白字（无半透明，墨屏无灰阶）
- 底部进度条 1–3px 纯黑线，无颜色渐变
- 默认字号 18sp、行距 1.8、边距 36/44dp，适配 4.7"–7.8" 电纸书

## 隐私说明

- 应用不收集任何个人信息，所有书籍与设置仅保存在本机数据库与存储中。
- 仅在使用「在线更新 / WebDAV / 百度网盘」功能时产生必要的网络连接。
- 百度网盘令牌仅缓存在本机，不会上传到任何第三方服务器。

## 许可证

本项目以 [Apache License 2.0](LICENSE) 开源。
