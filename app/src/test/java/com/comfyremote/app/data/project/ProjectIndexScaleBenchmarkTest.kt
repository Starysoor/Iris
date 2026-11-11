package com.comfyremote.app.data.project

import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Reproducible, non-gating baseline for #109 D1.
 * Timings vary by runner; no brittle speed threshold is asserted.
 */
class ProjectIndexScaleBenchmarkTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun fiftyProjectsTwelveShotsThreeTakesEncodeDecodeAndDurableWrite() {
        val projects = (1..50).map { p ->
            VideoProject(
                id = "benchmark-project-$p",
                title = "Project $p",
                synopsis = "A short visual narrative with continuity and a complete soundtrack",
                workflowId = "benchmark-mock",
                shots = (1..12).map { s ->
                    ProjectShot(
                        id = "shot-$p-$s",
                        prompt = "Shot $s, cinematic lighting, consistent costumes, camera motion",
                        cameraNotes = "Track, pan and cut safely",
                        takes = (1..3).map { t ->
                            ShotTake(
                                id = "take-$p-$s-$t",
                                providerId = "mock-provider",
                                workflowPath = "mock:video-v$t",
                                prompt = "Shot $s take $t",
                                seed = (p * s * t).toString(),
                                values = mapOf("seconds" to "10", "ratio" to "16:9"),
                                resultPath = "/mock/$p/$s/$t.mp4",
                                status = "done",
                                costEstimate = 2.0,
                            )
                        },
                    )
                },
            )
        }
        val measuredNs = mutableListOf<Long>()
        var encoded = ""
        repeat(8) { i ->
            val start = System.nanoTime()
            val next = ProjectCodec.encode(projects)
            val elapsed = System.nanoTime() - start
            if (i >= 2) measuredNs += elapsed
            encoded = next
        }
        val parseStart = System.nanoTime()
        val decoded = ProjectCodec.decode(encoded)
        val decodeMs = (System.nanoTime() - parseStart) / 1e6
        assertEquals(50, decoded.size)
        assertEquals(600, decoded.sumOf { it.shots.size })
        assertEquals(1800, decoded.sumOf { it.shots.sumOf { shot -> shot.takes.size } })
        val output = File(temporaryFolder.root, "projects.json")
        val writeStart = System.nanoTime()
        FileOutputStream(output).use { stream ->
            stream.write(encoded.toByteArray(Charsets.UTF_8))
            stream.fd.sync()
        }
        val writeMs = (System.nanoTime() - writeStart) / 1e6
        assertEquals(encoded.toByteArray(Charsets.UTF_8).size.toLong(), output.length())
        val sortedMs = measuredNs.map { it / 1e6 }.sorted()
        println("STUDIO_INDEX_BENCHMARK projects=50 shots=600 takes=1800 bytes=${output.length()} " +
            "encode_ms_median=${sortedMs[sortedMs.size / 2]} " +
            "encode_ms_min=${sortedMs.first()} decode_ms=$decodeMs write_fsync_ms=$writeMs")
        assertTrue(output.length() > 0L)
    }
}
