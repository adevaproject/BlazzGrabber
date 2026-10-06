package com.blazzgrabber

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.util.Log

class DownloadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_CHECK_STATUS -> handleCheckStatus(context, intent)
            ACTION_DOWNLOAD -> handleDownload(context, intent)
            ACTION_CANCEL -> handleCancel(context, intent)
            ACTION_CLEANUP -> handleCleanup(context, intent)
        }
    }

    private fun handleCancel(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        val pendingResult = goAsync()
        Thread {
            val response = try {
                cancelDownload(appContext, intent)
            } catch (exception: Exception) {
                Log.e(LOG_TAG, "BATAL_GAGAL alasan=${exception.javaClass.simpleName}")
                JsonResponse.fail(ERROR_INTERNAL)
            }
            respond(pendingResult, response.build())
        }.start()
    }

    private fun handleCleanup(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        val pendingResult = goAsync()
        Thread {
            val response = try {
                cleanup(appContext, intent)
            } catch (exception: Exception) {
                Log.e(LOG_TAG, "CLEANUP_GAGAL alasan=${exception.javaClass.simpleName}")
                JsonResponse.fail(ERROR_INTERNAL)
            }
            respond(pendingResult, response.build())
        }.start()
    }

    private fun cancelDownload(context: Context, intent: Intent): JsonResponse {
        val ref = intent.getStringExtra(EXTRA_REF)
        val idArg = intent.getLongExtra(EXTRA_DOWNLOAD_ID, -1L)
        val entry = when {
            idArg >= 0L -> DownloadRegistry.findById(context, idArg)
            !ref.isNullOrEmpty() -> DownloadRegistry.findByRef(context, ref)
            else -> null
        }
        val downloadId = entry?.id ?: idArg
        if (downloadId < 0L) {
            Log.e(LOG_TAG, "BATAL_GAGAL alasan=ID_TIDAK_VALID")
            return JsonResponse.fail(ERROR_NOT_FOUND).withRef(ref)
        }

        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val removed = downloadManager.remove(downloadId) > 0
        entry?.let {
            val target = GalleryStore.fileFor(context, it.filename)
            if (target.isFile && target.delete()) {
                Log.i(LOG_TAG, "BATAL_HAPUS file=${it.filename}")
            }
            DownloadRegistry.remove(context, downloadId)
        }
        Log.i(LOG_TAG, "BATAL_UNDUH id=$downloadId removed=$removed")
        return JsonResponse.ok().bool("cancelled", removed).withRef(ref)
    }

    private fun cleanup(context: Context, intent: Intent): JsonResponse {
        val ref = intent.getStringExtra(EXTRA_REF)
        val filename = intent.getStringExtra(EXTRA_FILENAME)?.trim()
        val olderThanHours = intent.getIntExtra(EXTRA_OLDER_THAN_HOURS, 0)
        if (filename.isNullOrEmpty() && olderThanHours <= 0) {
            Log.e(LOG_TAG, "CLEANUP_GAGAL alasan=BAD_ARGS")
            return JsonResponse.fail(ERROR_BAD_ARGS).withRef(ref)
        }

        val directory = GalleryStore.mediaDir(context)
        val files = if (!filename.isNullOrEmpty()) {
            listOf(GalleryStore.fileFor(context, filename))
        } else {
            directory.listFiles()?.toList().orEmpty()
        }
        val cutoff = System.currentTimeMillis() - olderThanHours * 3600_000L
        var deleted = 0
        var freed = 0L
        for (file in files) {
            if (!file.isFile || file.name.startsWith(".")) continue
            if (olderThanHours > 0 && file.lastModified() >= cutoff) continue
            val size = file.length()
            if (file.delete()) {
                deleteMediaStoreRows(context, file)
                deleted++
                freed += size
            }
        }
        Log.i(LOG_TAG, "CLEANUP_SELESAI deleted=$deleted freed=$freed")
        return JsonResponse.ok().num("deleted", deleted).num("freed_bytes", freed).withRef(ref)
    }

    private fun deleteMediaStoreRows(context: Context, file: java.io.File) {
        try {
            context.contentResolver.delete(
                android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                "${android.provider.MediaStore.MediaColumns.DATA}=?",
                arrayOf(file.absolutePath)
            )
        } catch (exception: Exception) {
            Log.e(LOG_TAG, "CLEANUP_BARIS_GAGAL file=${file.name} alasan=${exception.javaClass.simpleName}")
        }
    }

    private fun handleDownload(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        val pendingResult = goAsync()
        Thread {
            val response = try {
                startDownload(appContext, intent)
            } catch (exception: Exception) {
                Log.e(LOG_TAG, "UNDUH_GAGAL alasan=${exception.javaClass.simpleName}")
                JsonResponse.fail(ERROR_INTERNAL).withRef(intent.getStringExtra(EXTRA_REF))
            }
            respond(pendingResult, response.build())
        }.start()
    }

    private fun handleCheckStatus(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        val pendingResult = goAsync()
        Thread {
            val response = try {
                checkStatus(appContext, intent)
            } catch (exception: Exception) {
                Log.e(LOG_TAG, "UNDUH_GAGAL alasan=${exception.javaClass.simpleName}")
                JsonResponse.fail(ERROR_INTERNAL).withRef(intent.getStringExtra(EXTRA_REF))
            }
            respond(pendingResult, response.build())
        }.start()
    }

    private fun respond(pendingResult: BroadcastReceiver.PendingResult, json: String) {
        try {
            pendingResult.setResultData(json)
        } catch (exception: Exception) {
            Log.e(LOG_TAG, "RESPONS_GAGAL alasan=${exception.javaClass.simpleName}")
        }
        pendingResult.finish()
    }

    private fun startDownload(context: Context, intent: Intent): JsonResponse {
        val ref = intent.getStringExtra(EXTRA_REF)
        val url = intent.getStringExtra(EXTRA_URL)
        val uri = url?.let(Uri::parse)

        val scheme = uri?.scheme?.lowercase()
        if (uri == null || (scheme != "https" && scheme != "http") || uri.host.isNullOrBlank()) {
            Log.e(LOG_TAG, "UNDUH_GAGAL alasan=URL_TIDAK_VALID")
            return JsonResponse.fail(ERROR_BAD_URL).withRef(ref)
        }
        if (scheme == "http" && !context.resources.getBoolean(R.bool.allow_http)) {
            Log.e(LOG_TAG, "UNDUH_GAGAL alasan=BAD_URL http_ditolak")
            return JsonResponse.fail(ERROR_BAD_URL).withRef(ref)
        }
        if (!isHostAllowed(context, uri.host!!)) {
            Log.e(LOG_TAG, "UNDUH_GAGAL alasan=HOST_NOT_ALLOWED host=${uri.host}")
            return JsonResponse.fail(ERROR_HOST_NOT_ALLOWED).withRef(ref)
        }

        val requestedName = intent.getStringExtra(EXTRA_FILENAME)?.trim()
        val filename = if (requestedName.isNullOrEmpty()) {
            deriveFilename(uri)
        } else {
            if (!isValidFilename(requestedName)) {
                Log.e(LOG_TAG, "UNDUH_GAGAL alasan=BAD_ARGS")
                return JsonResponse.fail(ERROR_BAD_ARGS).withRef(ref)
            }
            requestedName
        }

        val expectedSize = intent.getLongExtra(EXTRA_EXPECTED_SIZE, 0L)
        val ifExists = intent.getStringExtra(EXTRA_IF_EXISTS)?.trim()?.lowercase()
        if (ifExists != null && ifExists != "skip" && ifExists != "replace") {
            Log.e(LOG_TAG, "UNDUH_GAGAL alasan=BAD_ARGS")
            return JsonResponse.fail(ERROR_BAD_ARGS).withRef(ref)
        }

        val target = GalleryStore.fileFor(context, filename)
        if (target.isFile) {
            when (ifExists) {
                "skip" -> {
                    val size = target.length()
                    if (expectedSize <= 0L || size == expectedSize) {
                        val rank = GalleryStore.touch(context, filename)
                        Log.i(LOG_TAG, "UNDUH_SKIP file=$filename size=$size rank=$rank")
                        return JsonResponse.ok()
                            .str("state", "EXISTS")
                            .str("filename", filename)
                            .num("size", size)
                            .num("gallery_rank", rank)
                            .withRef(ref)
                    }
                }
                "replace" -> if (!target.delete()) {
                    Log.e(LOG_TAG, "UNDUH_GAGAL file=$filename alasan=TIDAK_BISA_HAPUS")
                    return JsonResponse.fail(ERROR_INTERNAL).withRef(ref)
                }
            }
        }

        if (expectedSize > 0L && freeBytes() < expectedSize + SPACE_MARGIN_BYTES) {
            Log.e(LOG_TAG, "UNDUH_GAGAL alasan=NO_SPACE")
            return JsonResponse.fail(ERROR_NO_SPACE).withRef(ref)
        }

        val request = DownloadManager.Request(uri)
            .setTitle(filename)
            .setDescription(context.getString(R.string.app_name))
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(
                Environment.DIRECTORY_DOWNLOADS,
                "${context.getString(R.string.download_folder)}/$filename"
            )

        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val downloadId = try {
            downloadManager.enqueue(request)
        } catch (exception: RuntimeException) {
            Log.e(LOG_TAG, "UNDUH_GAGAL alasan=${exception.javaClass.simpleName}")
            return JsonResponse.fail(ERROR_DOWNLOAD_FAILED).withRef(ref)
        }

        DownloadRegistry.record(context, downloadId, filename, expectedSize, ref)
        Log.i(LOG_TAG, "UNDUH_DIMULAI id=$downloadId")
        return JsonResponse.ok()
            .str("state", "STARTED")
            .num("download_id", downloadId)
            .str("filename", filename)
            .withRef(ref)
    }

    private fun isHostAllowed(context: Context, host: String): Boolean {
        val allowed = context.getString(R.string.allowed_hosts)
            .split(',')
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
        if (allowed.isEmpty()) return true
        val normalized = host.lowercase()
        return allowed.any { it == normalized || normalized.endsWith(".$it") }
    }

    private fun deriveFilename(uri: Uri): String =
        uri.lastPathSegment
            ?.takeIf { it.isNotBlank() }
            ?.replace(Regex("[^A-Za-z0-9._-]"), "_")
            ?.take(180)
            ?.takeIf { it != "." && it != ".." }
            ?: "blazzgrabber-${System.currentTimeMillis()}.bin"

    private fun isValidFilename(filename: String): Boolean {
        if (filename.isBlank() || filename.length > MAX_FILENAME_LENGTH) return false
        if (filename == "." || filename == "..") return false
        return filename.none { it == '/' || it == '\\' || it < ' ' }
    }

    private fun freeBytes(): Long = try {
        val stat = StatFs(Environment.getExternalStorageDirectory().absolutePath)
        stat.availableBytes
    } catch (exception: Exception) {
        Long.MAX_VALUE
    }

    private fun checkStatus(context: Context, intent: Intent): JsonResponse {
        val ref = intent.getStringExtra(EXTRA_REF)
        val idArg = intent.getLongExtra(EXTRA_DOWNLOAD_ID, -1L)
        val filenameArg = intent.getStringExtra(EXTRA_FILENAME)?.trim()

        val entry = when {
            idArg >= 0L -> DownloadRegistry.findById(context, idArg)
                ?: filenameArg?.let { DownloadRegistry.Entry(idArg, it, 0L, ref) }
            !ref.isNullOrEmpty() -> DownloadRegistry.findByRef(context, ref)
            !filenameArg.isNullOrEmpty() -> DownloadRegistry.findByFilename(context, filenameArg)
            else -> null
        }

        val downloadId = entry?.id ?: idArg
        val filename = entry?.filename ?: filenameArg ?: ""
        if (downloadId < 0L) {
            Log.e(LOG_TAG, "UNDUH_GAGAL alasan=ID_TIDAK_VALID")
            return JsonResponse.fail(ERROR_NOT_FOUND)
                .str("status", "NOT_FOUND")
                .withRef(ref)
        }

        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        var status = "NOT_FOUND"
        var bytes = -1L
        var total = -1L
        var reason: Int? = null
        downloadManager.query(DownloadManager.Query().setFilterById(downloadId))?.use { result ->
            if (result.moveToFirst()) {
                status = statusName(
                    result.getInt(result.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                )
                val bytesIndex = result.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                val totalIndex = result.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                val reasonIndex = result.getColumnIndex(DownloadManager.COLUMN_REASON)
                if (bytesIndex >= 0) bytes = result.getLong(bytesIndex)
                if (totalIndex >= 0) total = result.getLong(totalIndex)
                if (reasonIndex >= 0) reason = result.getInt(reasonIndex)
            }
        }

        Log.i(LOG_TAG, "UNDUH_STATUS id=$downloadId status=$status bytes=$bytes total=$total reason=${reason ?: 0}")

        val indexed = filename.isNotEmpty() && GalleryStore.info(context, filename).inMediaStore
        val response = JsonResponse.ok()
            .num("download_id", downloadId)
            .str("status", status)
            .num("bytes", bytes)
            .num("total", total)
        if (reason != null) response.num("reason", reason) else response.raw("reason", "null")
        if (filename.isNotEmpty()) response.str("filename", filename)
        response.bool("indexed", indexed)
        return response.withRef(ref)
    }

    private fun statusName(status: Int): String = when (status) {
        DownloadManager.STATUS_PENDING -> "PENDING"
        DownloadManager.STATUS_RUNNING -> "RUNNING"
        DownloadManager.STATUS_PAUSED -> "PAUSED"
        DownloadManager.STATUS_SUCCESSFUL -> "SUCCESSFUL"
        DownloadManager.STATUS_FAILED -> "FAILED"
        else -> "NOT_FOUND"
    }

    private fun JsonResponse.withRef(ref: String?): JsonResponse =
        if (ref.isNullOrEmpty()) this else this.str("ref", ref)

    private companion object {
        const val ACTION_DOWNLOAD = "com.blazzgrabber.UNDUH_VIDEO"
        const val ACTION_CHECK_STATUS = "com.blazzgrabber.CEK_STATUS"
        const val ACTION_CANCEL = "com.blazzgrabber.BATAL_UNDUH"
        const val ACTION_CLEANUP = "com.blazzgrabber.CLEANUP"
        const val EXTRA_URL = "url"
        const val EXTRA_DOWNLOAD_ID = "download_id"
        const val EXTRA_FILENAME = "filename"
        const val EXTRA_REF = "ref"
        const val EXTRA_OLDER_THAN_HOURS = "older_than_hours"
        const val EXTRA_EXPECTED_SIZE = "expected_size"
        const val EXTRA_IF_EXISTS = "if_exists"
        const val MAX_FILENAME_LENGTH = 180
        const val SPACE_MARGIN_BYTES = 200L * 1024 * 1024
        const val ERROR_BAD_URL = "BAD_URL"
        const val ERROR_HOST_NOT_ALLOWED = "HOST_NOT_ALLOWED"
        const val ERROR_BAD_ARGS = "BAD_ARGS"
        const val ERROR_NO_SPACE = "NO_SPACE"
        const val ERROR_DOWNLOAD_FAILED = "DOWNLOAD_FAILED"
        const val ERROR_NOT_FOUND = "NOT_FOUND"
        const val ERROR_INTERNAL = "INTERNAL"
        const val LOG_TAG = "BlazzResponse"
    }
}
