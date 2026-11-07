# Iris Studio · 项目工作台测试与发布验收（#57 / #80）

> 基线：`main@a8475f9` → PR #90 的 Studio 安全与功能补丁。自动化可以提供证据，但**不能代替真实设备的硬件编码、文件访问及付费模型实测**。正式 Release 需单独完成发布门禁。

## 必跑自动化

1. JVM：`./gradlew :app:testDebugUnitTest --no-daemon`；覆盖 `ProjectStoreTest`、`TakeWorkshopTest`、`ProjectMigrationTest`、`TimelineTest`、`ExportPlanTest`、`BudgetGateTest`、`StudioProjectE2ETest`。
2. APK：`./gradlew :app:assembleDebug :app:assembleRelease --no-daemon`；Release 候选不代表已验证签名。
3. Android Lint：`./gradlew :app:lintDebug --no-daemon`。
4. Emulator QA：`.github/workflows/qa-emulator.yml` 已配置 API 29 手机、API 34 手机、API 34 平板三项矩阵；冷启动、强制停止与重启并保存截图证据。PR CI 必须全部成功。
5. 模拟故障：项目 JSON 损坏/半写入、任务掉线及取消竞态、超预算拒绝提交、删除 Take 后 History 和旧分镜素材保护；使用 Mock Provider，不在自动化中支付真实模型费用。

## 重点手工模拟器检查（Windows / Android Studio）

- **项目基础**：不设置云 Provider，离线新建项目；编辑标题、剧本、目标时长、预算；保存并强制停止应用再进入，确认全部仍在。
- **多模型**：项目默认 A，镜头 1 独立 B，改项目默认 C 后镜头 1 仍 B，继承默认的镜头使用 C。
- **Take 与任务**：同一镜头生成 A/B，切换主版本 A、B、A；快速打开/关闭页面；尝试删除 running Take，必须禁止；任务状态恢复不能把已选 A 再覆盖为 B。
- **删除安全**：原始结果同时在「结果」页和项目 Take 中；删除 Take/项目后原始结果仍可在结果页打开、播放、保存及分享。旧 `storyboards.json` 需要的媒体在新项目清理后仍保留。
- **连续性**：12 个 1080p 镜头及若干 4K 视频，执行抽帧与相邻衔接比较，Memory Profiler 检查峰值；快速切换项目不会持续增长内存。
- **大媒体**：分别试 50 MiB 视频、200 MiB 视频、长 WAV、损坏/未知大小文件，观察进度；点击取消后无残留 `.tmp`，应用不卡死；空间不足必须得到清晰错误。
- **时间线**：30s 视频与 30s 音轨并行显示 30s（而非 60s）；在当前引擎中 MP4 仅按视频轨导出，必须显示音频混音限制。
- **导出**：源片段 A/B/C，删除 B 文件时必须阻止导出；源裁剪出点超媒体实际长度时必须拒绝；正常拼接导出 MP4 后检查可播放、时长、音画同步与结果库记录。

## 完整两分钟项目级 E2E（真机+人工验收门禁）

- [ ] 创建目标时长 120s 的项目，导入 1 位角色三视图和至少 5 张场景图。
- [ ] 规划 12 个镜头，并使用两个不同模型（先 Mock，后在用户明确同意费用的情况下用真实 Provider）。
- [ ] 至少 4 个镜头分别保存多 Take；确认选择结果正确且旧素材未被误删。
- [ ] 选定 Take 排列视频时间线，导入音效/对白，编辑裁剪与音量。
- [ ] 强杀 App、重新进入，核对项目、任务、预算、素材与选定版本均完整。
- [ ] 导出完整可播放 MP4，检查总时长、剪辑顺序和音画同步。
- [ ] 使用真机硬件编码（而非仅模拟器软件编码），验证 MediaStore 写入 Movies/Iris、系统分享面板、Tailscale/ComfyUI 和后台运行。

## 发布前仍须解决/明确的限制

- 当前 media3 引擎**不导出独立音频轨的混音**。因此若需要音效/配乐形成真正的独立音轨有声成片，必须在后续实现可验证的音频合成后才能勾选上述“完整有声成片”验收；原视频片段自带音轨不等于独立音轨混音。
- 导出由 ViewModel 会话承载，关闭应用会中止；用户可以重新导出，但不能声称后台断点续传。
- AI 导演镜头规划需要用户自配 AI Endpoint，且**仅生成建议**；不会自动提交付费作业。
- GitHub Actions 模拟器不能替代真实厂商账号、真实 Tailscale 网络、OEM 后台限制和真机硬件编码器。

**发布条件：** 全部 GitHub CI 检查绿色 + 真实设备专项测试有可追溯证据 + 手工 E2E 完成，才建议将版本号提升为 `2.4.0` 并生成签名 Release。
