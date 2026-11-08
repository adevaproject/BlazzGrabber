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
    val uri: Uri?,
    val mediaType: String
)

/**
 * Jenis media yang didukung. Setiap jenis punya koleksi MediaStore sendiri, jadi
 * pencarian baris, indeks baru, dan perhitungan rank harus memakai koleksi yang tepat.
 */
enum class MediaKind(val collection: String, val typeName: String) {
    VIDEO("content://media/external/video/media", "video"),
    IMAGE("content://media/external/images/media", "image")
}

/**
 * Mengelola file media di folder unduhan: keberadaan, indeks MediaStore, dan
 * urutannya di galeri. Mendukung video dan gambar.
 *
 * Urutan galeri mengikuti `date_added DESC` (item terbaru = rank 0), dihitung per
 * jenis media. Pada Android 10+ `date_added` dikelola sistem dan tidak bisa diubah
 * langsung, sehingga untuk memindahkan file ke urutan teratas file disalin ulang ke
 * path yang sama dengan baris MediaStore baru (lihat [touch]).
 */
object GalleryStore {
    private const val LOG_TAG = "BlazzResponse"
    private const val RANK_POLL_TIMEOUT_MS = 8000L
    private const val RANK_POLL_INTERVAL_MS = 200L
    private const val INDEX_POLL_ATTEMPTS = 10
    private const val INDEX_POLL_INTERVAL_MS = 200L
    private const val INDEX_WAIT_TIMEOUT_MS = 3000L

    private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "heic", "heif", "bmp", "jfif")

    private data class Row(
        val id: Long,
        val size: Long,
        val dateAdded: Long,
        val dateModified: Long,
        val isPending: Boolean
    )

    /** Menentukan jenis media dari ekstensi nama file. Default video (kompatibel mundur). */
    fun kindFor(filename: String): MediaKind {
        val extension = filename.substringAfterLast('.', "").lowercase()
        return if (extension in IMAGE_EXTENSIONS) MediaKind.IMAGE else MediaKind.VIDEO
    }

    fun mediaDir(context: Context): File = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
        context.getString(R.string.download_folder)
    )

    fun fileFor(context: Context, filename: String): File = File(mediaDir(context), filename)

    private fun collectionUri(kind: MediaKind): Uri = Uri.parse(kind.collection)

    private fun relativePath(context: Context): String =
        "${Environment.DIRECTORY_DOWNLOADS}/${context.getString(R.string.download_folder)}/"

    /** Informasi file tanpa melakukan perubahan apa pun. */
    fun info(context: Context, filename: String): GalleryFileInfo {
        val file = fileFor(context, filename)
        val kind = kindFor(filename)
        val exists = file.isFile
        val row = if (exists) queryByPath(context, file, kind) else null
        return GalleryFileInfo(
            exists = exists,
            size = if (exists) file.length() else 0L,
            inMediaStore = row != null && !row.isPending,
            dateAdded = row?.dateAdded ?: 0L,
            dateModified = row?.dateModified ?: 0L,
            galleryRank = if (row != null) galleryRank(context, row.id, kind) else -1,
            uri = row?.let { ContentUris.withAppendedId(collectionUri(kind), it.id) },
            mediaType = kind.typeName
        )
    }

    /**
     * Seperti [info], tetapi bila file sudah ada namun belum terindeks di MediaStore,
     * memicu scan dan menunggu sebentar sampai baris tersedia. Ini mencegah pemeriksaan
     * rank pertama setelah unduhan balapan dengan pengindeksan MediaProvider yang
     * asinkron (terlihat di Android 9; Android 10+ sudah tertangani lewat IS_PENDING).
     */
    fun infoWaitingForIndex(
        context: Context,
        filename: String,
        timeoutMs: Long = INDEX_WAIT_TIMEOUT_MS
    ): GalleryFileInfo {
        val initial = info(context, filename)
        if (!initial.exists || initial.inMediaStore) return initial

        ensureIndexed(context, fileFor(context, filename))

        val deadline = System.currentTimeMillis() + timeoutMs
        var current = initial
        while (System.currentTimeMillis() < deadline) {
            current = info(context, filename)
            if (current.inMediaStore) return current
            try {
                Thread.sleep(INDEX_POLL_INTERVAL_MS)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                return current
            }
        }
        return current
    }

    /**
     * Menjadikan [filename] item pertama di galeri. Mengembalikan rank akhir,
     * atau -1 bila gagal / file tidak ada.
     */
    fun touch(context: Context, filename: String): Int {
        val file = fileFor(context, filename)
        val kind = kindFor(filename)
        if (!file.isFile) {
            Log.e(LOG_TAG, "TOUCH_GAGAL file=$filename alasan=FILE_TIDAK_ADA")
            return -1
        }
        if (galleryRank(context, filename) == 0) return 0

        // Selesaikan baris pending lebih dulu agar baris lama bisa dihapus dan diganti
        // baris baru setelah penyalinan; tanpa ini TOUCH berakhir RANK=-1.
        ensureIndexed(context, file)
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
        deleteRowsForPath(context, file, kind)

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
            Log.i(LOG_TAG, "TOUCH_SUKSES file=$filename type=${kind.typeName} rank=0")
        } else {
            Log.e(LOG_TAG, "TOUCH_GAGAL file=$filename alasan=RANK=$resolvedRank")
        }
        return resolvedRank
    }

    /**
     * Memastikan file terindeks sebagai video/gambar di MediaStore.
     *
     * Baris dibuat lewat `ContentResolver.insert` di semua versi Android, bukan hanya
     * Android 10+. Di Android ≤ 9 `MediaScannerConnection.scanFile` kadang tidak
     * menghasilkan baris untuk file yang disalin dari luar aplikasi (mis. `adb push`
     * dari komputer), sehingga rank tidak pernah terhitung dan `TOUCH` gagal dengan
     * `RANK=-1`. Insert langsung memberi baris dengan `date_added` baru tanpa menunggu
     * pengindeksan asinkron; scan tetap dipakai sebagai cadangan bila insert ditolak.
     */
    fun ensureIndexed(context: Context, file: File) {
        val kind = kindFor(file.name)
        val existing = queryByPath(context, file, kind)
        if (existing != null) {
            if (!existing.isPending) return
            // Baris hasil scan bisa tertinggal dalam keadaan pending (mis. file masuk lewat
            // `adb push`). Selama pending, rank tidak dihitung dan insert baru akan
            // ditolak indeks unik `_data`, jadi baris itu yang diselesaikan di sini.
            finalizeRow(context, existing.id, kind)
            if (queryByPath(context, file, kind)?.takeIf { !it.isPending } != null) return
            Log.e(LOG_TAG, "INDEX_PENDING file=${file.name} type=${kind.typeName} id=${existing.id}")
            return
        }

        insertRow(context, file, kind)
        if (queryByPath(context, file, kind)?.takeIf { !it.isPending } != null) return

        // Insert bisa ditolak bila sudah ada baris `_data` yang sama tetapi tidak terlihat
        // aplikasi (baris pending milik proses lain, mis. file ditulis lewat `adb push`).
        // Minta MediaProvider memindai ulang lalu tunggu sebentar.
        MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), null, null)
        val deadline = System.currentTimeMillis() + INDEX_WAIT_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (queryByPath(context, file, kind)?.takeIf { !it.isPending } != null) return
            try {
                Thread.sleep(INDEX_POLL_INTERVAL_MS)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
    }

    /** Menyelesaikan baris yang masih `IS_PENDING` supaya ikut terhitung di galeri. */
    private fun finalizeRow(context: Context, id: Long, kind: MediaKind) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        try {
            context.contentResolver.update(
                ContentUris.withAppendedId(collectionUri(kind), id),
                values,
                null,
                null
            )
            Log.i(LOG_TAG, "INDEX_FINAL type=${kind.typeName} id=$id")
        } catch (exception: Exception) {
            Log.e(LOG_TAG, "INDEX_FINAL_GAGAL type=${kind.typeName} id=$id alasan=${exception.javaClass.simpleName}")
        }
    }

    /** Rank file di galeri (per jenis media); 0 berarti item pertama. -1 bila tidak ditemukan. */
    fun galleryRank(context: Context, filename: String): Int {
        val kind = kindFor(filename)
        val row = queryByPath(context, fileFor(context, filename), kind) ?: return -1
        return galleryRank(context, row.id, kind)
    }

    private fun galleryRank(context: Context, id: Long, kind: MediaKind): Int {
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        val selection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "${MediaStore.MediaColumns.IS_PENDING}=0"
        } else {
            null
        }
        var rank = 0
        context.contentResolver.query(
            collectionUri(kind),
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
        val kind = kindFor(file.name)
        val deadline = System.currentTimeMillis() + RANK_POLL_TIMEOUT_MS
        var rank = -1
        while (System.currentTimeMillis() < deadline) {
            val row = queryByPath(context, file, kind)
            if (row != null && !row.isPending) {
                rank = galleryRank(context, row.id, kind)
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

    private fun insertRow(context: Context, file: File, kind: MediaKind) {
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
            context.contentResolver.insert(collectionUri(kind), values)
        } catch (exception: Exception) {
            Log.e(LOG_TAG, "INDEX_GAGAL file=${file.name} type=${kind.typeName} alasan=${exception.javaClass.simpleName}")
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
            val row = queryByPath(context, file, kind)
            if (row != null && !row.isPending) return
            try {
                Thread.sleep(INDEX_POLL_INTERVAL_MS)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
    }

    private fun deleteRowsForPath(context: Context, file: File, kind: MediaKind) {
        val row = queryByPath(context, file, kind) ?: return
        try {
            context.contentResolver.delete(
                ContentUris.withAppendedId(collectionUri(kind), row.id),
                null,
                null
            )
        } catch (exception: Exception) {
            Log.e(LOG_TAG, "HAPUS_BARIS_GAGAL file=${file.name} alasan=${exception.javaClass.simpleName}")
        }
    }

    /** Menghapus baris MediaStore untuk [file] pada koleksi yang sesuai jenisnya. */
    fun deleteMediaStoreRows(context: Context, file: File) {
        deleteRowsForPath(context, file, kindFor(file.name))
    }

    private fun queryByPath(context: Context, file: File, kind: MediaKind): Row? {
        query(context, "${MediaStore.MediaColumns.DATA}=?", arrayOf(file.absolutePath), kind)?.let { return it }
        return query(
            context,
            "${MediaStore.MediaColumns.DISPLAY_NAME}=?",
            arrayOf(file.name),
            kind
        )
    }

    private fun query(context: Context, selection: String, args: Array<String>, kind: MediaKind): Row? {
        val projection = mutableListOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.DATE_MODIFIED
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            projection.add(MediaStore.MediaColumns.IS_PENDING)
        }
        return try {
            context.contentResolver.query(
                collectionUri(kind),
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
        "jpg", "jpeg", "jfif" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "heic" -> "image/heic"
        "heif" -> "image/heif"
        "bmp" -> "image/bmp"
        else -> "video/mp4"
    }
}
