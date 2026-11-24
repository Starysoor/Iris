# Iris Studio 前台 MP4 导出（#109 B1）

## 行为
- 用户在项目时间线选择「导出」，应用先保存项目 ID 和本次导出的 ID，再启动 Android `dataSync` 前台服务。服务检查源文件和裁剪范围，使用 Media3 Transformer 编码并将通过校验的 MP4 写入结果库。
- `ProjectExportService` 在启动时进入前台，通知显示百分比、取消与返回原时间线的入口。导出使用发起时的时间线，临时输出通过同目录原子重命名生成为最终 MP4。
- 用户取消：取消 Media3 Transformer 和服务协程，删除临时输出。完成后结果仍可在 History 查看和分享。
- 进程异常结束后，应用重启时用导出 ID 查找已经写入结果库的成片。找到成片则恢复成功状态，其他中断的导出会显示「重试」。
- UI 显示的是应用级 StateFlow，切屏后可重新打开进度。

## 验证范围
- JVM 单测覆盖运行记录的写入、清理、损坏 JSON 和旧格式读取。
- 前台服务的原始实现通过 PR #120 的 JVM、构建、Lint 与三组模拟器 CI。 [Test](https://github.com/Starysoor/Iris/actions/runs/43399884367) [模拟器](https://github.com/Starysoor/Iris/actions/runs/43399884315)
- v2.4.0（32）在 Android 14 软件模拟器完成 12 镜头、16 Take 的后台导出。MP4 时长 120 秒，包含 960 帧视频，写入结果库后通过完整解码。逐镜头核对了剪辑顺序、源视频声音和独立混音。 [复测步骤](STUDIO_QA.md)
- 正式发布仍需记录真机通知、取消、强杀恢复、混音和成片验证。

完整真机成片与发布记录归档在 [Issue #80](https://github.com/Starysoor/Iris/issues/80)。
