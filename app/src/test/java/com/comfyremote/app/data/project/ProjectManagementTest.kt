package com.comfyremote.app.data.project

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProjectManagementTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun archivePersistsAndFilterDoesNotRemoveProjects() {
        val file = File(temp.root, "projects.json")
        val store = ProjectStore(file)
        store.create("Current", "")!!
        val archived = store.create("Archived", "")!!
        assertTrue(store.setArchived(archived.id, true))
        val loaded = ProjectStore(file).projects.value
        assertEquals(2, loaded.size)
        assertEquals(listOf("Current"), ProjectCatalogFilter.filter(loaded, ProjectCatalogFilter.Mode.CURRENT).map { it.title })
        assertEquals(listOf("Archived"), ProjectCatalogFilter.filter(loaded, ProjectCatalogFilter.Mode.ARCHIVED).map { it.title })
        assertTrue(store.setArchived(archived.id, false))
        assertEquals(2, ProjectStore(file).projects.value.size)
    }

    @Test fun duplicatePlanNeverCopiesPaidTaskOrCompletedOutputs() {
        val file = File(temp.root, "projects.json")
        val store = ProjectStore(file)
        val source = store.create("Film", "mock")!!
        val shot = store.addShot(source.id, "Opening")!!
        assertTrue(store.attachTask(source.id, shot.id, "remote-job"))
        val copy = store.duplicateProject(source.id)!!
        assertNotEquals(source.id, copy.id)
        assertEquals(1, copy.shots.size)
        val cloned = copy.shots.single()
        assertNotEquals(shot.id, cloned.id)
        assertEquals("Opening", cloned.prompt)
        assertEquals("draft", cloned.status)
        assertNull(cloned.taskId)
        assertNull(cloned.resultPath)
        assertNull(cloned.selectedTakeId)
        assertTrue(cloned.takes.isEmpty())
        assertNull(copy.timeline)
        assertFalse(copy.archived)
        assertEquals("running", store.project(source.id)!!.shots.single().status)
        assertNotNull(ProjectStore(file).project(copy.id))
    }

    @Test fun completedFilterDoesNotTreatArchivedOrEmptyShotsAsCompleted() {
        val done = VideoProject(id = "done", title = "done", workflowId = "",
            shots = listOf(ProjectShot(status = "done")))
        val running = done.copy(id = "running", shots = listOf(ProjectShot(status = "running")))
        val archived = done.copy(id = "archived", archived = true)
        val empty = done.copy(id = "empty", shots = emptyList())
        assertEquals(
            listOf("done"),
            ProjectCatalogFilter.filter(listOf(done, running, archived, empty),
                ProjectCatalogFilter.Mode.COMPLETED).map { it.id },
        )
    }
}
