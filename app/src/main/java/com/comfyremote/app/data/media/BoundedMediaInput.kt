package com.comfyremote.app.data.media

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** Limits untrusted gallery and document-provider streams even without Content-Length. */
internal object BoundedMediaInput {
    const val MAX_IMAGE_BYTES = 32L * 1024 * 1024
    const val MAX_AUDIO_BYTES = 32L * 1024 * 1024

    fun read(source: InputStream, limit: Long): ByteArray {
        val output = ByteArrayOutputStream()
        copy(source, output, limit)
        return output.toByteArray()
    }

    fun copy(
        source: InputStream, target: OutputStream, limit: Long,
        onProgress: (Long) -> Unit = {},
    ): Long {
        require(limit > 0)
        val buffer = ByteArray(64 * 1024)
        var received = 0L
        while (true) {
            val n = source.read(buffer)
            if (n < 0) break
            received += n
            if (received > limit) throw IOException("Media input exceeds ${limit / (1024 * 1024)} MiB")
            target.write(buffer, 0, n)
            onProgress(received)
        }
        return received
    }
}
