package com.comfyremote.app.data.media

/** UI-visible gallery save lifecycle independent of a screen's Snackbar host.
 * The preview dialog is a separate window, so host-screen Snackbars are occluded.
 */
enum class GallerySavePhase { SAVING, SUCCEEDED, FAILED }

data class GallerySaveFeedback(
    val path: String,
    val phase: GallerySavePhase,
    val progress: Float? = null,
    val error: String? = null,
) {
    val isSaving: Boolean get() = phase == GallerySavePhase.SAVING

    companion object {
        fun saving(path: String, fraction: Float = 0f) =
            GallerySaveFeedback(path, GallerySavePhase.SAVING, fraction.coerceIn(0f, 1f))
        fun success(path: String) =
            GallerySaveFeedback(path, GallerySavePhase.SUCCEEDED, 1f)
        fun failure(path: String, reason: String) =
            GallerySaveFeedback(path, GallerySavePhase.FAILED, error = reason)
    }
}
