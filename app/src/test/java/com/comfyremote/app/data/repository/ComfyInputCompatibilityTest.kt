package com.comfyremote.app.data.repository

import com.comfyremote.app.ComfyApi
import com.comfyremote.app.PromptValidationException
import com.comfyremote.app.invalidPromptImageNames
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ComfyInputCompatibilityTest {
    @get:Rule val cacheDirectory = TemporaryFolder()

    @Test
    fun missingTemplateImageCanBeIdentifiedBeforeQueueing() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(404))
            server.enqueue(MockResponse().setResponseCode(405))
            server.enqueue(MockResponse().setResponseCode(206))
            val api = ComfyApi(server.url("/").toString())

            assertFalse(api.inputImageExists("templates/cat.webp"))
            val missing = server.takeRequest()
            assertEquals("HEAD", missing.method)
            assertEquals("/view", missing.requestUrl?.encodedPath)
            assertEquals("cat.webp", missing.requestUrl?.queryParameter("filename"))
            assertEquals("templates", missing.requestUrl?.queryParameter("subfolder"))
            assertEquals("input", missing.requestUrl?.queryParameter("type"))

            assertTrue(api.inputImageExists("renders/cat.webp [output]"))
            assertEquals("HEAD", server.takeRequest().method)
            val fallback = server.takeRequest()
            assertEquals("GET", fallback.method)
            assertEquals("output", fallback.requestUrl?.queryParameter("type"))
            assertEquals("bytes=0-0", fallback.getHeader("Range"))
        }
    }

    @Test
    fun explicitRefreshGetsNewModelOptionsWithoutAComfyUiVersionChange() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"Loader":{"inputs":["old.safetensors"]}}"""))
            server.enqueue(MockResponse().setBody("""{"Loader":{"inputs":["new.safetensors"]}}"""))
            val api = ComfyApi(server.url("/").toString())
            val cache = ObjectInfoCache(cacheDirectory.root)

            assertEquals(cache.get(api, "0.5.0"), cache.get(api, "0.5.0"))
            assertEquals(1, server.requestCount)
            cache.invalidate()
            val refreshed = cache.get(api, "0.5.0")
            assertEquals("new.safetensors", refreshed.getJSONObject("Loader").getJSONArray("inputs").getString(0))
            assertEquals(2, server.requestCount)
        }
    }

    @Test
    fun invalidImageFromServerValidationCanBeMappedBackToTheSelectedFilename() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(400).setBody("""
                {
                  "error":{"message":"Prompt outputs failed validation"},
                  "node_errors":{"26":{"class_type":"LoadImage","errors":[{
                    "message":"Custom validation failed for node",
                    "details":"image - Invalid image file: templates/cat (2).webp"
                  }]}}
                }
            """.trimIndent()))
            val api = ComfyApi(server.url("/").toString())

            val failure = runCatching { api.queuePrompt(JSONObject(), "test") }.exceptionOrNull()
            assertTrue(failure is PromptValidationException)
            assertEquals(setOf("templates/cat (2).webp"), invalidPromptImageNames(failure?.message.orEmpty()))
        }
    }
}
