package com.blazzgrabber

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.util.Log

class DownloadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_CHECK_STATUS) {
            logDownloadStatus(context, intent.getLongExtra(EXTRA_DOWNLOAD_ID, -1L))
            return
        }
        if (intent.action != ACTION_DOWNLOAD) return

        val url = intent.getStringExtra(EXTRA_URL)
        val uri = url?.let(Uri::parse)
        if (uri == null ||
            (!uri.scheme.equals("https", ignoreCase = true) &&
                !uri.scheme.equals("http", ignoreCase = true)) ||
            uri.host.isNullOrBlank()
        ) {
            Log.e(LOG_TAG, "UNDUH_GAGAL alasan=URL_TIDAK_VALID")
            return
        }

        val fileName = uri.lastPathSegment
            ?.takeIf { it.isNotBlank() }
            ?.replace(Regex("[^A-Za-z0-9._-]"), "_")
            ?.take(180)
            ?.takeIf { it != "." && it != ".." }
            ?: "blazzgrabber-${System.currentTimeMillis()}.bin"

        val request = DownloadManager.Request(uri)
            .setTitle(fileName)
            .setDescription("BlazzGrabber")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "$DOWNLOAD_FOLDER/$fileName")

        try {
            val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val downloadId = downloadManager.enqueue(request)
            Log.i(LOG_TAG, "UNDUH_DIMULAI id=$downloadId")
        } catch (exception: RuntimeException) {
            Log.e(LOG_TAG, "UNDUH_GAGAL alasan=${exception.javaClass.simpleName}")
        }
    }

    private fun logDownloadStatus(context: Context, downloadId: Long) {
        if (downloadId < 0L) {
            Log.e(LOG_TAG, "UNDUH_GAGAL alasan=ID_TIDAK_VALID")
            return
        }

        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val cursor = downloadManager.query(DownloadManager.Query().setFilterById(downloadId))
        cursor?.use { result ->
            if (!result.moveToFirst()) {
                Log.e(LOG_TAG, "UNDUH_GAGAL id=$downloadId alasan=HASIL_TIDAK_DITEMUKAN")
                return
            }

            val status = result.getInt(result.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val bytesIndex = result.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
            val totalIndex = result.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
            val reasonIndex = result.getColumnIndex(DownloadManager.COLUMN_REASON)
            val bytes = if (bytesIndex >= 0) result.getLong(bytesIndex) else -1L
            val total = if (totalIndex >= 0) result.getLong(totalIndex) else -1L
            val reason = if (reasonIndex >= 0) result.getInt(reasonIndex) else 0
            Log.i(LOG_TAG, "UNDUH_STATUS id=$downloadId status=$status bytes=$bytes total=$total reason=$reason")
        }
    }

    private companion object {
        const val ACTION_DOWNLOAD = "com.blazzgrabber.UNDUH_VIDEO"
        const val ACTION_CHECK_STATUS = "com.blazzgrabber.CEK_STATUS"
        const val EXTRA_URL = "url"
        const val EXTRA_DOWNLOAD_ID = "download_id"
        const val DOWNLOAD_FOLDER = "BlazzMedia"
        const val LOG_TAG = "BlazzResponse"
    }
}