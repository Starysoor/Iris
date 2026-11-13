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
        assertNull(journal.interruptedProjectId())
        journal.markRunning("project-123")
        assertEquals("project-123", ProjectExportJournal(file).interruptedProjectId())
        journal.clear()
        assertNull(ProjectExportJournal(file).interruptedProjectId())
    }

    @Test fun corruptedMarkerDoesNotCrashStartupOrResumePaidWork() {
        val file = File(temp.root, "project-export-session.json")
        file.writeText("{not-json")
        val journal = ProjectExportJournal(file)
        assertNull(journal.interruptedProjectId())
        journal.clear()
        assertTrue(!file.exists())
    }
}
