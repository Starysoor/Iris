package com.comfyremote.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdaterVersionTest {
    @Test fun semverSortsBetaBuildsAndStableProperly() {
        assertTrue(Updater.compareVersions("v2.4.0-beta.3", "2.4.0-beta.2") > 0)
        assertTrue(Updater.compareVersions("v2.4.0-beta.10", "2.4.0-beta.9") > 0)
        assertTrue(Updater.compareVersions("v2.4.0-rc.1", "v2.4.0-beta.10") > 0)
        assertTrue(Updater.compareVersions("v2.4.0", "v2.4.0-rc.7") > 0)
        assertTrue(Updater.compareVersions("v2.3.2", "v2.4.0-beta.3") < 0)
        assertEquals(0, Updater.compareVersions("v2.4.0", "2.4.0"))
    }

    @Test fun betaUsersReceiveNewerBetaAndStableUsersStayStable() {
        fun release(tag: String, pre: Boolean, draft: Boolean = false, apk: Boolean = true): JSONObject {
            val assets = JSONArray()
            if (apk) {
                assets.put(JSONObject()
                    .put("name", "Iris-" + tag + ".apk")
                    .put("url", "https://api.github.com/assets/10")
                    .put("browser_download_url",
                        "https://github.com/Starysoor/Iris/releases/download/" +
                            tag + "/Iris-" + tag + ".apk")
                    .put("size", 123))
            }
            return JSONObject().put("tag_name", tag).put("name", tag)
                .put("prerelease", pre).put("draft", draft).put("assets", assets)
        }
        val releases = JSONArray()
            .put(release("v2.4.0-beta.2", true))
            .put(release("v2.3.2", false))
            .put(release("v2.4.0-beta.3", true))
            .put(release("v2.9.0-beta.1", true, draft = true))
            .put(release("v2.9.1", false, apk = false))
        assertEquals("v2.4.0-beta.3",
            Updater.selectRelease(releases, "2.4.0-beta.2").tag)
        assertEquals("v2.3.2",
            Updater.selectRelease(releases, "2.3.2").tag)
    }
}
