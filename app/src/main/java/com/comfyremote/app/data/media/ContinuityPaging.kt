package com.comfyremote.app.data.media

/** Read-only page boundaries; adjacent seam comparisons include the previous page's last shot. */
internal data class ContinuityWindow(
    val page: Int,
    val pages: Int,
    val start: Int,
    val endExclusive: Int,
    val seamStart: Int,
) {
    val count: Int get() = endExclusive - start
}

internal object ContinuityPaging {
    fun window(total: Int, requestedPage: Int, pageSize: Int): ContinuityWindow {
        require(total >= 0) { "Negative item count" }
        require(pageSize > 0) { "pageSize must be positive" }
        val pages = maxOf(1, (total / pageSize) + if (total % pageSize == 0) 0 else 1)
        val page = requestedPage.coerceIn(0, pages - 1)
        val start = page * pageSize
        val end = minOf(total, start + pageSize)
        return ContinuityWindow(page, pages, start, end, if (start > 0) start - 1 else start)
    }
}
