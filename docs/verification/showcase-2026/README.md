# Iris Studio · 2026 年 11 月全新真实界面截图

这组截图使用 GitHub Actions 上的 **Android 14 / API 34 Pixel 3a 模拟器**，运行仓库中的实际 Compose Android App 的 Debug APK，再调用 `adb exec-out screencap -p` 获取原始 PNG。

**为什么是演示数据？** 公开展示不能依赖用户的真实 API Key、私人素材或付费模型。Debug 构建通过明确的 `QA_GALLERY` Intent 在应用私有目录创建一个带有「演示 / Demo」名称的离线项目；示例静态图由 Android Canvas 本地绘制，视频/音频来自 FFmpeg 确定性测试素材。所有界面控件、布局、列表、导航、时间线均由真实应用渲染，而非截图合成或生成式 UI 图片。

## 截图（完成采集后提交）

| 文件 | 页面 | 主题 |
|---|---|---|
| `01-projects-light.png` | 项目列表 | 浅色 |
| `02-overview-light.png` | 项目概览 | 浅色 |
| `03-shots-light.png` | 镜头工坊 | 浅色 |
| `04-assets-light.png` | 素材库 | 浅色 |
| `05-timeline-dark.png` | 视频/音频时间线 | 深色 |
| `06-director-dark.png` | AI 导演 | 深色 |
| `07-settings-dark.png` | 设置中心 | 深色 |

各 PNG 保留原始屏幕像素；同名 XML 文件保存 `uiautomator` 窗口树，用于核对是否真的到达对应页面。Crash 日志见 `android-runtime-errors.log`。截图只用于展示开发版能力，不代表 v2.4.0 已正式发布。

## 复现

在能够运行 Android SDK、FFmpeg、GitHub Actions 模拟器的环境中，执行 `bash tools/qa_capture_showcase.sh`。工作流定义在 `.github/workflows/readme-gallery.yml`，只允许独立分支 `docs/iris-showcase-refresh` 自动回写截图；不修改正式版或用户设备数据。

> 不要把生成的演示媒体写成用户真实生成成果，不要用设计稿冒充截图。所有截图均应经过实际内容核查后用于 README。
