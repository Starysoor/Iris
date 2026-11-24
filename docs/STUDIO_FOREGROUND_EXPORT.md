# Iris Studio 前台 MP4 导出（#109 B1）

## 行为
- 用户在项目时间线选择「导出」，由应用级 `ProjectExportController` 申请 Android `dataSync` 前台服务，并在进入服务之前持久化运行中的项目 ID；保留原有时长/文件存在/修剪校验、Media3 Transformer 与 History 入库行为。
- `ProjectExportService` 在 `onStartCommand` 立即调用 `startForeground`，在通知栏显示百分比与取消入口。离开 Studio 页面不影响前台服务内的导出协程。原素材只读，输出临时文件通过同目录 rename 原子生成为最终 MP4。
- 用户取消：取消 Media3 Transformer 和服务协程，删除临时输出。完成后结果仍可在 History 查看和分享。
- 进程崩溃或系统强杀后：`ProjectExportJournal` 在应用重新启动时令 UI 显示 `Failed`，用户必须手动点击「重试」。**不会自动重做渲染，也不会自动触发任何云端付费作业**。
- UI 显示的是应用级 StateFlow，切屏后可重新打开进度。

## 验证范围
- JVM 单测验证 crash-marker 写入/清理/损坏 JSON。
- Android CI 验证 JVM、构建、lint 与 API29/34 手机和平板 smoke。
- 仍需要在真实 Android 设备上分别验证前台通知权限、后台切换、通知取消、进程强杀恢复、长视频编码器与混音的端到端完整样例。仅 CI 绿色不意味着这些测试完成。

此变更不代替 Issue #80 120 秒、12 镜头、多模型、多 Take 的真实成片证据，也不自动发布 v2.4.0。
