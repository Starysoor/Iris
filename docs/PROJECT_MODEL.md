# Iris · 项目数据模型（Iris Studio P0）

Iris v2.4 起，创作工作台的持久化中心从「分镜」（`storyboards.json`）升级为「项目」（`projects.json`）。本文描述新模型的实体、ID 语义、存储与迁移/回滚/垃圾回收规则，以及 Room 的评估结论。对应 Epic Issue #57 的 P0 阶段。

## 实体与 ID

全部实体 ID 均为 UUID v4（`UUID.randomUUID().toString()`），生成后永不复用。每个 ID 只标识一个实体类型：

| 实体 | 模型 | 说明 |
|---|---|---|
| 项目 | `VideoProject`（`data/project/ProjectModels.kt`） | 一个自包含的视频创作工作 |
| 镜头 | `ProjectShot` | 项目内有序创作单元；live 字段（status/taskId/resultPath/lastFramePath）镜像当前主版本 Take |
| 版本 | `ShotTake` | 一次生成尝试的完整快照（模型/参数/参考/结果/费用），每次提交自动记录；P1 支持设主版本/收藏/弃用/删除 |
| 素材 | `ProjectAsset`（`data/reference/ReferenceModels.kt`） | 项目级可复用媒体；`kind`（IMAGE/VIDEO/AUDIO/SUBTITLE）与 `tags`；导入经 `ProjectAssetStore`（图片 JPEG 规范化，视频/音频原样拷贝，分类限制：图片 32 MiB、视频 1 GiB、音频 512 MiB、字幕 8 MiB；视频/音频流式复制，支持进度与取消） |
| 一致性档案 | `AssetProfile` | 一个角色/场景的命名档案（名称+描述+有序素材列表）；应用到镜头 = 按档案内顺序展开为镜头多参考 |
| 生成任务 | `GenerationTask.taskId` | 复用现有任务引擎 ID，通过 `shot.taskId` 与 `GenerationRequest.storyboardProjectId/storyboardShotId` 弱关联（纯字符串，无外键） |

**为 P2 时间线预留的 ID 方案**：`Timeline` / `TimelineClip` / `Track` / `ProjectTask` / `ExportJob` 届时同样使用 UUID，且 clip 前缀将保留 `tlc:` 命名空间用于调试。P0 不实现时间线数据结构。

## 身份规则（必须遵守）

- **时间线片段 ≠ 镜头 ≠ 生成任务 ≠ 文件**。未来一个 Take 的结果文件可被多个时间线片段引用；删除镜头绝不能误删仍被引用的媒体文件。
- Take 是历史记录：重复生成同一镜头会产生多条 Take，`selectedTakeId` 指向产生当前 live 结果的那条；复制镜头会重置生成历史（不复制 Take）。
- **Take 生命周期**：`running → done | failed | cancelled`，已完成版本可标记 `discarded`。运行中镜头及 Take 不允许切换/删除；切换已完成主版本会解除过时的 `taskId`，旧任务状态不能覆盖用户的选择。删除 Take 只移除项目版本记录，**绝不能直接删除结果库所拥有的文件**；过期且无任何项目/旧分镜引用的 app-owned 项目媒体才进入受保护 GC。
- **镜头参考语义**：`referenceAssetIds` 是**有序**列表，提交时按顺序映射到工作流的 IMAGE 字段（首字段通常是 first_frame）；ReferenceRole（START_FRAME/END_FRAME 等）作为语义徽章随列表头部/尾部标注，暂不参与提交映射。
- **cameraNotes（动作/运镜）**：生成时按 `风格提示 + 镜头提示 + cameraNotes` 顺序拼接进 prompt，UI 中独立展示。
- 素材文件为 app-owned（`filesDir/storyboards/assets`，与旧媒体根一致，存量文件不迁移不失效），索引只存路径/类型/标签与元数据；一致性档案按 id 引用素材，删除素材会同步从档案中摘除，删除档案不影响素材。

## 存储

- 单文件 `filesDir/projects.json`，信封带版本号：`{"version":2,"projects":[...]}`。字段读取全部容错（opt* + 默认值），未知字段忽略，缺 ID 的行丢弃。
- codec（`ProjectCodec`）与 store（`ProjectStore`）分离，codec 为纯函数，可在 JVM 测试。
- 写入走与 legacy `StoryboardStore` 完全一致的原子写配方：`.tmp` 文件 + `fd.sync()` + `Files.move(ATOMIC_MOVE)`（不支持时降级），**写盘成功后才发布内存 StateFlow**。
- 写前损坏防护：`rawProjectCount()` 统计原始行数，与 `decode()` 成功行数不一致（或文件不是合法 JSON 信封）时**拒绝写入**，防止用可读部分覆盖掉可恢复数据；错误通过 `persistenceError: StateFlow<String?>` 暴露给 UI。

## 迁移与回滚（storyboards.json → projects.json）

`ProjectMigration.migrateIfNeeded(filesDir)`（`data/project/ProjectMigration.kt`）在项目 UI 上线时（P0-2）由 `AppContainer` 调用，语义：

1. `projects.json` 已存在且可解析 → no-op（迁移已完成）。
2. `projects.json` 损坏且 legacy 锚点存在 → 从 legacy 重新迁移覆盖（备份先做）。
3. 首次迁移：先复制 `storyboards.json` → `storyboards.backup-<epoch>.json`（保留最新 2 份），再解码旧数据 → 逐字段映射（**UUID、时间戳、路径全部原样保留**；每个有过生成的镜头生成一条确定性 Take，ID 为 `<shotId>-take-1`）→ 编码后回读自检（项目数/镜头数/素材数一致）→ 原子写 `projects.json`。
4. **提交点是 projects.json 的原子写**。提交前任何一步失败或崩溃：旧文件字节不变、不产生半成品、下次启动重跑即可（备份覆盖复制，幂等）。
5. `storyboards.json` 迁移后**冻结不更新**，永久保留作为回滚锚点。回滚 = 换回旧版 APK，旧版从冻结快照继续（注意：迁移之后新建的数据不存在于快照中，见下）。

**降级注意**：从 v2.4+ 回退到 v2.3.x 后，旧版仍可读冻结快照；但迁移后新增/修改的项目只存在于 `projects.json`，旧版不可见。旧版执行删除类操作时其 GC 只保护快照中的引用（10 分钟宽限后可能清理迁移后新建的未引用媒体）。

## 垃圾回收语义

- 触发点：删除项目 / 删除镜头 / 删除素材（成功落盘后）。
- 保护集合 `referencedMediaPaths()`：所有 shot 的 `resultPath`/`lastFramePath`/`referencePath`、全部 Take 的 `resultPath`、全部素材 `path`。
- 删除规则：**先提交索引、再删文件**；仅清理 app-owned `filesDir/storyboards/` 下「新项目索引和**冻结的旧分镜回滚索引**均未引用，且 `lastModified` 早于 10 分钟宽限期」的媒体；对任意外部绝对路径、结果库媒体不得直接执行删除。
- 跨 Store 保护：`AppContainer` 将 legacy store 与 project store 的引用集**并集**注入 `HistoryStore` 的 protectedMedia 钩子，结果页 History 的媒体清理永不破坏任一侧仍在引用的文件。

## 时间线与成片导出（P2）

- `Timeline`（视频轨 + 音频轨）/ `TimelineClip`（`tlc:` ID 前缀）：**按路径引用媒体**，同一文件可被多个片段引用；`referencedMediaPaths` 并入片段 sourcePath，删除镜头/Take/素材时仍被时间线引用的文件受保护。两个逻辑轨道并行：`totalDurationMs = max(videoDurationMs, audioDurationMs)`，不能把两条轨道时长相加；当前可导出时长只按视频轨计算。
- **预览与导出共用同一份 timeline 数据**；预览保真度差异：ExoPlayer 顺序 playlist 应用裁剪，速度/音量/静音预览为 1x（对话框内明示），导出完整生效。
- **导出能力矩阵（media3 1.4.1，compileSdk 34 兼容的最终版本线）**：
  - ✅ 视频轨顺序导出：裁剪、0.25-4x 速度、0-200% 音量、静音、片段自带音频音画同步
  - ✅ 完整文件验证（可播放 + 时长 ±10%）；先严格校验所有源视频/裁剪边界，任何片段丢失或不可读取即拒绝导出；`.tmp → 验证 → 原子 rename`，绝不在原子重命名失败后复制为最终文件。
  - ❌ 音频轨（配乐）混音导出——需 compileSdk 35 + media3 1.5 升级后补；UI 固定提示，不承诺
- **导出生命周期**：ViewModel 作用域，关闭应用中止导出（tmp 不入结果页），需手动重试；前台服务化属后续增强。
- 成片入库：`HistoryRepository.newMediaFile + add`（tags: `project-export`），结果页可见/可分享/可存相册。

## 预算字段语义（P1）

`VideoProject.budget` 是项目级估算预算上限（平台点数，非货币），用户在项目设置页编辑，可留空取消。更改其他设置不清空既有预算；`BudgetGate` 在项目云端提交/重试前计算 `spent + newEstimate > budget` 并阻止超预算提交（只是费用估算，不能作为平台实际账单）。未选择模型的项目也能离线新建/管理素材/剧本，生成时再明确选模型。

## AI 导演（P3）

- **建议制边界**：导演页（DirectorScreen）的三块能力——规则检查、逐秒抽帧衔接、AI 镜头计划——**全部只提建议**；唯一的落地操作是「应用建议为本地草稿镜头」（addShot，免费），绝不自动生成或提交付费作业。
- **本地检查规则**（DirectorChecks，永远免费）：失败镜头、接力断裂（仅 chainFrames 开启）、缺参考且无可接力尾帧、相邻重复提示词、结果/参考/尾帧/素材文件丢失、时间线总长与目标时长偏差 >30%。衔接像素差阈值 `CONTINUITY_DIFF_THRESHOLD = 76.5`（0-255 平均通道刻度，约 30%）。
- **预算阈值阻断**（BudgetGate）：仅作用于**项目内镜头的云提交**（预算是项目级字段）；在付费确认门禁之前执行；`spent + newCost > budget` 阻断（恰好达到允许）；预算为空/0 不启用。已花费估算 = 该项目全部任务 `estimatedCloudCost` 之和（与主页横幅同一聚合）。
- **AI 计划输出契约**：`序号 | 提示词 | 运镜描述 | 建议时长秒` 逐行；parsePlan 宽松解析（markdown/缺列/坏行容错），不依赖 response_format。AI 未配置时入口保留、点按提示前往设置。

## Room 评估（P0 结论）

**结论：P0/P1 维持 JSON 方案，P2 时间线落地时重评。**

现状配方已覆盖的关键能力：事务性（原子写 + 失败不发布内存态）、损坏防护（写前行数校验 + 拒绝覆盖）、幂等恢复（迁移提交点 + 容错读）、JVM 可测性（纯函数 codec + File 注入）。当前数据规模（索引 < 数百项目）单文件读写性能足够，且全部 56 个测试类无需 Android 设备即可运行，回归成本低。

引入 Room 的代价：新增依赖与 schema 版本管理；与现有 8+ 个 Store 并存形成双存储期；事务语义需要重新验证（尤其「先写索引再删文件」与进程死亡恢复）；测试需要 Robolectric/仪器环境。而 P0/P1 的查询模式（按项目取全部镜头/素材）并没有关系查询压力。

**P2 重评触发条件**：时间线引入多轨片段与同一媒体多引用后，出现「按引用关系反向查询/批量级联更新」需求，或单项目 shots+takes+clips 规模让 `projects.json` 写放大明显时，将项目索引与关系迁移至 Room（媒体文件仍为 app-owned 文件，库中只存路径/校验/元数据）。

## 相关代码

- 模型/codec/store/迁移：`app/src/main/java/com/comfyremote/app/data/project/`
- legacy 分镜（迁移源，冻结后保留）：`app/src/main/java/com/comfyremote/app/data/storyboard/`
- 回归测试：`app/src/test/java/com/comfyremote/app/data/project/`
- AppContainer 接线（protectedMedia 并集）：`app/src/main/java/com/comfyremote/app/AppContainer.kt`
