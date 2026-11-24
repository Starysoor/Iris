package com.comfyremote.app.data.cloud

import android.content.SharedPreferences
import com.comfyremote.app.data.security.SecretVault
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudProviderSettingsStoreTest {

    @Test
    fun aSavedEmptyModelListNeverFallsBackToPresets() {
        val store = CloudProviderSettingsStore(MemoryPrefs(), MemoryVault())

        store.save("openai", "https://api.openai.com/v1", "key", emptyList())

        val account = store.account("openai", "https://fallback")
        assertEquals(emptyList<String>(), account.models)
        assertTrue(store.hasSavedModels("openai"))
    }

    @Test
    fun providerVisibilityCanBeTurnedOffAndCleared() {
        val store = CloudProviderSettingsStore(MemoryPrefs(), MemoryVault())
        store.save("openai", "https://api.openai.com/v1", "key", listOf("gpt-image-2.5"))

        store.setProviderEnabled("openai", false)
        assertEquals(false, store.providerEnabledOverride("openai"))

        store.clear("openai")

        assertNull(store.providerEnabledOverride("openai"))
        assertEquals("", store.account("openai", "https://api.openai.com/v1").token)
        assertFalse(store.hasSavedModels("openai"))
    }

    @Test
    fun anUpgradePreservesManuallySavedAndOldPresetModelIds() {
        val prefs = MemoryPrefs().apply {
            values["cloud_openai_models"] = "gpt-image-2.5-sunburst,my-custom-model"
            values["cloud_custom_models"] = "my-own-model"
        }

        val store = CloudProviderSettingsStore(prefs, MemoryVault())

        assertTrue(store.hasSavedModels("openai"))
        assertEquals(listOf("gpt-image-2.5-sunburst", "my-custom-model"), store.account("openai", "").models)
        assertEquals(listOf("my-own-model"), store.account("custom", "").models)
    }

    @Test
    fun modelsMatchingKnownPresetsArePreservedWhenUserSaved() {
        val prefs = MemoryPrefs().apply {
            values["cloud_siliconflow_models"] = "Kwai-Kolors/Kolors,Qwen/Qwen-Image"
        }

        val store = CloudProviderSettingsStore(prefs, MemoryVault())

        assertTrue(store.hasSavedModels("siliconflow"))
        assertEquals(listOf("Kwai-Kolors/Kolors", "Qwen/Qwen-Image"), store.account("siliconflow", "").models)
    }

    @Test
    fun upgradingFromSchemaTwoKeepsExactUserModelList() {
        val prefs = MemoryPrefs().apply {
            values["cloud_catalog_schema"] = 2
            values["cloud_gemini_models"] = "gemini-3-pro-image,gemini-my-custom-image"
        }
        val store = CloudProviderSettingsStore(prefs, MemoryVault())
        assertEquals(
            listOf("gemini-3-pro-image", "gemini-my-custom-image"),
            store.account("gemini", "").models,
        )
        assertEquals(3, prefs.getInt("cloud_catalog_schema", -1))
    }

    @Test
    fun anExplicitlySavedEmptyListSurvivesTheUpgrade() {
        val prefs = MemoryPrefs().apply {
            values["cloud_openai_models"] = ""
        }

        val store = CloudProviderSettingsStore(prefs, MemoryVault())

        assertTrue(store.hasSavedModels("openai"))
        assertEquals(emptyList<String>(), store.account("openai", "").models)
    }

    @Test
    fun discoveryIsPulledOnceAndASavedEmptyListStopsFurtherPulls() = runBlocking {
        val store = CloudProviderSettingsStore(MemoryPrefs(), MemoryVault())
        var calls = 0

        val base = "https://api.openai.com/v1"
        val first = modelDirectory(store, store.account("openai", base), force = false) {
            calls++
            listOf(CloudModelMetadata("gpt-image-2.5", "GPT Image 2.5"), CloudModelMetadata("dall-e-3"))
        }
        val second = modelDirectory(store, store.account("openai", base), force = false) {
            calls++
            error("Should use the saved catalogue")
        }

        assertEquals(listOf("gpt-image-2.5", "dall-e-3"), first.map { it.id })
        assertEquals(first, second)
        assertEquals(1, calls)

        store.save("openai", base, "token", emptyList())
        val third = modelDirectory(store, store.account("openai", base), force = true) {
            calls++
            error("An explicit empty list stays empty")
        }

        assertTrue(third.isEmpty())
        assertEquals(1, calls)
    }

    @Test
    fun refreshedDirectoryRetainsManualChoicesAndRemovesRetiredDiscoveredModels() = runBlocking {
        val store = CloudProviderSettingsStore(MemoryPrefs(), MemoryVault())
        val base = "https://provider.example/v1"
        modelDirectory(store, store.account("p", base), force = false) {
            listOf(CloudModelMetadata("retired"), CloudModelMetadata("still-there"))
        }
        store.save("p", base, "", listOf("retired", "still-there", "manual"))
        store.setModelEnabled("p", "still-there", enabled = false)
        val refreshed = modelDirectory(store, store.account("p", base), force = true) {
            listOf(CloudModelMetadata("still-there"), CloudModelMetadata("new", "New variant", "Official summary", "video"))
        }
        assertEquals(listOf("manual", "new"), refreshed.map { it.id })
        assertEquals("Official summary", refreshed.last().description)
        assertEquals("video", refreshed.last().kind)
        assertEquals(listOf("still-there", "manual", "new"), store.account("p", base).models)
    }

    private class MemoryVault : SecretVault {
        private val values = mutableMapOf<String, String>()
        override fun get(key: String): String? = values[key]
        override fun put(key: String, value: String?) {
            if (value.isNullOrBlank()) values.remove(key) else values[key] = value
        }
    }

    private class MemoryPrefs : SharedPreferences {
        val values = mutableMapOf<String, Any>()

        override fun getAll(): MutableMap<String, *> = values.toMutableMap()
        override fun getString(key: String, defValue: String?): String? = values[key] as? String ?: defValue
        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String> {
            val stored = values[key] as? Set<String>
            return when {
                stored != null -> stored.toMutableSet()
                defValues != null -> defValues.toMutableSet()
                else -> mutableSetOf()
            }
        }
        override fun getInt(key: String, defValue: Int): Int = values[key] as? Int ?: defValue
        override fun getLong(key: String, defValue: Long): Long = values[key] as? Long ?: defValue
        override fun getFloat(key: String, defValue: Float): Float = values[key] as? Float ?: defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
        override fun contains(key: String): Boolean = values.containsKey(key)
        override fun edit(): SharedPreferences.Editor = Editor()
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

        private inner class Editor : SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Any?>()
            private var clear = false

            override fun putString(key: String, value: String?): SharedPreferences.Editor = apply {
                pending[key] = value
            }

            override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor = apply {
                pending[key] = values?.toSet()
            }

            override fun putInt(key: String, value: Int): SharedPreferences.Editor = apply { pending[key] = value }
            override fun putLong(key: String, value: Long): SharedPreferences.Editor = apply { pending[key] = value }
            override fun putFloat(key: String, value: Float): SharedPreferences.Editor = apply { pending[key] = value }
            override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = apply { pending[key] = value }
            override fun remove(key: String): SharedPreferences.Editor = apply { pending[key] = null }
            override fun clear(): SharedPreferences.Editor = apply { clear = true }
            override fun commit(): Boolean {
                apply()
                return true
            }

            override fun apply() {
                if (clear) values.clear()
                pending.forEach { (key, value) ->
                    if (value == null) values.remove(key) else values[key] = value
                }
                pending.clear()
            }
        }
    }
}
