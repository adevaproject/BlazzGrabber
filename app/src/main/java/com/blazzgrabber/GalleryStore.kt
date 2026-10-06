package com.blazzgrabber

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/** Hasil pemeriksaan sebuah file di folder unduhan dan MediaStore. */
data class GalleryFileInfo(
    val exists: Boolean,
    val size: Long,
    val inMediaStore: Boolean,
    val dateAdded: Long,
    val dateModified: Long,
    val galleryRank: Int,
    val uri: Uri?
)

/**
 * Mengelola file video di folder unduhan: keberadaan, indeks MediaStore, dan
 * urutannya di galeri.
 *
 * Urutan galeri mengikuti `date_added DESC` (item terbaru = rank 0). Pada Android 10+
 * `date_added` dikelola sistem dan tidak bisa diubah langsung, sehingga untuk
 * memindahkan file ke urutan teratas file disalin ulang ke path yang sama dengan
 * baris MediaStore baru (lihat [touch]).
 */
object GalleryStore {
    private const val LOG_TAG = "BlazzResponse"
    private const val VIDEO_COLLECTION = "content://media/external/video/media"
    private const val RANK_POLL_TIMEOUT_MS = 8000L
    private const val RANK_POLL_INTERVAL_MS = 200L
    private const val INDEX_POLL_ATTEMPTS = 10
    private const val INDEX_POLL_INTERVAL_MS = 200L

    private data class Row(
        val id: Long,
        val size: Long,
        val dateAdded: Long,
        val dateModified: Long,
        val dateTaken: Long,
        val isPending: Boolean
    )

    fun mediaDir(context: Context): File = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
        context.getString(R.string.download_folder)
    )

    fun fileFor(context: Context, filename: String): File = File(mediaDir(context), filename)

    private fun collectionUri(): Uri = Uri.parse(VIDEO_COLLECTION)

    private fun relativePath(context: Context): String =
        "${Environment.DIRECTORY_DOWNLOADS}/${context.getString(R.string.download_folder)}/"

    /** Informasi file tanpa melakukan perubahan apa pun. */
    fun info(context: Context, filename: String): GalleryFileInfo {
        val file = fileFor(context, filename)
        val exists = file.isFile
        val row = if (exists) queryByPath(context, file) else null
        return GalleryFileInfo(
            exists = exists,
            size = if (exists) file.length() else 0L,
            inMediaStore = row != null && !row.isPending,
            dateAdded = row?.dateAdded ?: 0L,
            dateModified = row?.dateModified ?: 0L,
            galleryRank = if (row != null) galleryRank(context, row.id) else -1,
            uri = row?.let { ContentUris.withAppendedId(collectionUri(), it.id) }
        )
    }

    /**
     * Menjadikan [filename] item pertama di galeri. Mengembalikan rank akhir,
     * atau -1 bila gagal / file tidak ada.
     */
    fun touch(context: Context, filename: String): Int {
        val file = fileFor(context, filename)
        if (!file.isFile) {
            Log.e(LOG_TAG, "TOUCH_GAGAL file=$filename alasan=FILE_TIDAK_ADA")
            return -1
        }
        if (galleryRank(context, filename) == 0) return 0

        val temp = File(mediaDir(context), ".${filename}.blazztouch")
        if (temp.exists() && !temp.delete()) {
            Log.e(LOG_TAG, "TOUCH_GAGAL file=$filename alasan=TEMP_TIDAK_BISA_DIBUAT")
            return -1
        }

        // 1. Pindahkan file lama dulu supaya baris MediaStore aman dihapus tanpa
        //    menghapus isi file.
        if (!file.renameTo(temp)) {
            Log.e(LOG_TAG, "TOUCH_GAGAL file=$filename alasan=GAGAL_PINDAH")
            return -1
        }

        // 2. Hapus baris MediaStore lama (file sudah di tempat aman).
        deleteRowsForPath(context, file)

        // 3. Salin kembali ke path semula sebagai file baru.
        val copied = try {
            temp.inputStream().use { input ->
                FileOutputStream(file).use { output -> input.copyTo(output) }
            }
            true
        } catch (exception: Exception) {
            Log.e(LOG_TAG, "TOUCH_GAGAL file=$filename alasan=SALIN_GAGAL ${exception.javaClass.simpleName}")
            false
        }

        if (!copied) {
            // Pulihkan file lama bila salinan gagal.
            temp.renameTo(file)
            return -1
        }

        // 4. Daftarkan ke MediaStore. Apa pun yang gagal setelah langkah ini,
        //    file di path akhir sudah utuh, jadi sisa file sementara dibersihkan.
        var rank = -1
        try {
            ensureIndexed(context, file)
            rank = waitForRank(context, file)
        } finally {
            temp.delete()
        }

        val resolvedRank = if (rank == 0) 0 else {
            // Fallback: baris lama tidak terhapus (mis. Android 9 menempel ke baris lama)
            // tetapi file sudah disalin baru; coba tandai ulang dan hitung rank.
            ensureIndexed(context, file)
            waitForRank(context, file)
        }
        if (resolvedRank == 0) {
            Log.i(LOG_TAG, "TOUCH_SUKSES file=$filename rank=0")
        } else {
            Log.e(LOG_TAG, "TOUCH_GAGAL file=$filename alasan=RANK=$resolvedRank")
        }
        return resolvedRank
    }

    /** Memastikan file terindeks sebagai video di MediaStore. */
    fun ensureIndexed(context: Context, file: File) {
        if (queryByPath(context, file)?.takeIf { !it.isPending } != null) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            insertRow(context, file)
        } else {
            MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), null, null)
        }
    }

    /** Rank file di galeri; 0 berarti item pertama. -1 bila tidak ditemukan. */
    fun galleryRank(context: Context, filename: String): Int {
        val row = queryByPath(context, fileFor(context, filename)) ?: return -1
        return galleryRank(context, row.id)
    }

    private fun galleryRank(context: Context, id: Long): Int {
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        val selection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "${MediaStore.MediaColumns.IS_PENDING}=0"
        } else {
            null
        }
        var rank = 0
        context.contentResolver.query(
            collectionUri(),
            projection,
            selection,
            null,
            "${MediaStore.MediaColumns.DATE_ADDED} DESC"
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            while (cursor.moveToNext()) {
                if (cursor.getLong(idColumn) == id) return rank
                rank++
            }
        }
        return -1
    }

    private fun waitForRank(context: Context, file: File): Int {
        val deadline = System.currentTimeMillis() + RANK_POLL_TIMEOUT_MS
        var rank = -1
        while (System.currentTimeMillis() < deadline) {
            val row = queryByPath(context, file)
            if (row != null && !row.isPending) {
                rank = galleryRank(context, row.id)
                if (rank == 0) return 0
            }
            try {
                Thread.sleep(RANK_POLL_INTERVAL_MS)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                return -1
            }
        }
        return rank
    }

    private fun insertRow(context: Context, file: File) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.DATA, file.absolutePath)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeTypeFor(file.name))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath(context))
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val uri = try {
            context.contentResolver.insert(collectionUri(), values)
        } catch (exception: Exception) {
            Log.e(LOG_TAG, "INDEX_GAGAL file=${file.name} alasan=${exception.javaClass.simpleName}")
            null
        } ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val finalizeValues = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            context.contentResolver.update(uri, finalizeValues, null, null)
        }

        // Tunggu metadata siap (durasi/ukuran) setelah finalize.
        repeat(INDEX_POLL_ATTEMPTS) {
            val row = queryByPath(context, file)
            if (row != null && !row.isPending) return
            try {
                Thread.sleep(INDEX_POLL_INTERVAL_MS)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
    }

    private fun deleteRowsForPath(context: Context, file: File) {
        val row = queryByPath(context, file) ?: return
        try {
            context.contentResolver.delete(
                ContentUris.withAppendedId(collectionUri(), row.id),
                null,
                null
            )
        } catch (exception: Exception) {
            Log.e(LOG_TAG, "HAPUS_BARIS_GAGAL file=${file.name} alasan=${exception.javaClass.simpleName}")
        }
    }

    private fun queryByPath(context: Context, file: File): Row? {
        query(context, "${MediaStore.MediaColumns.DATA}=?", arrayOf(file.absolutePath))?.let { return it }
        return query(
            context,
            "${MediaStore.MediaColumns.DISPLAY_NAME}=?",
            arrayOf(file.name)
        )
    }

    private fun query(context: Context, selection: String, args: Array<String>): Row? {
        val projection = mutableListOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.Video.Media.DATE_TAKEN
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            projection.add(MediaStore.MediaColumns.IS_PENDING)
        }
        return try {
            context.contentResolver.query(
                collectionUri(),
                projection.toTypedArray(),
                selection,
                args,
                null
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return null
                fun longOf(name: String): Long {
                    val index = cursor.getColumnIndex(name)
                    return if (index >= 0 && !cursor.isNull(index)) cursor.getLong(index) else 0L
                }
                val pendingIndex = cursor.getColumnIndex(MediaStore.MediaColumns.IS_PENDING)
                Row(
                    id = longOf(MediaStore.MediaColumns._ID),
                    size = longOf(MediaStore.MediaColumns.SIZE),
                    dateAdded = longOf(MediaStore.MediaColumns.DATE_ADDED),
                    dateModified = longOf(MediaStore.MediaColumns.DATE_MODIFIED),
                    dateTaken = longOf(MediaStore.Video.Media.DATE_TAKEN),
                    isPending = pendingIndex >= 0 && cursor.getInt(pendingIndex) != 0
                )
            }
        } catch (exception: Exception) {
            Log.e(LOG_TAG, "QUERY_GAGAL alasan=${exception.javaClass.simpleName}")
            null
        }
    }

    private fun mimeTypeFor(filename: String): String = when (filename.substringAfterLast('.', "").lowercase()) {
        "mp4", "m4v" -> "video/mp4"
        "webm" -> "video/webm"
        "mkv" -> "video/x-matroska"
        "3gp" -> "video/3gpp"
        "mov" -> "video/quicktime"
        else -> "video/mp4"
    }
}
