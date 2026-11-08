package com.comfyremote.app.data.project

/**
 * Conservative, extension-level UI routing for History media.
 * Actual decodability and duration are checked before a timeline mutation.
 */
object ProjectMediaLinks {
    enum class Kind { VIDEO, AUDIO, IMAGE, UNKNOWN }

    fun kind(path: String): Kind = when (path.substringAfterLast('.', "").lowercase()) {
        "mp4", "m4v", "mov", "webm", "mkv" -> Kind.VIDEO
        "wav", "mp3", "m4a", "aac", "flac", "ogg" -> Kind.AUDIO
        "png", "jpg", "jpeg", "webp", "heif", "heic" -> Kind.IMAGE
        else -> Kind.UNKNOWN
    }

    fun track(path: String): TrackKind? = when (kind(path)) {
        Kind.VIDEO -> TrackKind.VIDEO
        Kind.AUDIO -> TrackKind.AUDIO
        else -> null
    }
}
