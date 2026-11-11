# Studio 项目索引写放大基线（#109 D1）

本基线在 JVM 单元测试 `ProjectIndexScaleBenchmarkTest` 下构造 **50 项目 × 12 Shot × 3 Take = 600 Shot / 1800 Take**，使用真实 `ProjectCodec.encode/decode`，测试序列化 JSON 字节大小、预热后六次编码耗时（中位数）、一次解码与一次本地 `FileOutputStream + fd.sync` 写入时间。

运行：

```sh
./gradlew :app:testDebugUnitTest --tests '*ProjectIndexScaleBenchmarkTest' --info
```

在 GitHub Test 日志中搜索 `STUDIO_INDEX_BENCHMARK` 可找到每个 CI 运行环境的独立观察值（单位毫秒、字节）。这些数据**不是移动设备磁盘耗时**，不同 CI 机器、缓存、CPU、设备闪存和项目素材数量不可直接推算；测试使用模拟路径，不上传真实媒体或触发云端收费。

**当前架构：**全部项目使用单个 `projects.json` 事务重写，任何镜头标题/Take/时间线更新都会把全部项目重新序列化并写盘。近期 #109 A1 的三份轮转备份增加安全性，同时也会增加每次更新写入量；本次只测量，不在缺乏证据时进行破坏性数据模式迁移。

**后续决策门槛：**需要 Android 10/14 上用实际 50 项目的索引测量端到端写入延迟、瞬时堆内存、存储占用与低容量磁盘故障。若 95 分位 UI 编辑等待明显超标或备份成为持续写放大瓶颈，可考虑按项目分片；Room 迁移必须先提供兼容性、回滚和媒体引用的双向测试。本 PR 不在无真机指标时贸然更换数据库。
