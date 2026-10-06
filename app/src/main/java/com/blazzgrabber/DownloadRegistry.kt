package com.blazzgrabber

import android.content.Context

/**
 * Menyimpan metadata permintaan unduhan per `download_id` agar bisa dipakai
 * ulang oleh [CEK_STATUS] dan saat unduhan selesai (validasi ukuran).
 */
object DownloadRegistry {
    private const val PREFS_NAME = "blazz_downloads"
    private const val SEPARATOR = "\u0000"

    data class Entry(
        val id: Long,
        val filename: String,
        val expectedSize: Long,
        val ref: String?
    )

    fun record(
        context: Context,
        id: Long,
        filename: String,
        expectedSize: Long,
        ref: String?
    ) {
        val value = listOf(filename, expectedSize.toString(), ref.orEmpty())
            .joinToString(SEPARATOR)
        prefs(context).edit().putString(id.toString(), value).apply()
    }

    fun findById(context: Context, id: Long): Entry? =
        prefs(context).all[id.toString()]?.let { decode(id, it as? String) }

    fun findByRef(context: Context, ref: String): Entry? =
        prefs(context).all.entries.asSequence()
            .mapNotNull { (key, value) ->
                val id = key.toLongOrNull() ?: return@mapNotNull null
                decode(id, value as? String)
            }
            .firstOrNull { it.ref == ref }

    fun findByFilename(context: Context, filename: String): Entry? =
        prefs(context).all.entries.asSequence()
            .mapNotNull { (key, value) ->
                val id = key.toLongOrNull() ?: return@mapNotNull null
                decode(id, value as? String)
            }
            .firstOrNull { it.filename == filename }

    fun remove(context: Context, id: Long) {
        prefs(context).edit().remove(id.toString()).apply()
    }

    private fun decode(id: Long, value: String?): Entry? {
        val parts = value?.split(SEPARATOR) ?: return null
        if (parts.size < 3) return null
        return Entry(
            id = id,
            filename = parts[0],
            expectedSize = parts[1].toLongOrNull() ?: 0L,
            ref = parts[2].takeIf { it.isNotEmpty() }
        )
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
