package com.comfyremote.app.data.project

import com.comfyremote.app.data.reference.ReferenceRole
import java.util.UUID

/**
 * One recorded generation attempt for a shot. A take keeps the full model /
 * parameter / reference snapshot together with its outcome and cost so past
 * versions stay inspectable without touching the shot's live fields.
 */
data class ShotTake(
    val id: String = UUID.randomUUID().toString(),
    val providerId: String = "",
    val workflowPath: String = "",
    val prompt: String = "",
    val seed: String? = null,
    val values: Map<String, String> = emptyMap(),
    val referenceAssetIds: List<String> = emptyList(),
    val status: String = "running", // running | done | failed | cancelled | discarded
    val taskId: String? = null,
    val resultPath: String? = null,
    val error: String? = null,
    val costEstimate: Double? = null,
    val favorite: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
)

/**
 * One shot inside a video project. The live fields (status/taskId/resultPath/
 * lastFramePath) mirror the most recent generation so existing single-result
 * semantics keep working; [takes] records every attempt for later A/B views.
 */
data class ProjectShot(
    val id: String = UUID.randomUUID().toString(),
    val prompt: String = "",
    /** Optional camera/blocking description for the shot workshop. */
    val cameraNotes: String? = null,
    /** Reference image for this shot, already copied to app storage when set. */
    val referencePath: String? = null,
    /** True when the user picked the reference manually; auto updates must not overwrite it. */
    val referenceManual: Boolean = false,
    /** Semantic purpose of this shot's reference image. */
    val referenceRole: ReferenceRole = ReferenceRole.AUTO,
    /** Project asset ids used as this shot's multiple references. */
    val referenceAssetIds: List<String> = emptyList(),
    /** Optional explicit start-frame asset override. */
    val firstFrameAssetId: String? = null,
    /** Optional explicit end-frame asset override. */
    val lastFrameAssetId: String? = null,
    /** Full provider workflow path override; null uses the project workflow. */
    val workflowPath: String? = null,
    /** Provider id for the workflow override. */
    val providerId: String? = null,
    /** Bare model/workflow id retained for display and legacy projects. */
    val workflowId: String? = null,
    /** Last used non-media field values for this shot. */
    val values: Map<String, String> = emptyMap(),
    /** Last frame extracted from a successful result; feeds the next shot. */
    val lastFramePath: String? = null,
    /** True once this frame has been written to project assets (and History for videos). */
    val tailFrameStored: Boolean = false,
    val status: String = "draft", // draft | running | done | failed
    val taskId: String? = null,
    val resultPath: String? = null,
    val error: String? = null,
    val takes: List<ShotTake> = emptyList(),
    /** Take that produced the current live result; null for never-generated shots. */
    val selectedTakeId: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

/** Scope of a consistency profile: one reusable character or scene identity. */
enum class ProfileKind {
    CHARACTER,
    SCENE;

    companion object {
        fun fromStorage(raw: String?): ProfileKind =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: CHARACTER
    }
}

/**
 * A consistency profile: one named character or scene bundling several
 * reference assets (e.g. a character's three views) plus a short description
 * so shots can reuse the same identity across the project.
 */
data class AssetProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val summary: String = "",
    val kind: ProfileKind = ProfileKind.CHARACTER,
    val assetIds: List<String> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
)

/** Logical track kind of a timeline clip. */
enum class TrackKind { VIDEO, AUDIO }

/**
 * One clip on a timeline track. Clips reference media **by path**, so the same
 * take result or asset can be reused by many clips; deleting the source only
 * loses the clip's media when nothing else references it (GC rule). A clip is
 * never the same entity as the shot, take, task or file it points at.
 */
data class TimelineClip(
    val id: String = "tlc:" + UUID.randomUUID(),
    val sourcePath: String,
    /** Display-only origin, e.g. "镜头 3 · take-a" or the asset label. */
    val sourceLabel: String = "",
    /** Trim window inside the source media, in milliseconds. */
    val sourceInMs: Long = 0,
    val sourceOutMs: Long = 0,
    /** Playback speed applied to the trimmed range (export is authoritative). */
    val speed: Float = 1f,
    /** Gain applied to the clip's own audio; 0 when muted. */
    val volume: Float = 1f,
    val muted: Boolean = false,
) {
    /** Timeline duration after speed; the authoritative trim sanity check. */
    val trimmedMs: Long get() = (sourceOutMs - sourceInMs).coerceAtLeast(0L)
    val durationMs: Long get() = if (speed > 0f) (trimmedMs / speed).toLong() else 0L
}

/** A project timeline with one ordered video track and one audio track. */
data class Timeline(
    val id: String = UUID.randomUUID().toString(),
    val videoTrack: List<TimelineClip> = emptyList(),
    val audioTrack: List<TimelineClip> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) {
    fun track(kind: TrackKind): List<TimelineClip> =
        if (kind == TrackKind.VIDEO) videoTrack else audioTrack

    /** The video and audio tracks run in parallel, not end-to-end. */
    val videoDurationMs: Long get() = videoTrack.sumOf { it.durationMs }
    val audioDurationMs: Long get() = audioTrack.sumOf { it.durationMs }
    val totalDurationMs: Long get() = maxOf(videoDurationMs, audioDurationMs)
}

/**
 * The Studio-era project: one self-contained video work carrying its shots,
 * assets, default workflow and (stored-only, not yet enforced) budget.
 */
data class VideoProject(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    /** Short synopsis / script summary shown on the project home. */
    val synopsis: String = "",
    val aspectRatio: String = "",
    /** Target final duration in seconds; stored only in P0. */
    val targetDurationSeconds: Int? = null,
    val stylePrompt: String = "",
    /** Legacy bare AutoDL workflow id kept for parity with pre-Studio projects. */
    val workflowId: String,
    /** Unified workflow path (`autodl:`, `autodl-model:`, `replicate:`, ...). */
    val workflowPath: String = "",
    val providerId: String = "",
    val defaultValues: Map<String, String> = emptyMap(),
    /** Optional "tail frame relay" gameplay; off leaves each shot's reference manual. */
    val chainFrames: Boolean = true,
    /** Soft budget cap for cloud spend; stored only in P0, no enforcement yet. */
    val budget: Double? = null,
    val shots: List<ProjectShot> = emptyList(),
    val assets: List<com.comfyremote.app.data.reference.ProjectAsset> = emptyList(),
    /** Consistency profiles (characters/scenes); optional and always safe to omit. */
    val profiles: List<AssetProfile> = emptyList(),
    /** Final-cut timeline; optional and always safe to omit. */
    val timeline: Timeline? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

/**
 * A persisted running shot whose task vanished must be made editable,
 * never resubmitted automatically (paid work must not double-fire).
 */
internal fun isOrphanedRunningShot(shot: ProjectShot, knownTaskIds: Set<String>): Boolean =
    shot.status == "running" && (shot.taskId.isNullOrBlank() || shot.taskId !in knownTaskIds)

/** Deterministic take id for the take created when migrating a legacy result. */
internal fun legacyTakeId(shotId: String): String = "$shotId-take-1"
