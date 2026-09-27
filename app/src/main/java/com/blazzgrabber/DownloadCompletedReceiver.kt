package com.blazzgrabber

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class DownloadCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return

        val downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
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

            when (result.getInt(result.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
                DownloadManager.STATUS_SUCCESSFUL -> Log.i(LOG_TAG, "UNDUH_SELESAI id=$downloadId")
                DownloadManager.STATUS_FAILED -> {
                    val reasonIndex = result.getColumnIndex(DownloadManager.COLUMN_REASON)
                    val reason = if (reasonIndex >= 0) result.getInt(reasonIndex) else -1
                    Log.e(LOG_TAG, "UNDUH_GAGAL id=$downloadId alasan=$reason")
                }
            }
        }
    }

    private companion object {
        const val LOG_TAG = "BlazzResponse"
    }
}