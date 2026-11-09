package com.comfyremote.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class DraftTransferRepositoryTest {

    private fun transfer(path: String, reuseAcrossWorkflows: Boolean = false) = DraftTransfer(
        workflowPath = path,
        workflowName = path.substringAfterLast('/'),
        values = mapOf("prompt" to "castle in fog"),
        randomSeed = true,
        reuseAcrossWorkflows = reuseAcrossWorkflows,
    )

    @Test
    fun directTransferOnlyOpensItsOriginalWorkflow() {
        val repo = DraftTransferRepository()
        val pending = transfer("workflows/a.json")
        repo.put(pending)

        assertNull(repo.consume("workflows/b.json"))
        assertSame(pending, repo.peek())
        assertSame(pending, repo.consume("workflows/a.json"))
        assertNull(repo.peek())
    }

    @Test
    fun workflowChoiceTransferIsConsumedByThePickedWorkflow() {
        val repo = DraftTransferRepository()
        val pending = transfer("autodl:seedance", reuseAcrossWorkflows = true)
        repo.put(pending)

        assertSame(pending, repo.consume("workflows/local.json"))
        assertNull(repo.peek())
    }

    @Test
    fun handoffPreservesExactModelFieldPathsOnlyForTheTargetWorkflow() {
        val repo = DraftTransferRepository()
        val transfer = transfer("autodl:video").copy(
            referenceFieldPaths = mapOf(
                "last_frame" to "/project/end.png",
                "first_frame" to "/project/start.png",
                "audio" to "/project/voice.wav",
            ),
        )
        repo.put(transfer)
        assertNull(repo.consume("autodl:different"))
        assertEquals(transfer.referenceFieldPaths,
            repo.consume("autodl:video")!!.referenceFieldPaths)
        assertNull(repo.peek())
    }

    @Test
    fun clearDropsThePendingTransfer() {
        val repo = DraftTransferRepository()
        repo.put(transfer("autodl:seedance", reuseAcrossWorkflows = true))

        repo.clear()

        assertNull(repo.peek())
        assertNull(repo.consume("autodl:seedance"))
    }
}
