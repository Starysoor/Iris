package com.comfyremote.app.data.repository

import com.comfyremote.app.ComfyApi
import com.comfyremote.app.ConvertedWorkflow
import com.comfyremote.app.WorkflowConverter
import com.comfyremote.app.WorkflowEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlin.coroutines.cancellation.CancellationException

/** Loads workflows from the server and converts them via the object_info cache. */
class WorkflowRepository(
    private val session: ServerSessionRepository,
    private val objectInfoCache: ObjectInfoCache,
) {

    suspend fun list(): List<WorkflowEntry> = session.require().listWorkflows()

    suspend fun convert(entry: WorkflowEntry): ConvertedWorkflow {
        val a = session.require()
        val ref = LocalWorkflowReference.parse(entry.path)
        if (ref != null && ref.serverUrl != a.base) {
            throw com.comfyremote.app.ComfyException("Connect to ${ref.serverUrl} to use this ComfyUI workflow")
        }
        val version = session.version
        val json = a.fetchWorkflow(ref?.workflowPath ?: entry.path)
        val first = WorkflowConverter.convert(json, objectInfoCache.get(a, version))
        return if (first.blockingProblems.any { "服务器缺少节点" in it }) {
            WorkflowConverter.convert(json, objectInfoCache.get(a, version, force = true))
        } else first
    }

    internal suspend fun studioWorkflows(): List<Pair<WorkflowEntry, ConvertedWorkflow>> = coroutineScope {
        val api = session.require()
        val info = objectInfoCache.get(api, session.version)
        api.listWorkflows().chunked(4).flatMap { chunk ->
            chunk.map { entry ->
                async(Dispatchers.IO) {
                    try {
                        val converted = WorkflowConverter.convert(api.fetchWorkflow(entry.path), info)
                        val key = LocalWorkflowReference(api.base, entry.path).key()
                        entry.copy(path = key) to converted
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        null
                    }
                }
            }.awaitAll().filterNotNull()
        }
    }
}
