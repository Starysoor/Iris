package com.comfyremote.app.data.project

/**
 * Local, always-free director checks (#57 P3): rule-based continuity and
 * hygiene findings over one project's shots and timeline. AI suggestions
 * build on top of these; the rules themselves never need a configured AI.
 */
object DirectorChecks {

    enum class Severity { WARNING, INFO }

    data class Issue(
        val severity: Severity,
        val code: String,
        val message: String,
        val shotId: String? = null,
    )

    /**
     * Pixel-difference threshold above which adjacent shots count as a cut,
     * on the 0..255 mean per-channel scale of FrameStrip.gridDistance
     * (~30% of full range).
     */
    const val CONTINUITY_DIFF_THRESHOLD = 76.5

    /** Target-duration deviation that earns an info note (30% per #57). */
    const val DURATION_DRIFT = 0.30

    /**
     * Runs every rule over [project]. [existingPaths] is the set of media
     * files present on disk (built by the caller); referenced files missing
     * from it are reported. Order-stable so tests and UI stay deterministic.
     */
    fun analyze(project: VideoProject, existingPaths: Set<String>): List<Issue> {
        val issues = mutableListOf<Issue>()
        val shots = project.shots

        shots.forEachIndexed { index, shot ->
            if (shot.status == "failed") {
                issues += Issue(
                    Severity.WARNING,
                    "shot_failed",
                    "镜头 ${index + 1} 生成失败，待重试或检查工作流",
                    shot.id,
                )
            }
            if (shot.prompt.isBlank()) {
                issues += Issue(Severity.INFO, "shot_empty_prompt", "镜头 ${index + 1} 还没有提示词", shot.id)
            }
            val hasReference = shot.referencePath != null || shot.referenceAssetIds.isNotEmpty()
            val hasOwnResult = shot.status == "done" && shot.resultPath != null
            if (!hasReference && index > 0) {
                val previous = shots[index - 1]
                if (previous.lastFramePath == null) {
                    issues += Issue(
                        Severity.WARNING,
                        "shot_no_reference",
                        "镜头 ${index + 1} 缺少参考图，且上一镜没有可接力的尾帧",
                        shot.id,
                    )
                }
            }
            if (index > 0 && shots[index - 1].prompt.isNotBlank() &&
                shots[index - 1].prompt == shot.prompt
            ) {
                issues += Issue(
                    Severity.INFO,
                    "shot_duplicate_prompt",
                    "镜头 $index 与镜头 ${index + 1} 提示词完全相同",
                    shot.id,
                )
            }
            // Missing media files (results / references).
            if (shot.resultPath != null && shot.resultPath !in existingPaths) {
                issues += Issue(
                    Severity.WARNING,
                    "shot_result_missing",
                    "镜头 ${index + 1} 的结果文件已丢失，请修复后再导出",
                    shot.id,
                )
            }
            if (shot.referencePath != null && shot.referencePath !in existingPaths) {
                issues += Issue(
                    Severity.WARNING,
                    "shot_reference_missing",
                    "镜头 ${index + 1} 的参考图文件已丢失",
                    shot.id,
                )
            }
            if (hasOwnResult && shot.lastFramePath != null && shot.lastFramePath !in existingPaths) {
                issues += Issue(
                    Severity.INFO,
                    "shot_frame_missing",
                    "镜头 ${index + 1} 的尾帧文件已丢失，接力会退化为无参考",
                    shot.id,
                )
            }
        }

        // Relay break: chainFrames on and a non-done shot precedes a later shot
        // that is already queued/done — the later shot cannot inherit a frame.
        if (project.chainFrames) {
            shots.forEachIndexed { index, shot ->
                if (index == 0) return@forEachIndexed
                val previous = shots[index - 1]
                val previousReady = previous.status == "done"
                val laterStarted = shot.status != "draft"
                if (!previousReady && laterStarted) {
                    issues += Issue(
                        Severity.WARNING,
                        "relay_broken",
                        "尾帧接力断裂：镜头 $index 尚未完成，镜头 ${index + 1} 已开始",
                        shot.id,
                    )
                }
            }
        }

        // The current exporter renders only the video track. Audio runs in parallel
        // and is not included until the separate audio-mixing feature is delivered.
        val target = project.targetDurationSeconds
        val timeline = project.timeline
        if (target != null && target > 0 && timeline != null && timeline.videoDurationMs > 0) {
            val targetMs = target * 1000.0
            val drift = (timeline.videoDurationMs - targetMs).let { if (it < 0) -it else it } / targetMs
            if (drift > DURATION_DRIFT) {
                issues += Issue(
                    Severity.INFO,
                    "duration_drift",
                    "视频轨与目标时长偏差约 ${(drift * 100).toInt()}%，考虑增删片段或调整目标",
                )
            }
        }

        // Referenced project assets whose files vanished.
        project.assets.forEach { asset ->
            if (asset.path.isNotBlank() && asset.path !in existingPaths) {
                issues += Issue(
                    Severity.WARNING,
                    "asset_missing",
                    "素材「${asset.label.ifBlank { asset.path.substringAfterLast('/') }}」的文件已丢失",
                )
            }
        }

        return issues
    }
}
