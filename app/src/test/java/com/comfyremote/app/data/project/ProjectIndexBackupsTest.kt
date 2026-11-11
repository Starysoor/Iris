package com.comfyremote.app.data.project

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProjectIndexBackupsTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun firstCreationAndSubsequentWritesProvideRotatingSnapshots() {
        val file = File(temp.root, "projects.json")
        val store = ProjectStore(file)
        assertNotNull(store.create("first", ""))
        assertEquals(1, store.availableBackups().size)
        repeat(5) { i -> assertNotNull(store.create("next-$i", "")) }
        val snapshots = store.availableBackups()
        assertEquals(3, snapshots.size)
        assertTrue(snapshots.all { it.projectCount > 0 && it.filename.startsWith("projects.backup-") })
    }

    @Test fun corruptIndexCanBeRestoredWithoutDeletingOriginalBytesOrMedia() {
        val file = File(temp.root, "projects.json")
        val store = ProjectStore(file)
        val one = store.create("first", "")!!
        store.create("second", "")
        val candidates = store.availableBackups()
        assertTrue(candidates.isNotEmpty())
        val backup = candidates.last()
        file.writeText("{damaged-index")
        val recovered = ProjectStore(file)
        assertNotNull(recovered.loadError.value)
        assertFalse(recovered.restoreBackup("../projects.json"))
        assertEquals("{damaged-index", file.readText())
        assertTrue(recovered.restoreBackup(backup.filename))
        assertEquals(null, recovered.loadError.value)
        assertTrue(recovered.projects.value.any { it.id == one.id })
        assertTrue(temp.root.listFiles()!!.any {
            it.name.startsWith("projects.before-restore-") && it.readText() == "{damaged-index"
        })
        assertNotNull(recovered.create("new-after-recovery", ""))
        assertTrue(ProjectStore(file).projects.value.any { it.title == "new-after-recovery" })
    }

    @Test fun invalidOrPartiallyDecodableBackupIsNotOfferedForRestore() {
        val file = File(temp.root, "projects.json")
        val store = ProjectStore(file)
        store.create("first", "")
        val validCount = store.availableBackups().size
        File(temp.root, "projects.backup-1111111111111-000.json")
            .writeText("""{"version":2,"projects":[{"title":"missing id"}]}""")
        assertEquals(validCount, store.availableBackups().size)
        assertFalse(store.restoreBackup("projects.backup-1111111111111-000.json"))
    }
}
