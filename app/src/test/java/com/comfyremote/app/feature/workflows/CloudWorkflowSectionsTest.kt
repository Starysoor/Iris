package com.comfyremote.app.feature.workflows

import com.comfyremote.app.WorkflowEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CloudWorkflowSectionsTest {

    @Test
    fun workflowsAreGroupedByProviderInWorkspaceOrder() {
        val providers = listOf(
            CloudProviderCard("openai", "OpenAI", configured = true, modelCount = 2),
            CloudProviderCard("autodl", "AutoDL.Art", configured = true, modelCount = 1),
        )
        val entries = listOf(
            entry("autodl:wf", "AutoDL.Art", "autodl"),
            entry("openai:gpt-image", "OpenAI", "openai"),
            entry("openai:gpt-image-2", "OpenAI", "openai"),
        )

        val sections = cloudWorkflowSections(entries, providers)

        assertEquals(listOf("openai", "autodl"), sections.map { it.providerId })
        assertEquals(2, sections.first().workflows.size)
        assertEquals(1, sections.last().workflows.size)
    }

    @Test
    fun anEntryWithoutACardStillRendersInItsOwnSection() {
        val sections = cloudWorkflowSections(
            listOf(entry("custom:model", "Custom", "custom")),
            emptyList(),
        )

        assertEquals("custom", sections.single().providerId)
        assertEquals("Custom", sections.single().providerLabel)
    }

    @Test
    fun aFailedProviderShowsItsErrorEvenWhenAnotherProviderHasModels() {
        val state = WorkflowsUiState(
            source = WorkflowSource.CLOUD,
            cloudWorkflows = listOf(entry("autodl:workflow", "AutoDL Workflows", "autodl")),
            cloudProviderCards = listOf(
                CloudProviderCard("autodl", "AutoDL Workflows", configured = true, modelCount = 1),
                CloudProviderCard("autodl-models", "AutoDL Models", configured = true, error = "HTTP 503"),
            ),
        )

        assertNull(state.cloudListError)
        val failedSelection = state.copy(selectedCloudProviderId = "autodl-models")
        assertEquals("HTTP 503", failedSelection.cloudListError)
        assertEquals(emptyList<WorkflowEntry>(), failedSelection.visible)
        val healthySelection = state.copy(selectedCloudProviderId = "autodl", cloudError = "Another provider failed")
        assertNull(healthySelection.cloudListError)
    }

    private fun entry(path: String, label: String, providerId: String) = WorkflowEntry(
        path = path,
        name = path,
        sizeKb = "",
        cloud = true,
        providerLabel = label,
        providerId = providerId,
    )
}
