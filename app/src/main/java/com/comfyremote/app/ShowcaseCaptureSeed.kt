package com.comfyremote.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import com.comfyremote.app.data.project.AssetProfile
import com.comfyremote.app.data.project.ProfileKind
import com.comfyremote.app.data.project.ShotTake
import com.comfyremote.app.data.project.TimelineClip
import com.comfyremote.app.data.project.TrackKind
import com.comfyremote.app.data.reference.AssetKind
import com.comfyremote.app.data.reference.ProjectAsset
import com.comfyremote.app.data.reference.ReferenceRole
import java.io.File

/**
 * Isolated, explicitly triggered DEBUG-only fixture for real emulator screenshots.
 * Never uses cloud services, API keys, task submission, or user's real projects.
 * All fixture names are marked "演示 / Demo" and files live under app-owned data.
 */
internal object ShowcaseCaptureSeed {
    fun seed(container: AppContainer): String? {
        val store = container.projectStore
        val title = "埃瑟维恩 · Studio 演示"
        store.projects.value.firstOrNull { it.title == title }?.let { return it.id }
        val dir = File(container.app.filesDir, "qa-showcase").apply { mkdirs() }

        val palette = listOf(
            Triple("aether-garden.png", 0xff253a4c.toInt(), 0xff75a995.toInt()),
            Triple("violet-ballroom.png", 0xff302343.toInt(), 0xffba8ec6.toInt()),
            Triple("scarlet-moon.png", 0xff3a1c32.toInt(), 0xffe16c78.toInt()),
            Triple("ivory-archives.png", 0xff34475b.toInt(), 0xffbda987.toInt()),
        )
        palette.forEachIndexed { index, (name, start, end) ->
            val f = File(dir, name)
            if (!f.isFile) paintSample(f, start, end, index)
        }

        val project = store.create(
            title = title, workflowId = "", workflowPath = "",
            synopsis = "一段横屏幻想短片：从月光花园进入仪式舞厅，以细腻运镜、首尾帧和多模型候选组织镜头。此项目仅用于截图演示。",
            aspectRatio = "16:9", stylePrompt = "Japanese fantasy · soft painterly · cinematic", chainFrames = false,
        ) ?: return null
        store.setTargetDuration(project.id, 120)
        store.setBudget(project.id, 30.0)
        val assets = palette.mapIndexed { i, (name, _, _) ->
            ProjectAsset(
                path = File(dir, name).absolutePath,
                label = listOf("月光花园 · 场景", "紫罗兰舞厅 · 场景", "绯红之月 · 氛围", "象牙档案馆 · 场景")[i],
                kind = AssetKind.IMAGE,
                role = if (i == 2) ReferenceRole.STYLE_REFERENCE else ReferenceRole.SCENE_REFERENCE,
                tags = listOf("demo", if (i == 2) "style" else "scene"),
            ).also { store.addAsset(project.id, it) }
        }
        store.addProfile(project.id, AssetProfile(
            name = "舞厅 / 场景一致性", summary = "紫色金饰、烛光和仪式大厅",
            kind = ProfileKind.SCENE, assetIds = listOf(assets[1].id, assets[2].id),
        ))

        val shots = listOf(
            "月光穿过花园穹顶，轻缓推进，建立世界",
            "紫裙旅人走入舞厅，侧面跟拍至正面",
            "双手局部特写，镜头上升到面部",
            "旋转裙摆，环绕运镜与柱后遮挡",
            "绯红污染在墙面蔓延，微距切镜",
            "俯视定格，旅人向舞厅谢幕",
        )
        val movie = File(dir, "showcase-cut.mp4")
        shots.forEachIndexed { i, prompt ->
            val shot = store.addShot(project.id, prompt, "镜头 ${i + 1} · 电影级构图") ?: return@forEachIndexed
            store.updateShot(project.id, shot.id) {
                it.copy(referenceAssetIds = listOf(assets[i % assets.size].id))
            }
            // The media is a local color-bar fixture, not paid/generated output.
            if (i < 2 && movie.isFile) {
                val take = ShotTake(
                    status = "done", providerId = "offline-demo", workflowPath = "offline-demo",
                    prompt = prompt, resultPath = movie.absolutePath, costEstimate = 0.0,
                )
                store.updateShot(project.id, shot.id) {
                    it.copy(status = "done", resultPath = movie.absolutePath,
                        takes = listOf(take), selectedTakeId = take.id)
                }
            }
        }
        store.ensureTimeline(project.id)
        if (movie.isFile) {
            repeat(2) { n ->
                store.addClip(project.id, TrackKind.VIDEO, TimelineClip(
                    sourcePath = movie.absolutePath,
                    sourceLabel = "演示镜头 ${n + 1}",
                    sourceOutMs = 4_000L,
                ))
            }
        }
        val audio = File(dir, "showcase-score.wav")
        if (audio.isFile) {
            store.addAsset(project.id, ProjectAsset(
                path = audio.absolutePath, label = "舞厅环境音 · Demo",
                kind = AssetKind.AUDIO, tags = listOf("demo", "sfx"),
            ))
            store.addClip(project.id, TrackKind.AUDIO, TimelineClip(
                sourcePath = audio.absolutePath, sourceLabel = "环境音效 · Demo", sourceOutMs = 8_000L,
            ))
        }
        store.create(
            title = "暮光序章 · 草稿演示", workflowId = "", workflowPath = "",
            synopsis = "另一份未配置模型的离线项目，用于演示项目列表。",
            aspectRatio = "16:9", chainFrames = false,
        )
        return project.id
    }

    private fun paintSample(file: File, first: Int, last: Int, index: Int) {
        val width = 800
        val height = 480
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, 0f, width.toFloat(), height.toFloat(),
                first, last, Shader.TileMode.CLAMP)
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.shader = null
        paint.color = 0x22ffffff
        for (n in 0..9) {
            val x = (n * 97 + index * 53) % width
            canvas.drawCircle(x.toFloat(), (120 + n * 31).toFloat(),
                (18 + n * 7).toFloat(), paint)
        }
        paint.color = Color.WHITE
        paint.textSize = 54f
        paint.letterSpacingCompat()
        canvas.drawText("IRIS  /  DEMO ${index + 1}", 42f, 412f, paint)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 95, it) }
        bitmap.recycle()
    }

    // Keep the painted fixture independent of fonts/resources.
    private fun Paint.letterSpacingCompat() { typeface = android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL) }
}
