package com.comfyremote.app.data.project

import android.content.Context
import com.comfyremote.app.data.cloud.AutoDlConfig
import com.comfyremote.app.data.reference.ProjectAsset
import com.comfyremote.app.data.storyboard.StoryboardCodec
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray

/**
 * JSON-file-backed Studio projects (projects.json). The atomic-write recipe,
 * corruption guard and media GC semantics intentionally mirror
 * [com.comfyremote.app.data.storyboard.StoryboardStore] so both stores behave
 * identically during the migration window.
 */
class ProjectStore internal constructor(private val file: File) {
    constructor(context: Context) : this(File(context.filesDir, "projects.json"))

    private val lock = Any()
    /** Missing index is a normal empty Studio; unreadable index must not masquerade as one. */
    private val _loadError = MutableStateFlow<String?>(null)
    val loadError: StateFlow<String?> = _loadError.asStateFlow()
    private val _projects = MutableStateFlow(load())
    val projects: StateFlow<List<VideoProject>> = _projects.asStateFlow()

    /** Last durable-state write failure; surfaced by the project screens. */
    private val _persistenceError = MutableStateFlow<String?>(null)
    val persistenceError: StateFlow<String?> = _persistenceError.asStateFlow()

    fun clearPersistenceError() {
        _persistenceError.value = null
    }

    /** Validated, app-private recovery points; unreadable snapshots are hidden. */
    fun availableBackups(): List<ProjectBackupSnapshot> =
        synchronized(lock) { ProjectIndexBackups(file).list() }

    /** Explicit user action only. Never silently replace the index on app startup. */
    fun restoreBackup(filename: String): Boolean = synchronized(lock) {
        try {
            ProjectIndexBackups(file).restore(filename)
            val loaded = load()
            if (_loadError.value != null) throw IOException("Restored index could not be loaded")
            _projects.value = loaded
            _persistenceError.value = null
            true
        } catch (error: Exception) {
            _persistenceError.value = error.message ?: error.javaClass.simpleName
            false
        }
    }

    fun load(): List<VideoProject> = synchronized(lock) {
        try {
            val projects = if (!file.exists()) {
                emptyList()
            } else {
                // decode() tolerates partial damage for inspection; never display a
                // partly decoded index as if some of its projects had been deleted.
                if (!file.isFile) throw IOException("Project index is not a regular file")
                val raw = file.readText()
                val count = ProjectCodec.rawProjectCount(raw)
                    ?: throw IOException("Project index has invalid JSON or version")
                val decoded = ProjectCodec.decode(raw)
                if (decoded.size != count) throw IOException("Project index contains unreadable rows")
                decoded
            }
            _loadError.value = null
            projects
                .map { project -> project.copy(shots = rebaseAutoReferences(project.shots, project.chainFrames)) }
                .sortedByDescending { it.updatedAt }
        } catch (error: Exception) {
            // Keep all bytes intact, and make the recovery blocker visible to UI.
            _loadError.value = error.message ?: error.javaClass.simpleName
            emptyList()
        }
    }

    fun create(
        title: String,
        workflowId: String,
        workflowPath: String = "",
        providerId: String = "",
        stylePrompt: String = "",
        synopsis: String = "",
        aspectRatio: String = "",
        chainFrames: Boolean = true,
    ): VideoProject? = synchronized(lock) {
        val path = workflowPath.trim().ifBlank {
            workflowId.trim().takeIf { it.isNotBlank() }
                ?.let { AutoDlConfig.PATH_PREFIX + it }
                .orEmpty()
        }
        val project = VideoProject(
            id = UUID.randomUUID().toString(),
            title = title.trim().ifBlank { "Project" },
            synopsis = synopsis.trim(),
            workflowId = workflowId.trim().ifBlank { path.substringAfter(':') },
            workflowPath = path,
            providerId = providerId.trim().ifBlank {
                if (path.startsWith(AutoDlConfig.PATH_PREFIX)) AutoDlConfig.PROVIDER else ""
            },
            stylePrompt = stylePrompt.trim(),
            aspectRatio = aspectRatio.trim(),
            chainFrames = chainFrames,
        )
        if (write(listOf(project) + _projects.value)) project else null
    }

    /** Archive is a reversible index flag, not media deletion. */
    fun setArchived(projectId: String, archived: Boolean): Boolean = update(projectId) {
        it.copy(archived = archived, updatedAt = System.currentTimeMillis())
    }

    /**
     * A copied project is a new production *plan*, never a clone of existing
     * paid jobs, Takes, finished media outputs or timeline state.
     */
    fun duplicateProject(projectId: String): VideoProject? = synchronized(lock) {
        val source = _projects.value.firstOrNull { it.id == projectId } ?: return@synchronized null
        val now = System.currentTimeMillis()
        val assets = source.assets.filterNot { it.id.startsWith("tail:") }
        val assetIds = assets.mapTo(hashSetOf()) { it.id }
        val shots = source.shots.map { shot ->
            ProjectShot(
                id = UUID.randomUUID().toString(),
                prompt = shot.prompt,
                cameraNotes = shot.cameraNotes,
                plannedDurationSeconds = shot.plannedDurationSeconds,
                referencePath = if (shot.referenceManual) shot.referencePath else null,
                referenceManual = shot.referenceManual,
                referenceRole = shot.referenceRole,
                referenceAssetIds = shot.referenceAssetIds.filter { it in assetIds },
                firstFrameAssetId = shot.firstFrameAssetId?.takeIf { it in assetIds },
                lastFrameAssetId = shot.lastFrameAssetId?.takeIf { it in assetIds },
                workflowPath = shot.workflowPath,
                providerId = shot.providerId,
                workflowId = shot.workflowId,
                values = shot.values,
                createdAt = now, updatedAt = now,
            )
        }
        val copy = source.copy(
            id = UUID.randomUUID().toString(),
            title = source.title + " (copy)",
            archived = false,
            shots = shots,
            assets = assets,
            profiles = source.profiles.map { profile ->
                profile.copy(
                    id = UUID.randomUUID().toString(),
                    assetIds = profile.assetIds.filter { it in assetIds },
                    createdAt = now,
                )
            },
            stageMediaBindings = source.stageMediaBindings.filter { it.assetId in assetIds },
            timeline = null,
            createdAt = now, updatedAt = now,
        )
        if (write(listOf(copy) + _projects.value)) copy else null
    }

    fun delete(projectId: String) = synchronized(lock) {
        val project = _projects.value.firstOrNull { it.id == projectId } ?: return@synchronized false
        if (project.shots.any { it.status == "running" }) return@synchronized false
        if (write(_projects.value.filterNot { it.id == projectId })) {
            gcOrphanMediaLocked()
            true
        } else {
            false
        }
    }

    fun setTitle(projectId: String, title: String): Boolean = update(projectId) {
        it.copy(title = title.trim().ifBlank { "Project" }, updatedAt = System.currentTimeMillis())
    }

    fun setSynopsis(projectId: String, synopsis: String): Boolean = update(projectId) {
        it.copy(synopsis = synopsis.trim(), updatedAt = System.currentTimeMillis())
    }

    fun setStyle(projectId: String, style: String): Boolean = update(projectId) {
        it.copy(stylePrompt = style.trim(), updatedAt = System.currentTimeMillis())
    }

    fun setAspectRatio(projectId: String, ratio: String): Boolean = update(projectId) {
        it.copy(aspectRatio = ratio.trim(), updatedAt = System.currentTimeMillis())
    }

    fun setTargetDuration(projectId: String, seconds: Int?): Boolean = update(projectId) {
        it.copy(targetDurationSeconds = seconds?.takeIf { value -> value > 0 }, updatedAt = System.currentTimeMillis())
    }

    fun setBudget(projectId: String, budget: Double?): Boolean = update(projectId) {
        it.copy(budget = budget?.takeIf { value -> value.isFinite() && value > 0.0 }, updatedAt = System.currentTimeMillis())
    }

    /** Local recipe/plan changes never submit paid work or modify an existing Take. */
    fun setWorkflowRecipe(projectId: String, recipeId: String): Boolean {
        if (!StudioWorkflowRecipes.supports(recipeId)) return false
        return update(projectId) { project ->
            project.copy(workflowRecipeId = recipeId, updatedAt = System.currentTimeMillis())
        }
    }

    fun setStageWorkflow(projectId: String, kind: String, workflowPath: String): Boolean {
        if (!StudioWorkflowRecipes.isModelKind(kind)) return false
        return update(projectId) { project ->
            project.copy(
                stageWorkflowPaths = if (workflowPath.isBlank()) project.stageWorkflowPaths - kind
                    else project.stageWorkflowPaths + (kind to workflowPath),
                updatedAt = System.currentTimeMillis(),
            )
        }
    }

    /**
     * Save one explicit assignment without altering any existing tasks or Takes.
     * Null removes the binding. Asset ownership is checked before persistence.
     */
    fun setStageMediaBinding(
        projectId: String,
        workflowPath: String,
        fieldName: String,
        assetId: String?,
    ): Boolean = synchronized(lock) {
        if (workflowPath.isBlank() || fieldName.isBlank()) return@synchronized false
        var valid = true
        val persisted = updateLocked(projectId) { project ->
            if (assetId != null && project.assets.none { it.id == assetId }) {
                valid = false
                return@updateLocked project
            }
            val retained = project.stageMediaBindings.filterNot {
                it.workflowPath == workflowPath && it.fieldName == fieldName
            }
            project.copy(
                stageMediaBindings = if (assetId == null) retained
                    else retained + StudioStageMediaBinding(workflowPath, fieldName, assetId),
                updatedAt = System.currentTimeMillis(),
            )
        }
        valid && persisted
    }

    /** Toggles the optional tail-frame relay. Turning it off clears automatic refs only. */
    fun setChainFrames(projectId: String, enabled: Boolean): Boolean = update(projectId) { project ->
        project.copy(
            chainFrames = enabled,
            shots = rebaseAutoReferences(project.shots, enabled),
            updatedAt = System.currentTimeMillis(),
        )
    }

    fun updateSettings(
        projectId: String,
        title: String,
        synopsis: String,
        workflowPath: String,
        providerId: String,
        aspectRatio: String,
        defaultValues: Map<String, String>,
        targetDurationSeconds: Int? = null,
        budget: Double? = null,
        updateBudget: Boolean = budget != null,
    ): Boolean = update(projectId) { project ->
        val path = workflowPath.trim()
        val workflowChanged = project.workflowPath != path
        project.copy(
            title = title.trim().ifBlank { "Project" },
            synopsis = synopsis.trim(),
            workflowId = if (path.isBlank()) "" else path.substringAfter(':'),
            workflowPath = path,
            providerId = providerId.trim(),
            aspectRatio = aspectRatio.trim(),
            defaultValues = defaultValues,
            targetDurationSeconds = targetDurationSeconds?.takeIf { it > 0 },
            budget = if (updateBudget) budget?.takeIf { it.isFinite() && it > 0.0 } else project.budget,
            shots = if (workflowChanged) {
                // Explicit per-shot provider/model overrides are independent of the project default.
                // Only inherited fields are reset when the default workflow changes.
                project.shots.map { shot ->
                    if (shot.workflowPath.isNullOrBlank()) shot.copy(values = emptyMap()) else shot
                }
            } else {
                project.shots
            },
            updatedAt = System.currentTimeMillis(),
        )
    }

    fun addShot(projectId: String, prompt: String, cameraNotes: String? = null): ProjectShot? =
        synchronized(lock) {
            var created: ProjectShot? = null
            val persisted = updateLocked(projectId) { project ->
                val shot = ProjectShot(prompt = prompt.trim(), cameraNotes = cameraNotes?.trim()?.ifBlank { null })
                created = shot
                project.copy(
                    shots = rebaseAutoReferences(project.shots + shot, project.chainFrames),
                    updatedAt = System.currentTimeMillis(),
                )
            }
            created.takeIf { persisted }
        }

    /**
     * Apply an AI shot proposal in a single atomic project-index write.
     * A model response never executes a paid generation or changes existing shots.
     */
    fun addPlannedShots(projectId: String, suggested: List<ProjectShot>): Boolean =
        synchronized(lock) {
            if (suggested.isEmpty() || suggested.size > 30 ||
                suggested.any { it.prompt.isBlank() ||
                    (it.plannedDurationSeconds != null && it.plannedDurationSeconds !in 1..3600) }
            ) return@synchronized false
            var validReferences = false
            val persisted = updateLocked(projectId) { project ->
                val imageIds = project.assets.filter {
                    it.kind == com.comfyremote.app.data.reference.AssetKind.IMAGE
                }.mapTo(hashSetOf()) { it.id }
                if (suggested.any { shot ->
                    shot.referenceAssetIds.size > 16 ||
                        shot.referenceAssetIds.distinct().size != shot.referenceAssetIds.size ||
                        shot.referenceAssetIds.any { it !in imageIds }
                }) return@updateLocked project
                validReferences = true
                project.copy(
                    // Only safe creative fields cross from AI proposals. Keep automatic
                    // previous-shot tail-frame relay identical to normal addShot().
                    shots = rebaseAutoReferences(
                        project.shots + suggested.map { shot ->
                            ProjectShot(
                                prompt = shot.prompt.trim(),
                                cameraNotes = shot.cameraNotes?.trim()?.ifBlank { null },
                                plannedDurationSeconds = shot.plannedDurationSeconds,
                                referenceAssetIds = shot.referenceAssetIds,
                            )
                        },
                        project.chainFrames,
                    ),
                    updatedAt = System.currentTimeMillis(),
                )
            }
            validReferences && persisted
        }

    fun insertShotAfter(projectId: String, shotId: String, prompt: String, cameraNotes: String? = null): ProjectShot? =
        synchronized(lock) {
            var created: ProjectShot? = null
            val persisted = updateLocked(projectId) { project ->
                val index = project.shots.indexOfFirst { it.id == shotId }
                if (index < 0) return@updateLocked project
                val shot = ProjectShot(prompt = prompt.trim(), cameraNotes = cameraNotes?.trim()?.ifBlank { null })
                created = shot
                val shots = project.shots.toMutableList().apply { add(index + 1, shot) }
                project.copy(
                    shots = rebaseAutoReferences(shots, project.chainFrames),
                    updatedAt = System.currentTimeMillis(),
                )
            }
            created.takeIf { persisted }
        }

    /** Duplicates a shot's creative setup but never its generation history. */
    fun duplicateShot(projectId: String, shotId: String): ProjectShot? = synchronized(lock) {
        var created: ProjectShot? = null
        val persisted = updateLocked(projectId) { project ->
            val index = project.shots.indexOfFirst { it.id == shotId }
            if (index < 0) return@updateLocked project
            val copy = project.shots[index].copy(
                id = UUID.randomUUID().toString(),
                status = "draft",
                taskId = null,
                resultPath = null,
                lastFramePath = null,
                tailFrameStored = false,
                error = null,
                takes = emptyList(),
                selectedTakeId = null,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
            )
            created = copy
            val shots = project.shots.toMutableList().apply { add(index + 1, copy) }
            project.copy(
                shots = rebaseAutoReferences(shots, project.chainFrames),
                updatedAt = System.currentTimeMillis(),
            )
        }
        created.takeIf { persisted }
    }

    fun moveShot(projectId: String, shotId: String, delta: Int): Boolean = synchronized(lock) {
        var moved = false
        val persisted = updateLocked(projectId) { project ->
            val index = project.shots.indexOfFirst { it.id == shotId }
            val target = index + delta
            if (index < 0 || target !in project.shots.indices) return@updateLocked project
            val shots = project.shots.toMutableList()
            val shot = shots.removeAt(index)
            shots.add(target, shot)
            moved = true
            project.copy(
                shots = rebaseAutoReferences(shots, project.chainFrames),
                updatedAt = System.currentTimeMillis(),
            )
        }
        moved && persisted
    }

    /** Adds or replaces an asset by id (tail frames reuse one stable asset id per shot). */
    fun addAsset(projectId: String, asset: ProjectAsset): Boolean = synchronized(lock) {
        var added = false
        val persisted = update(projectId) { project ->
            added = true
            val existing = project.assets.any { it.id == asset.id }
            val assets = if (existing) {
                project.assets.map { if (it.id == asset.id) asset else it }
            } else {
                project.assets + asset
            }
            project.copy(assets = assets, updatedAt = System.currentTimeMillis())
        }
        added && persisted
    }

    fun removeAsset(projectId: String, assetId: String): Boolean = synchronized(lock) {
        var removed = false
        val persisted = update(projectId) { project ->
            val asset = project.assets.firstOrNull { it.id == assetId }
                ?: return@update project
            removed = true
            val remaining = project.assets.filterNot { it.id == assetId }
            val profiles = project.profiles.map { profile ->
                if (assetId in profile.assetIds) {
                    profile.copy(assetIds = profile.assetIds - assetId)
                } else {
                    profile
                }
            }
            val cleaned = project.shots.map { shot ->
                val referencedById = assetId in shot.referenceAssetIds
                val referencedByPath = shot.referencePath == asset.path
                // A tail frame asset feeds the next shot's automatic reference by path,
                // not by id. Removing it from the asset strip must not break the
                // relay or turn the automatic reference into a manual empty one.
                val referencedByAutoChain = referencedByPath &&
                    !shot.referenceManual && shot.referenceAssetIds.isEmpty()
                if (!referencedById && (!referencedByPath || referencedByAutoChain)) return@map shot

                val ids = shot.referenceAssetIds - assetId
                val nextAsset = ids.firstNotNullOfOrNull { id ->
                    remaining.firstOrNull { it.id == id }
                }
                shot.copy(
                    referenceAssetIds = ids,
                    referencePath = when {
                        !referencedByPath -> shot.referencePath
                        nextAsset != null -> nextAsset.path
                        else -> null
                    },
                    referenceRole = if (referencedByPath && nextAsset != null) {
                        nextAsset.role
                    } else {
                        shot.referenceRole
                    },
                    // Removing an explicit asset must not silently fall back to an auto frame.
                    referenceManual = true,
                )
            }
            project.copy(
                assets = remaining,
                profiles = profiles,
                stageMediaBindings = project.stageMediaBindings.filterNot { it.assetId == assetId },
                shots = rebaseAutoReferences(cleaned, project.chainFrames),
                updatedAt = System.currentTimeMillis(),
            )
        }
        if (persisted && removed) {
            // Never unlink arbitrary paths: assets can also be used by History or legacy projects.
            // Only the app-owned project media GC may reclaim expired, unreferenced files.
            gcOrphanMediaLocked()
        }
        removed && persisted
    }

    /** Adds or replaces a consistency profile by id; assets themselves are untouched. */
    fun addProfile(projectId: String, profile: AssetProfile): Boolean = synchronized(lock) {
        var saved = false
        val persisted = update(projectId) { project ->
            saved = true
            val exists = project.profiles.any { it.id == profile.id }
            val profiles = if (exists) {
                project.profiles.map { if (it.id == profile.id) profile else it }
            } else {
                project.profiles + profile
            }
            project.copy(profiles = profiles, updatedAt = System.currentTimeMillis())
        }
        saved && persisted
    }

    /** Deletes a profile only; assets referenced by it stay in the project. */
    fun deleteProfile(projectId: String, profileId: String): Boolean = synchronized(lock) {
        var removed = false
        val persisted = update(projectId) { project ->
            if (project.profiles.none { it.id == profileId }) return@update project
            removed = true
            project.copy(
                profiles = project.profiles.filterNot { it.id == profileId },
                updatedAt = System.currentTimeMillis(),
            )
        }
        removed && persisted
    }

    fun updateProfile(
        projectId: String,
        profileId: String,
        transform: (AssetProfile) -> AssetProfile,
    ): Boolean = synchronized(lock) {
        var changed = false
        val persisted = update(projectId) { project ->
            val profiles = project.profiles.map { profile ->
                if (profile.id == profileId) {
                    changed = true
                    transform(profile)
                } else {
                    profile
                }
            }
            if (!changed) return@update project
            project.copy(profiles = profiles, updatedAt = System.currentTimeMillis())
        }
        changed && persisted
    }

    fun updateShot(projectId: String, shotId: String, transform: (ProjectShot) -> ProjectShot): Boolean =
        synchronized(lock) {
            updateLocked(projectId) { project ->
                project.copy(
                    shots = project.shots.map { if (it.id == shotId) transform(it) else it },
                    updatedAt = System.currentTimeMillis(),
                )
            }
        }

    fun deleteShot(projectId: String, shotId: String): Boolean = synchronized(lock) {
        var deleted = false
        val persisted = updateLocked(projectId) { project ->
            val index = project.shots.indexOfFirst { it.id == shotId }
            if (index < 0) return@updateLocked project
            if (project.shots[index].status == "running") return@updateLocked project
            deleted = true
            val remaining = project.shots.toMutableList().apply { removeAt(index) }
            project.copy(
                shots = rebaseAutoReferences(remaining, project.chainFrames),
                updatedAt = System.currentTimeMillis(),
            )
        }
        if (persisted && deleted) gcOrphanMediaLocked()
        deleted && persisted
    }

    /**
     * Marks a shot as running for [taskId] and records a fresh take snapshot.
     * A shot already running for another task is never reassigned here.
     */
    fun attachTask(projectId: String, shotId: String, taskId: String, takeSeed: ShotTake? = null): Boolean =
        synchronized(lock) {
            var attached = false
            val persisted = updateLocked(projectId) { project ->
                val shots = project.shots.map { shot ->
                    if (shot.id != shotId) return@map shot
                    if (shot.status == "running" && !shot.taskId.isNullOrBlank()) return@map shot
                    attached = true
                    val take = (takeSeed ?: ShotTake(
                        providerId = shot.providerId.orEmpty().ifBlank { project.providerId },
                        workflowPath = shot.workflowPath.orEmpty().ifBlank { project.workflowPath },
                        prompt = shot.prompt,
                        values = shot.values,
                        referenceAssetIds = shot.referenceAssetIds,
                    )).copy(
                        prompt = takeSeed?.prompt?.ifBlank { shot.prompt } ?: shot.prompt,
                        referenceAssetIds = takeSeed?.referenceAssetIds?.takeIf { it.isNotEmpty() }
                            ?: shot.referenceAssetIds,
                        id = UUID.randomUUID().toString(),
                        status = "running",
                        taskId = taskId,
                        resultPath = null,
                        error = null,
                        createdAt = System.currentTimeMillis(),
                    )
                    shot.copy(
                        status = "running",
                        taskId = taskId,
                        error = null,
                        resultPath = null,
                        takes = shot.takes + take,
                        selectedTakeId = take.id,
                    )
                }
                project.copy(shots = shots, updatedAt = System.currentTimeMillis())
            }
            attached && persisted
        }

    /**
     * Link an existing History-owned video to a shot as a *candidate*, not as the
     * primary take. It must never interrupt an active remote task or silently
     * replace a selected take or a previously arranged timeline clip.
     *
     * The underlying HistoryStore's referenced-path guard keeps the media alive
     * while this Take refers to it. Repeated actions are idempotent by media path.
     */
    fun attachExistingTake(projectId: String, shotId: String, source: ShotTake): Boolean =
        synchronized(lock) {
            val path = source.resultPath
            if (source.status != "done" || path.isNullOrBlank()) return@synchronized false
            var added = false
            val persisted = updateLocked(projectId) { project ->
                val shots = project.shots.map { shot ->
                    if (shot.id != shotId || shot.status == "running" ||
                        shot.takes.any { it.resultPath == path }) return@map shot
                    added = true
                    shot.copy(takes = shot.takes + source.copy(
                        id = UUID.randomUUID().toString(),
                        status = "done",
                        resultPath = path,
                        createdAt = System.currentTimeMillis(),
                    ))
                }
                project.copy(shots = shots, updatedAt = System.currentTimeMillis())
            }
            added && persisted
        }

    /** Marks the shot done and closes the matching running take with the result. */
    fun markDone(projectId: String, shotId: String, resultPath: String, lastFramePath: String?): Boolean =
        synchronized(lock) {
            var finished = false
            val persisted = updateLocked(projectId) { project ->
                val shots = project.shots.map { shot ->
                    if (shot.id != shotId || shot.status != "running" || shot.taskId.isNullOrBlank() ||
                        shot.takes.none { it.status == "running" && it.taskId == shot.taskId }
                    ) shot
                    else {
                        finished = true
                        val takes = shot.takes.map { take ->
                            if (take.status == "running" && take.taskId != null && take.taskId == shot.taskId) {
                                take.copy(status = "done", resultPath = resultPath, error = null)
                            } else {
                                take
                            }
                        }
                        shot.copy(
                            status = "done",
                            resultPath = resultPath,
                            lastFramePath = lastFramePath ?: shot.lastFramePath,
                            // A newly extracted frame still needs to enter assets/results.
                            tailFrameStored = if (lastFramePath != null) false else shot.tailFrameStored,
                            error = null,
                            takes = takes,
                            selectedTakeId = shot.selectedTakeId
                                ?: takes.lastOrNull { it.status == "done" }?.id,
                        )
                    }
                }
                project.copy(
                    shots = rebaseAutoReferences(shots, project.chainFrames),
                    updatedAt = System.currentTimeMillis(),
                )
            }
            finished && persisted
        }

    fun markFailed(projectId: String, shotId: String, message: String): Boolean =
        updateShot(projectId, shotId) { shot ->
            val takes = shot.takes.map { take ->
                if (take.status == "running" && take.taskId != null && take.taskId == shot.taskId) {
                    take.copy(status = "failed", error = message)
                } else {
                    take
                }
            }
            shot.copy(status = "failed", error = message, takes = takes)
        }

    /** Returns a cancelled shot to draft and closes its take as cancelled. */
    fun markTaskCancelled(projectId: String, shotId: String, taskId: String): Boolean {
        var cancelled = false
        val persisted = synchronized(lock) {
            updateLocked(projectId) { project ->
                val shots = project.shots.map { shot ->
                    // A stale cancel for an older attempt must not touch the current state.
                    if (shot.id != shotId || shot.taskId != taskId) return@map shot
                    cancelled = true
                    val takes = shot.takes.map { take ->
                        if (take.status == "running" && take.taskId == taskId) {
                            take.copy(status = "cancelled")
                        } else {
                            take
                        }
                    }
                    shot.copy(status = "draft", taskId = null, takes = takes)
                }
                project.copy(shots = shots, updatedAt = System.currentTimeMillis())
            }
        }
        return cancelled && persisted
    }

    /**
     * Promotes a finished take to the shot's primary version: its result becomes
     * the live result. Only done takes can be selected.
     */
    fun selectTake(projectId: String, shotId: String, takeId: String): Boolean =
        synchronized(lock) {
            var selected = false
            val persisted = updateLocked(projectId) { project ->
                val shots = project.shots.map { shot ->
                    if (shot.id != shotId || shot.status == "running") return@map shot
                    val take = shot.takes.firstOrNull { it.id == takeId } ?: return@map shot
                    if (take.status != "done" || take.resultPath.isNullOrBlank()) return@map shot
                    selected = true
                    shot.copy(
                        status = "done",
                        resultPath = take.resultPath,
                        taskId = null,
                        lastFramePath = null,
                        // The promoted result needs its own tail-frame artifacts.
                        tailFrameStored = false,
                        error = null,
                        selectedTakeId = take.id,
                    )
                }
                project.copy(shots = rebaseAutoReferences(shots, project.chainFrames), updatedAt = System.currentTimeMillis())
            }
            selected && persisted
        }

    fun setTakeFavorite(projectId: String, shotId: String, takeId: String, favorite: Boolean): Boolean =
        transformTake(projectId, shotId, takeId) { it.copy(favorite = favorite) }

    /** Marks a take discarded; the record and its files stay untouched. */
    fun discardTake(projectId: String, shotId: String, takeId: String): Boolean =
        transformTake(projectId, shotId, takeId) { take ->
            if (take.status == "done") take.copy(status = "discarded") else take
        }

    /**
     * Deletes a take record entirely. If it was the primary version the shot
     * falls back to draft; the result file is deleted only once nothing else
     * references it.
     */
    fun deleteTake(projectId: String, shotId: String, takeId: String): Boolean =
        synchronized(lock) {
            var removed = false
            val persisted = updateLocked(projectId) { project ->
                val shots = project.shots.map { shot ->
                    if (shot.id != shotId) return@map shot
                    val take = shot.takes.firstOrNull { it.id == takeId } ?: return@map shot
                    // Never discard the active paid task's recovery identity.
                    if (shot.status == "running" || take.status == "running") return@map shot
                    removed = true
                    val takes = shot.takes.filterNot { it.id == takeId }
                    val wasSelected = shot.selectedTakeId == takeId
                    val fallback = takes.lastOrNull { it.status == "done" && !it.resultPath.isNullOrBlank() }
                    when {
                        // Promote the newest remaining done take so the shot keeps its result.
                        wasSelected && fallback != null -> shot.copy(
                            status = "done",
                            resultPath = fallback.resultPath,
                            selectedTakeId = fallback.id,
                            taskId = null,
                            lastFramePath = null,
                            tailFrameStored = false,
                            takes = takes,
                        )
                        // Primary version gone with no replacement: back to draft.
                        wasSelected -> shot.copy(
                            status = "draft",
                            taskId = null,
                            resultPath = null,
                            selectedTakeId = null,
                            lastFramePath = null,
                            tailFrameStored = false,
                            takes = takes,
                        )
                        else -> shot.copy(takes = takes)
                    }
                }
                project.copy(shots = rebaseAutoReferences(shots, project.chainFrames), updatedAt = System.currentTimeMillis())
            }
            if (persisted && removed) {
                // Keep History-owned media intact. Only expired project-owned files may be GCed.
                gcOrphanMediaLocked()
            }
            removed && persisted
        }

    private fun transformTake(
        projectId: String,
        shotId: String,
        takeId: String,
        transform: (ShotTake) -> ShotTake,
    ): Boolean = synchronized(lock) {
        var changed = false
        val persisted = updateLocked(projectId) { project ->
            val shots = project.shots.map { shot ->
                if (shot.id != shotId) return@map shot
                val takes = shot.takes.map { take ->
                    if (take.id == takeId) {
                        changed = true
                        transform(take)
                    } else {
                        take
                    }
                }
                shot.copy(takes = takes)
            }
            if (!changed) return@updateLocked project
            project.copy(shots = shots, updatedAt = System.currentTimeMillis())
        }
        changed && persisted
    }

    fun setReference(
        projectId: String,
        shotId: String,
        path: String?,
        manual: Boolean = true,
    ): Boolean = updateShot(projectId, shotId) {
        it.copy(referencePath = path, referenceManual = manual)
    }

    /** Creates the empty timeline once; a no-op when one already exists. */
    fun ensureTimeline(projectId: String): Boolean =
        updateTimeline(projectId) { timeline -> timeline ?: Timeline() }

    fun addClip(projectId: String, kind: TrackKind, clip: TimelineClip): Boolean =
        updateTimeline(projectId) { timeline ->
            (timeline ?: Timeline()).withTrack(kind) { it + clip }
        }

    fun insertClipAt(projectId: String, kind: TrackKind, index: Int, clip: TimelineClip): Boolean =
        updateTimeline(projectId) { timeline ->
            (timeline ?: Timeline()).withTrack(kind) { track ->
                val target = index.coerceIn(0, track.size)
                track.toMutableList().apply { add(target, clip) }
            }
        }

    fun moveClip(projectId: String, kind: TrackKind, clipId: String, delta: Int): Boolean =
        updateTimeline(projectId) { timeline ->
            timeline?.withTrack(kind) { track ->
                val index = track.indexOfFirst { it.id == clipId }
                val target = index + delta
                if (index < 0 || target !in track.indices) return@withTrack track
                track.toMutableList().apply {
                    add(target, removeAt(index))
                }
            }
        }

    /** Removes a clip record only; the referenced media file is never touched here. */
    fun removeClip(projectId: String, kind: TrackKind, clipId: String): Boolean =
        updateTimeline(projectId) { timeline ->
            timeline?.withTrack(kind) { track -> track.filterNot { it.id == clipId } }
        }

    fun updateClip(
        projectId: String,
        kind: TrackKind,
        clipId: String,
        transform: (TimelineClip) -> TimelineClip,
    ): Boolean = updateTimeline(projectId) { timeline ->
        timeline?.withTrack(kind) { track ->
            track.map { if (it.id == clipId) transform(it) else it }
        }
    } ?: false

    // Reference identity, not data equality: track transforms return the same
    // instance when nothing changed, so updatedAt-only churn stays a no-op.
    private fun updateTimeline(
        projectId: String,
        transform: (Timeline?) -> Timeline?,
    ): Boolean = synchronized(lock) {
        var changed = false
        val persisted = updateLocked(projectId) { project ->
            val next = transform(project.timeline)
            if (next === project.timeline) return@updateLocked project
            changed = true
            project.copy(timeline = next, updatedAt = System.currentTimeMillis())
        }
        changed && persisted
    }

    private fun Timeline.withTrack(kind: TrackKind, transform: (List<TimelineClip>) -> List<TimelineClip>): Timeline {
        val current = if (kind == TrackKind.VIDEO) videoTrack else audioTrack
        val next = transform(current)
        if (next == current) return this
        return if (kind == TrackKind.VIDEO) {
            copy(videoTrack = next, updatedAt = System.currentTimeMillis())
        } else {
            copy(audioTrack = next, updatedAt = System.currentTimeMillis())
        }
    }

    fun nextShot(projectId: String): ProjectShot? {
        val project = _projects.value.firstOrNull { it.id == projectId } ?: return null
        // Do not skip over a running shot: its last frame feeds the next reference.
        val firstPending = project.shots.firstOrNull { it.status != "done" } ?: return null
        return firstPending.takeIf { it.status == "draft" || it.status == "failed" }
    }

    fun project(projectId: String): VideoProject? = _projects.value.firstOrNull { it.id == projectId }

    /** Media History must never prune while a project still references it. */
    fun referencedMediaPaths(): Set<String> {
        val shotMedia = _projects.value.asSequence()
            .flatMap { it.shots.asSequence() }
            .flatMap { shot ->
                listOfNotNull(shot.resultPath, shot.lastFramePath, shot.referencePath).asSequence() +
                    shot.takes.asSequence().mapNotNull { it.resultPath }
            }
        val assets = _projects.value.asSequence()
            .flatMap { it.assets.asSequence() }
            .map { it.path }
        // Timeline clips reference media by path, so any clip source keeps its
        // file alive even after the shot/take/asset that produced it is gone.
        val timelineMedia = _projects.value.asSequence()
            .mapNotNull { it.timeline }
            .flatMap { it.videoTrack.asSequence() + it.audioTrack.asSequence() }
            .map { it.sourcePath }
        return (shotMedia + assets + timelineMedia).filter { it.isNotBlank() }.toSet()
    }

    /**
     * Rebuilds automatic frame inheritance in timeline order. Manual references and
     * project-asset references always win; the most recent preceding last frame is
     * carried forward until a shot produces a newer one.
     */
    private fun rebaseAutoReferences(
        shots: List<ProjectShot>,
        chainFrames: Boolean,
    ): List<ProjectShot> {
        if (!chainFrames) {
            var cleared = false
            val result = shots.map { shot ->
                if (!shot.referenceManual && shot.referenceAssetIds.isEmpty() &&
                    shot.referencePath != null
                ) {
                    cleared = true
                    shot.copy(referencePath = null)
                } else {
                    shot
                }
            }
            return if (cleared) result else shots
        }
        var inherited: String? = null
        var changed = false
        val result = shots.map { shot ->
            val next = if (!shot.referenceManual && shot.referenceAssetIds.isEmpty() &&
                shot.referencePath != inherited
            ) {
                changed = true
                shot.copy(referencePath = inherited)
            } else {
                shot
            }
            if (shot.lastFramePath != null) inherited = shot.lastFramePath
            next
        }
        return if (changed) result else shots
    }

    private fun update(projectId: String, transform: (VideoProject) -> VideoProject): Boolean =
        synchronized(lock) { updateLocked(projectId, transform) }

    private fun updateLocked(
        projectId: String,
        transform: (VideoProject) -> VideoProject,
    ): Boolean {
        val next = _projects.value.map { if (it.id == projectId) transform(it) else it }
        return write(next)
    }

    /** Atomic write first, then publish the in-memory project list. */
    private fun write(projects: List<VideoProject>): Boolean {
        val sorted = projects.sortedByDescending { it.updatedAt }
        val parent = file.parentFile
        if (parent != null && !parent.isDirectory && !parent.mkdirs()) {
            _persistenceError.value = "Cannot create project directory: $parent"
            return false
        }
        val tmp = File(parent, file.name + ".tmp")
        return try {
            // decode() intentionally tolerates damaged files for read-only UI;
            // writes must not overwrite that damage and erase recoverable work.
            if (file.exists() && !file.isFile) {
                throw IOException("Existing project index is not a regular file")
            }
            val existing = if (file.isFile) file.readText() else null
            if (existing != null) {
                val rows = ProjectCodec.rawProjectCount(existing)
                    ?: throw IOException("Existing project index is not valid JSON")
                if (ProjectCodec.decode(existing).size != rows) {
                    throw IOException("Existing project index contains unreadable projects")
                }
                // Snapshot validated, previously committed bytes before replacement.
                // Failure to secure the old index prevents the new write.
                ProjectIndexBackups(file).snapshot(existing)
            }
            val encoded = ProjectCodec.encode(sorted)
            FileOutputStream(tmp).use { stream ->
                stream.write(encoded.toByteArray(Charsets.UTF_8))
                stream.fd.sync()
            }
            try {
                java.nio.file.Files.move(
                    tmp.toPath(),
                    file.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (unsupported: java.nio.file.AtomicMoveNotSupportedException) {
                java.nio.file.Files.move(
                    tmp.toPath(),
                    file.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                )
            }
            _projects.value = sorted
            // Guarantee a first recovery point even after the first new-project
            // creation (where there was no previous index to snapshot).
            if (existing == null) {
                runCatching { ProjectIndexBackups(file).snapshot(encoded) }
                    .onFailure { warning ->
                        _persistenceError.value = "Project saved; initial backup failed: " +
                            (warning.message ?: warning.javaClass.simpleName)
                    }
            }
            true
        } catch (error: Exception) {
            tmp.delete()
            _persistenceError.value = error.message ?: error.javaClass.simpleName
            false
        }
    }

    /** Deletes app-owned project media that no shot or asset references anymore. */
    private fun gcOrphanMediaLocked() {
        val mediaDir = File(file.parentFile, "storyboards")
        // The old storyboard index remains a rollback anchor after migration.
        // Protect its media too; otherwise deleting a migrated project can remove
        // files still needed when a user installs the old APK.
        val legacyFile = File(file.parentFile, "storyboards.json")
        val legacyRefs = if (legacyFile.isFile) {
            val raw = runCatching { legacyFile.readText() }.getOrNull() ?: return
            val projects = StoryboardCodec.decode(raw)
            val rows = runCatching { JSONArray(raw).length() }.getOrNull()
            if (rows == null || rows != projects.size) return // damaged anchor: never GC blindly
            projects.flatMap { legacy ->
                legacy.assets.map { it.path } + legacy.shots.flatMap { shot ->
                    listOfNotNull(shot.referencePath, shot.resultPath, shot.lastFramePath)
                }
            }.toSet()
        } else emptySet()
        val referenced = referencedMediaPaths() + legacyRefs
        val cutoff = System.currentTimeMillis() - MEDIA_GC_GRACE_MS
        mediaDir.walkTopDown().filter { it.isFile }.forEach { candidate ->
            if (candidate.absolutePath !in referenced && candidate.lastModified() < cutoff) {
                runCatching { candidate.delete() }
            }
        }
    }

    companion object {
        private const val MEDIA_GC_GRACE_MS = 10 * 60_000L
    }
}
