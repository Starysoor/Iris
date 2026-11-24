# AutoDL.Art 云端工作流指南

Iris 通过 AutoDL.Art 的托管 ComfyUI Workflow API 调用云端模型。本文说明 Token、计费、参数支持范围与限制。

## 1. 准备 Token

1. 登录 [AutoDL.Art](https://autodl.art)。
2. 打开 [令牌管理](https://autodl.art/large-model/tokens)，创建令牌。
3. **分组必须选择 `ComfyUI`**；开发者 Token 不能用于工作流 API。
4. 在 Iris 中打开 **设置 → 云端服务**，粘贴 Token 并保存。

Token 使用 Android Keystore 的 AES/GCM 加密保存，不会写入日志或发布包。

## 2. 选择工作流

- 在 **创作 → 云端模型** 浏览 AutoDL 公共目录。列表显示官方名称、工作流 ID、版本和简介，可用 ID 区分重名工作流。生成页可查看完整简介和厂商说明（如果目录提供链接）。
- 在 **设置 → 云端服务 → AutoDL Models** 配置模型 API 令牌。它使用独立于托管工作流的模型目录，提交时保留目录中的原始模型 ID。缺少已知调用方式的模型会显示原因。
- **结果页 → Make video**：使用“设置 → 云端服务 → 默认视频工作流”把当前图片直接带入视频创作。
- **分镜（Board）**：把多个镜头串成一个项目，上一镜尾帧会自动成为下一镜参考图。

## 3. 计费与确认

AutoDL 目录返回 `billing_config`，Iris 会解析出：

- 按次计费：按分辨率档位显示区间，例如 `40–100 / run`；
- 按秒计费：例如 `1 / sec, min 10`；
- 提交前显示本次预估，并要求二次确认（可在 设置 → 应用与界面 关闭，不建议）。

本地“预估消耗”仅用于提醒，**不是账单**，实际扣费以 AutoDL 为准。

云端任务没有公开的远程取消接口。App 内取消只停止手机端跟踪，AutoDL 侧可能继续执行并计费；取消前会再次提示。

## 4. 参数支持范围

Iris 会读取每个工作流的 `input_rules` 并动态生成控件：

| AutoDL 类型 | Iris 控件 | 说明 |
|---|---|---|
| `prompt` | 多行提示词 | 必填时为空会阻止提交 |
| `string` | 单行文本 | |
| `integer` | 数字输入 | 客户端校验 min/max |
| `number` | 数字输入 | 客户端校验 min/max |
| `boolean` | 开关 | |
| `enum` | 下拉框 | 分辨率等选项保留 AutoDL 原始标签 |
| `image` | 相册/拍照/多选 | 压缩后以 `data:image/...;base64,...` 提交 |
| `audio` | 文件选择 | 以 `data:audio/...;base64,...` 提交 |
| `seed` | Seed 字段 + 随机开关 | 关闭随机后才使用输入值 |
| `video` | 视频文件或 HTTP(S) URL | 支持动作迁移工作流的 `ref_video`。应用限制内联文件为 32 MiB |

图片、音频和视频字段显示目录声明的文件格式。工作流参数和格式可以通过 **创作** 或 **项目 → 检查输入** 查看。

### Seed 行为

- 默认“随机种子”开启：每次提交随机生成 seed，输入框中的 seed 不生效。
- 关闭随机种子后使用输入值；历史记录会保存实际使用的 seed，可用于复现。
- 固定 seed 批量生成时，变体会按 `+0、+1、+2…` 递增。

### 限制

- 只能调整 AutoDL `input_rules` 公开的参数；工作流内部未暴露的节点参数无法从 App 修改。
- 应用提交前检查必填图片、音频和视频。可选素材为空时省略对应字段。
- 服务端仍会做最终校验（文件格式、时长、组合约束等），客户端校验只是为了减少无效付费请求。

## 5. API 端点

Iris 使用以下 AutoDL 接口：

```text
POST https://autodl.art/api/v1/comfyui/workflows                  # 目录
GET  https://autodl.art/api/v1/comfyui/workflows/{workflow_id}   # 详情/input_rules
POST https://autodl.art/api/v1/comfyui/comfyui_workflow/{id}     # 提交
GET  https://autodl.art/api/v1/comfyui/comfyui_workflow/result/{task_id} # 轮询
Authorization: <ComfyUI 分组 Token>
```

## 6. 扩展其他云厂商

云端生成层使用 `CloudProvider` 接口 + `CloudProviderRegistry`，AutoDL 是第一个实现。新增厂商时需要：

1. 实现 `CloudProvider`（目录、详情、提交、轮询、下载）；
2. 在 `AppContainer` 中注册；
3. 若参数类型不同，在 `CloudWorkflowMapping` 中扩展映射；
4. 添加对应的 Token/凭证存储与设置项。

不要把厂商 Token 写进代码、CI 或日志。
