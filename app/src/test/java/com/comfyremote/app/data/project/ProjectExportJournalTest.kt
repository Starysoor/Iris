package com.comfyremote.app.data.project

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProjectExportJournalTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun interruptedProjectCanBeDiagnosedAndExplicitlyRetried() {
        val file = File(temp.root, "project-export-session.json")
        val journal = ProjectExportJournal(file)
        assertNull(journal.interrupted())
        journal.markRunning("project-123", "export-456")
        assertEquals(ProjectExportJournal.Session("project-123", "export-456"), ProjectExportJournal(file).interrupted())
        journal.clear()
        assertNull(ProjectExportJournal(file).interrupted())
    }

    @Test fun corruptedMarkerDoesNotCrashStartupOrResumePaidWork() {
        val file = File(temp.root, "project-export-session.json")
        file.writeText("{not-json")
        val journal = ProjectExportJournal(file)
        assertNull(journal.interrupted())
        journal.clear()
        assertTrue(!file.exists())
    }

    @Test fun recognizesProjectsSavedByOlderVersionsOfTheJournal() {
        val file = File(temp.root, "project-export-session.json")
        file.writeText("""{"state":"running","projectId":"old"}""")
        assertEquals(ProjectExportJournal.Session("old", null), ProjectExportJournal(file).interrupted())
    }
}
