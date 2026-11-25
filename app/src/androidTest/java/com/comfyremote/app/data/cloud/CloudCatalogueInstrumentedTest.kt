package com.comfyremote.app.data.cloud

import android.content.Context
import android.os.StrictMode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.comfyremote.app.data.security.SecretStore
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CloudCatalogueInstrumentedTest {
    @Test
    fun refreshesAutoDlAndOpenAiCataloguesFromTheMainDispatcher() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefsName = "cloud-catalogue-test-${UUID.randomUUID()}"
        val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val settings = CloudProviderSettingsStore(prefs, SecretStore(prefs))
        val taskStore = CloudProviderTaskStore(context)
        val autoDl = AutoDlModelRepository(settings, taskStore)
        val openAi = OpenAiCompatibleImageRepository(settings, taskStore)

        try {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setBody(
                    """{"data":[{"id":"Qwen-Image","display_name":"Qwen Image","type":"image"}]}""",
                ))
                server.enqueue(MockResponse().setBody(
                    """{"data":[{"id":"gpt-image-2.5-sunburst","type":"image"}]}""",
                ))
                settings.save(autoDl.id, server.url("/autodl/v1").toString(), "autodl-test", listOf("Qwen-Image"))
                settings.save(openAi.id, server.url("/openai/v1").toString(), "openai-test", listOf("gpt-image-2.5-sunburst"))

                withContext(Dispatchers.Main) {
                    val previous = StrictMode.getThreadPolicy()
                    StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder(previous)
                        .detectNetwork()
                        .penaltyDeathOnNetwork()
                        .build())
                    try {
                        val autoDlModel = autoDl.catalog(force = true).single()
                        assertEquals("Qwen-Image", autoDlModel.id)
                        assertEquals("Qwen Image", autoDlModel.name)
                        assertEquals("image", autoDlModel.kind)
                        assertEquals("gpt-image-2.5-sunburst", openAi.catalog(force = true).single().id)
                    } finally {
                        StrictMode.setThreadPolicy(previous)
                    }
                }

                val autoDlRequest = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))
                val openAiRequest = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))
                assertEquals("/autodl/v1/models", autoDlRequest.path)
                assertEquals("Bearer autodl-test", autoDlRequest.getHeader("Authorization"))
                assertEquals("/openai/v1/models", openAiRequest.path)
                assertEquals("Bearer openai-test", openAiRequest.getHeader("Authorization"))
            }
        } finally {
            context.deleteSharedPreferences(prefsName)
        }
    }
}
