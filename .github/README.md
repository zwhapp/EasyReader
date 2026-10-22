# GitHub 自动发布工作流说明
# 构建: 每次 push 到 main 自动构建 release APK
# 发布: 打 tag (v0.1.0 / v0.2.0) 自动触发 Release + 上传 APK
# App 内 GitHubUpdater 拉取 /releases/latest 检查更新
