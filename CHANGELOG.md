# Changelog

## 2.4.0-beta.2 — Iris Studio 联动与结构化 AI 导演（测试版）

- 将 Studio 的作品上下文连接至创作、任务、结果；项目/镜头关联、按作品筛选任务、结果导入候选 Take 或时间线，不影响原独立创作入口（#101 / PR #102）。
- 新增三种需用户逐阶段确认的工作流配方；依据已配置 Provider 的实际输入规则查看图片/音频必填字段、角色、数量和限制，支持从项目素材到特定输入字段的显式绑定；无真实契约时拒绝猜测并阻断付费提交（PR #103 / #104）。
- AI 导演支持 v1 结构化 JSON 镜头建议，带来源依据、风险、项目内图片 ID 校验、人工编辑与原子保存；不自行提交付费任务（PR #106）。
- 保留先前 Studio 数据安全、媒体导入、音轨混音/试听与导出修复。完整跨 Provider 自动化 DAG、视频视觉理解和 120 秒实体设备成片尚未验收，见 #101 / #80。
- 升级测试版版本号到 2.4.0-beta.2（versionCode 30，包名不变）；附签名 APK 的 SHA-256 文件，保持 GitHub prerelease，不覆盖正式版最新入口。

**测试版本，非 v2.4.0 正式发布。** 请先备份项目与素材，必要时继续使用稳定版 v2.3.2；旧 APK 降级安装不保证读取新版项目索引。

## Unreleased — Iris Studio issue hardening (PR #90)

- Protect Take result files still owned by History and by the legacy storyboard rollback index; block active-task Take deletion and stale task-result overwrites; resolve out-of-order tail-frame selection.
- Preserve project budgets on unrelated settings edits and expose an editable project budget. Recheck limits before project retries.
- Keep explicit per-shot Provider/model overrides when changing project defaults, and allow empty/offline projects before a model is configured.
- Bound video continuity thumbnails to small bitmaps; fix parallel video/audio duration logic and use video duration for export-target checks.
- Refuse incomplete MP4 exports when clips are missing, unplayable or trimmed past source duration, and never copy partially into a final result filename.
- Stream large project media by kind (image 32 MiB, video 1 GiB, audio 512 MiB, subtitles 8 MiB) with progress, cancel and temporary-file cleanup.
- Add a paid-API-free 12-shot / 16-Take mixed-provider project roundtrip test, release QA instructions, and regression cases for all fixes.

**Not yet released:** Source fixes require latest-head CI, emulator matrix and physical-device/codec checks before version bump or signed v2.4.0 APK.

## 2.3.2 — 2026-11-07

### Added

- Multi-reference image inputs follow the real model contract: GPT Image / AutoDL Models / custom OpenAI-compatible models expose up to 4 image slots and send repeated `image[]` multipart parts; Gemini image models send every reference as its own `inlineData` part (up to 4 slots); SiliconFlow `Qwen-Image-Edit-2509` exposes `image` / `image2` / `image3`.
- Real emulator verification covers AutoDL multi-image editing and SiliconFlow `Qwen-Image-Edit-2509` multi-image editing.
- SiliconFlow reference slots are re-numbered contiguously, so filling `image` + `image3` still sends `image` + `image2`.

### Fixed

- Third-party reference-image URLs are downloaded without provider credentials, so an image host can never receive `Authorization` / `x-goog-api-key`.
- A filled but unreadable optional reference image now blocks submission instead of silently becoming a cheaper request with missing references.
- Task and storyboard persistence now writes atomically before publishing state; failed writes surface an error, block task submission, and never delete media that the old project still references.
- AutoDL Models image tasks now clear the delegated base64 result cache on `forgetTask`, preventing long-session memory growth.
- SiliconFlow's removed image `batch_size` field is no longer shown or sent; multi-image runs use Iris' own batch queue.
- SiliconFlow `MiniMaxAI/MiniMax-H3` is recognized as video and uses the new duration/resolution/ratio/`images[]` contract; Wan2.2 models show a retirement warning.
- Custom OpenAI manually saved model ids are no longer filtered by Iris media-name keywords.
- The catalog schema migration preserves all previously saved model IDs and explicitly empty lists rather than deleting entries that may have been user-selected.
- Gemini capability matrices now match model families: Flash gets extreme ratios and 0.5K/2K/4K, Pro gets 1K/2K/4K without the unsupported extreme ratios.
- The emulator smoke workflow now runs for PRs, release branches, version tags and manual dispatches, and submits a debug-only local smoke task.
- SiliconFlow random seeds now respect the provider limit (9,999,999,999), so image generation no longer fails with HTTP 400 `seed: Must be less than or equal to 9.999999999e+09`.
- SiliconFlow boolean parameters such as `prompt_enhancement` are sent as JSON booleans instead of strings.
- AutoDL Models `/models` is filtered to real image/video models; Claude, DeepSeek and GPT text models no longer appear as image models and cannot be selected by mistake.
- SiliconFlow edit models no longer show the unsupported `image_size` field; Qwen-Image-Edit step limits are capped at 20 and CFG is omitted for edit models.
- Gemini image aspect-ratio options now include the documented wider/taller ratios; the extra wide/tall ratios are only shown for Gemini 3 Pro Image.
- OpenAI 2048px sizes are only shown for GPT Image 2.5 models, and custom OpenAI endpoints no longer list pure text models.
- The generation foreground service now calls `startForeground()` immediately, preventing `ForegroundServiceDidNotStartInTimeException` crashes on cloud submit.
- Cloud catalog errors now include the exception class when the provider supplies no message.

### Reliability, security and connectivity follow-up

- Multi-provider generation history now uses a local unique task identity; remote provider IDs cannot collide across services or become unsafe media filenames. Estimated cloud spend deduplicates by provider, endpoint and remote ID.
- Cancellation is durable before reporting success. Local-pending cancellation is atomic with queue dispatch, preventing a task that was already submitted from being falsely reported as cancelled.
- A slow automatic ComfyUI reconnect can no longer overwrite a newer manual connection or disconnect; health probes use short timeouts.
- Storyboard shots whose task records were deleted or pruned become recoverable, with a warning to check the remote task before any paid retry.
- App-imported image and audio streams now have bounded input sizes; audio writes stream to disk instead of loading the whole input into memory.
- Tailscale discovery now checks LAN/VPN ranges, remembered URLs and, when explicitly enabled, read-only official Tailnet device listings. Saved or newly listed cross-subnet IP/MagicDNS peers are validated as ComfyUI servers before display.
- Optional Tailnet API tokens are stored in the existing credential vault, sent only to the fixed Tailscale HTTPS API without redirects, and can be removed from Connect / Server Manager. No token is sent to ComfyUI peers.
- Added JVM regression tests for history and spend identity, cancel/dispatch races, reconnect ordering, orphaned storyboard tasks, bounded media, and read-only Tailnet device API handling.

### Release validation and limitations

- Android 34 emulator smoke, JVM unit tests, debug/release-candidate build and Android lint passed on the merged pull-request head before release; the signed release workflow independently tests and validates APK package/version/signature.
- The emulator smoke verifies app launch/relaunch and basic workflows; it is not a paid end-to-end test for every remote model.
- Tailnet discovery requires an opt-in Tailscale token with `devices:core:read` to find previously unknown peers; connections still depend on Tailnet access controls and ComfyUI being reachable on port 8188.
- A remote provider accepting a job during a simultaneous local storage failure can still require manual recovery. Verify the remote job state before resubmitting to avoid duplicate charges.

### Verified

- Emulator with real AutoDL Models and SiliconFlow keys: AutoDL `gpt-image-2` text/reference/multi-reference generation, SiliconFlow `Kwai-Kolors/Kolors` and reference/multi-reference `Qwen-Image-Edit-2509` all completed; AutoDL model list shows only six media models; no foreground-service crash on submit.
- Version `2.3.2` (versionCode 28).

## 2.3.1 — 2026-11-04

### Added

- Storyboard tail-frame relay is now an optional per-project gameplay, selectable when creating a project and toggleable in the workspace; turning it off clears only automatic references.
- Every finished shot now writes its tail frame into project assets and Results. Video tail frames get a durable History copy; existing projects are migrated once.
- Shot cards show the result and the returned tail frame separately, with a "Use previous shot's tail frame" action.
- The next-shot action now reflects project state (draft, running, all done), and project assets show their labels and semantic roles.
- Tail-frame labels follow shots after reordering, and History copies use clean image filenames.

### Fixed

- Removing a tail-frame asset no longer clears the next shot's automatic reference or forces it into manual mode.
- Loading a project rebuilds the automatic frame chain from its relay setting, so legacy/rotated order stays consistent.

- Cloud tab now has a provider workspace strip and groups every model under its real vendor, matching the local server workspace; tapping a provider filters the list.
- Cloud providers can be enabled/disabled from Cloud services or the provider page. Hidden providers no longer contribute models, recent presets or recent results, and disabling a provider blocks new submissions through it.
- Configured providers can be fully disconnected, or have their pulled model list cleared, so a model can no longer get stuck in the picker.
- Official provider model lists are pulled from each provider instead of seeded from hard-coded presets. Discovery is persisted, missing catalogues are fetched once, and legacy preset lists are migrated away on upgrade.
- Reference-image inputs now depend on the actual model: GPT Image, Gemini image, SiliconFlow edit/I2V and configurable edit-style models expose an upload slot; OpenAI edits send a real multipart `/images/edits` request and Gemini sends inline image parts.
- Cloud errors, billing notes, confirmations and the Generate subtitle name the provider that actually runs the request instead of always saying AutoDL.
- "Make video" no longer routes a legacy AutoDL workflow id to AutoDL when that provider is disabled or unconfigured.
- Generic AutoDL wording was removed from cloud cancellation dialogs and task copy.

### Tests

- Added regression coverage for provider grouping, provider enable/disable, pulled-vs-preset catalogues, model-specific reference-image rules, provider-labelled failures, request codec round-trips and real multipart edit payloads.
- Added storyboard regression coverage for optional tail-frame relay, codec migration, tail-frame asset upserts, shot duplication and persisted tail-frame import state.

- Version `2.3.1` (versionCode 27).

## 2.3.0 — 2026-11-04

### Added

- Phase A foundation: semantic `ReferenceRole` / `ProjectAsset` models, persisted through storyboards, history and task requests.
- Reference-role defaults on local and cloud image fields, with provider rule metadata.
- Skill Runtime metadata on `AiSkillDef`: scopes, input sources, execution and output policies.
- Multi-image vision requests that send every reference with its role/order in one request.
- AI vision rewrite now treats references as hidden context, fuses all enabled references, and describes start→end transitions instead of captioning images.
- Multi-stage vision skill: hidden visual analysis → planner/rewrite, with the analysis never shown or quoted into the final prompt.
- Skill editor exposes description, scopes, input sources, execution and output policies.
- Tag profiles (AUTO / Danbooru / Illustrious / Pony / Custom) shape the AI tag skill's quality conventions.
- AI reasoning controls are provider/model-aware; inapplicable protocol fields are stripped from requests.
- Retired the built-in English-expand and translate skills; users can recreate them as custom skills.
- Independent "auto-save drafts" and "auto-restore drafts" settings.
- Project workspace core: project-owned assets with semantic roles, durable copies, and role labels.
- Shot reordering, duplication and insertion; project assets can become a shot's manual reference or be cleared.
- Storyboard project/shot ids persist on generation tasks; Task Detail links back to the board.
- Project settings dialog: rename, default workflow, aspect ratio, key=value default parameters, and a live shot status overview.
- Project aspect-ratio/default-value model fields; per-shot workflow and portable parameter memory for Regenerate.
- Shot multi-reference: select several project assets and fill consecutive image inputs in one Generate session.
- History results can be added to a storyboard project with a chosen semantic role.
- Official provider adapters: OpenAI Images, Google Gemini image models, Replicate image/video models, SiliconFlow image/video models, and the AutoDL.Art model API (separate from ComfyUI workflow tokens).
- OpenAI catalog updated to GPT Image 2.5 (flare/sunburst), Gemini to Gemini 3.1/3 Pro Image, SiliconFlow to Kolors/Qwen-Image/Wan2.2, and AutoDL model API to GPT Image 2.5 / Qwen-Image / Z-Image / nano-banana plus Wan3.0, Seedance 2.0/2.5, MiniMax H3/Hailuo and Kling video models using their official request/poll protocols.
- Custom JSON-template provider with `{{field}}` placeholders, task/status/result path mapping, sample request/response inference and manual field definitions.
- Replicate input parameters are discovered from each model's OpenAPI input schema.
- Custom OpenAI-compatible provider with editable base URL, token and model list; image parameters are inferred automatically.
- AI model presets refreshed for GPT-5.5, DeepSeek V4.1 Flash, GLM-5.3, Qwen3.8-Max and Kimi K2.6; reasoning controls know GLM-5.3 effort levels.
- Cloud provider registry metadata and a Cloud services list with per-provider detail routing and credential settings.
- Create's Cloud tab aggregates workflows from every registered provider and labels cards with the provider name.
- Cloud confirmation, cancellation and spend tracking now treat every non-ComfyUI provider as hosted cloud work.
- Launch intro: a 2.2 s "lens" animation on launcher cold starts (rack-focus pull-back, ring impact, dive through the pupil into the live UI). Tap or Back skips it; it never plays for notification/share launches or with system animations off, and can be turned off in App & interface.
- Version `2.3.0` (versionCode 26).

### Fixed and verified for release

- All non-local cloud submissions now use the cloud prompt builder so user inputs, images and parameters reach the provider.
- Cloud Provider tasks and paid output metadata survive process restart where the remote task ID and recovery record were durably saved; Replicate polling retains the submission-time Endpoint.
- Storyboard asset removal and shot reorder update automatic references without overwriting manual references; cloud draft scopes are separated from local ComfyUI servers.
- Custom JSON templates preserve number/boolean JSON types and provide honest remote-health status when no safe health probe exists.
- Cloud cancellation remains cancellation rather than failure; provider errors name the actual service.
- Recovery-record write failures are no longer silently treated as success. Token reads preserve the fallback credential when Android Keystore is unavailable.
- Local mock HTTP tests exercise submit → poll → restart recovery → completion. Android 34 emulator QA verified cold start, force-stop/relaunch and crash-free app shell. Unit tests, Debug/Release builds and lint passed.

### Known limitations

- An I/O failure exactly after a remote task is accepted but before the task index is written can still prevent fully automatic recovery; the remote task ID is shown in submission errors when known.
- Provider-specific paid end-to-end coverage is partial. API/model availability, pricing and remote cancellation depend on each provider; follow the current official documentation.
- Device smoke tests verify launch/relaunch and the connection screen, not every generation UI and workflow.

## 2.2.4 — 2026-11-03

### Added

- App shell starts immediately; ComfyUI auto-recovery runs in the background with a short-timeout probe, and health checks update per server.
- VPN/Tailscale-aware discovery hint and CGNAT subnet handling.
- Workflow presets remember their original server and skip non-portable image/audio references on other servers.
- Storyboard shots lock batch size to 1 and can record manual vs automatic reference images.

### Fixed

- Cloud spend is recorded only after the provider returns a remote task id, with recovery deduplication.
- Local ComfyUI video/audio downloads stream to disk; partial result downloads are reported.
- Local QUEUED cancel only succeeds after ComfyUI confirms queue removal; RUNNING cancel warns that `/interrupt` is server-wide and surfaces failures instead of silently marking CANCELLED.
- Result viewer, gallery save and share use one media-type resolver (AVI/3GP/MKV included); failed writes clean up their MediaStore entry.
- API-format workflows expose INT/FLOAT/BOOLEAN/COMBO parameters and skip linked inputs.
- Deleting a server clears its saved last URL; deleting a running storyboard shot/project is blocked and orphan frame files are collected.
- External notification/share intents are consumed once per Activity creation, and sharing no longer requires a local ComfyUI session.
- AI API keys and GitHub tokens are masked; non-local HTTP AI endpoints show a clear warning.
- Recent-result reuse keeps the original server and source field metadata.
- Version `2.2.4` (versionCode 25).

## 2.2.3 — 2026-11-03

### Changed

- Every cloud submission now passes one confirmation gate; retries and bulk retries show run count, per-run estimate and total estimate.
- Cross-workflow parameter reuse maps values by field role/input instead of raw node IDs, reports unmatched values, and never guesses between positive/negative prompts.

### Fixed

- Running storyboard shots can no longer be reopened or resubmitted; the next shot waits for the running one, deleted shots rebase downstream references, and tail frames use the actual final frame.
- History keeps media referenced by storyboards and writes its index atomically; pruning waits for in-flight downloads.
- Cloud result downloads use the long-timeout client and stream directly to disk instead of buffering whole videos in memory.
- Image preprocessing now scales exactly to the configured limit and fails clearly when an image cannot be decoded.
- Clearing the cloud input cache keeps files referenced by queued tasks, drafts, history and presets.
- Bulk "Cancel queued" warns when cloud jobs were already submitted.
- Recent results are filtered by server/cloud before the display limit.
- Transient Keystore decrypt failures no longer delete saved API tokens.
- Version `2.2.3` (versionCode 24).

## 2.2.2 — 2026-11-03

### Added

- Setting to choose a workflow when reusing history/task parameters; off (default) returns straight to the original platform and workflow.

### Fixed

- Reusing a cloud (AutoDL) result no longer asks for a local ComfyUI server.
- Version `2.2.2` (versionCode 23).

## 2.2.1 — 2026-11-03

### Added

- Cloud workflow cards now show the unique AutoDL workflow ID under the name.
- Cloud workflow descriptions are shown in the list and in the Generate workflow card; the full AutoDL detail `content` is available in Workflow notes.
- Debug logging of cloud media metadata (`IrisCloud`) to diagnose missing reference images.

### Fixed

- Prevent submitting a cloud task while reference images/audio are still being prepared.
- Normalize every cloud reference image to JPEG, so HEIC/AVIF/GIF/null-MIME images cannot be sent with an invalid MIME type.
- Show a clear note that multi-image references do not guarantee an identical first frame; use a first/last-frame workflow for exact first-frame control.
- Version `2.2.1` (versionCode 22).

## 2.2.0 — 2026-11-03

### Added

- Iris design system: light/dark palettes, typography, surfaces, buttons, chips, inputs, progress and settings components.
- Edge-to-edge layout with theme-aware system bar contrast.
- Resource-based localization (`values` + `values-zh`, 452 keys) with plurals for count strings.
- Redesigned Create, Generate, Tasks, History, Settings, Cloud Settings, AI Settings, Update and Board screens.
- Verification screenshots under `docs/verification/redesign/`.

### Changed

- Screen code split into focused sections/components; removed the old inline `L10n` helper.
- `AppStrings` now resolves through application resources (no static Activity/Context).
- Version `2.2.0` (versionCode 21).

### Fixed

- Count strings now use Android plurals.
- Removed unused action strings and launcher color resource.
- Compose modifier parameter/extension warnings.
- Replaced platform `ExifInterface` with AndroidX, explicit locales for formatted sizes, and removed an obsolete SDK check.

## 2.1.0 — 2026-11-02

### Added

- AutoDL.Art hosted ComfyUI workflow support: 25 workflows, including H3 image/video, first/last frame, multi-image/multi-audio, lip-sync and IndexTTS2.
- Cloud price parsing (`billing_config`), per-run estimate, submit confirmation, local spend estimate and cloud cancel warnings.
- Audio reference inputs via file picker and `data:audio/...;base64,...`.
- Storyboard / multi-shot projects: shot list, shared style prompt, automatic last-frame extraction and next-shot reference chaining.
- AI vision rewrite: sends the actual image to a multimodal OpenAI-compatible model and rewrites the prompt.
- Encrypted secrets (AI key, AutoDL token, GitHub token) using Android Keystore AES/GCM with legacy plaintext migration.
- `CloudProvider` abstraction and generic cloud generation backend.
- Gradle wrapper.
- Verification screenshots under `docs/verification/`.

### Changed

- Rebranded to **Iris（灵瞳）**; launcher icon uses the supplied artwork, with adaptive and monochrome variants.
- Compact Create/Tasks layouts; recent result and setup counts configurable.
- Consolidated Settings: appearance, creation defaults, cloud submit, notifications, cloud services and cache.
- Task delete actions reduced to one confirmation point.
- Version `2.1.0` (versionCode 20).
- Repository renamed from `Starysoor/ComfyRemote` to `Starysoor/Iris`.

### Fixed

- Required image inputs now block generation when empty; optional inputs remain warnings.
- Cloud amount limits (min/max) are checked before submit.
- Video/audio results use media-aware history, thumbnails, playback and gallery export.
- Task/history reuse preserves the original server and actual seed.

## 2.0.0 — 2026-11-02

- Initial Iris rebrand, AutoDL cloud workflows, video results, AI vision rewrite, app settings and compact UI.
