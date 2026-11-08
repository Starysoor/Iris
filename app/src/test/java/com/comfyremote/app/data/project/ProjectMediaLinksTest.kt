package com.comfyremote.app.data.project

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProjectMediaLinksTest {
    @Test fun distinguishesTimelineMediaFromReferenceImages() {
        assertEquals(TrackKind.VIDEO, ProjectMediaLinks.track("/a/scene.MP4"))
        assertEquals(TrackKind.VIDEO, ProjectMediaLinks.track("/a/scene.webm"))
        assertEquals(TrackKind.AUDIO, ProjectMediaLinks.track("/a/voice.wav"))
        assertEquals(TrackKind.AUDIO, ProjectMediaLinks.track("/a/music.M4A"))
        assertNull(ProjectMediaLinks.track("/a/hero.png"))
        assertNull(ProjectMediaLinks.track("/a/unknown.zip"))
    }
}
