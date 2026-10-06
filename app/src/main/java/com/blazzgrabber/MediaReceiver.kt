package com.blazzgrabber

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Action baru untuk pengelolaan file galeri: [ACTION_CHECK_FILE] dan [ACTION_TOUCH].
 * Hasil dikembalikan sebagai JSON satu baris lewat [setResultData].
 */
class MediaReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != ACTION_CHECK_FILE && action != ACTION_TOUCH) return

        val ref = intent.getStringExtra(EXTRA_REF)
        val filename = intent.getStringExtra(EXTRA_FILENAME)?.trim()

        if (!isValidFilename(filename)) {
            resultData = JsonResponse.fail(ERROR_BAD_ARGS).withRef(ref).build()
            Log.e(LOG_TAG, "MEDIA_GAGAL action=$action alasan=BAD_ARGS")
            return
        }

        // Konteks receiver membatasi registerReceiver (dipakai MediaScannerConnection),
        // jadi pakai applicationContext.
        val appContext = context.applicationContext
        val pendingResult = goAsync()
        Thread {
            val response = try {
                if (action == ACTION_CHECK_FILE) checkFile(appContext, filename!!) else touch(appContext, filename!!)
            } catch (exception: Exception) {
                Log.e(LOG_TAG, "MEDIA_GAGAL action=$action alasan=${exception.javaClass.simpleName}")
                JsonResponse.fail(ERROR_INTERNAL)
            }
            val json = response.withRef(ref).build()
            try {
                pendingResult.setResultData(json)
            } catch (exception: Exception) {
                Log.e(LOG_TAG, "RESPONS_GAGAL alasan=${exception.javaClass.simpleName}")
            }
            pendingResult.finish()
        }.start()
    }

    private fun checkFile(context: Context, filename: String): JsonResponse {
        val info = GalleryStore.info(context, filename)
        if (!info.exists) return JsonResponse.ok().bool("exists", false)

        val response = JsonResponse.ok()
            .bool("exists", true)
            .num("size", info.size)
            .bool("in_mediastore", info.inMediaStore)
            .num("date_added", info.dateAdded)
            .num("date_modified", info.dateModified)
        if (info.galleryRank >= 0) response.num("gallery_rank", info.galleryRank)
        Log.i(LOG_TAG, "CEK_FILE_SUKSES file=$filename exists=true rank=${info.galleryRank}")
        return response
    }

    private fun touch(context: Context, filename: String): JsonResponse {
        val rank = GalleryStore.touch(context, filename)
        if (rank < 0) {
            return JsonResponse.fail(ERROR_TOUCH_FAILED)
        }
        return JsonResponse.ok().num("gallery_rank", rank)
    }

    private fun JsonResponse.withRef(ref: String?): JsonResponse =
        if (ref.isNullOrEmpty()) this else this.str("ref", ref)

    private fun isValidFilename(filename: String?): Boolean {
        if (filename.isNullOrBlank()) return false
        if (filename.length > MAX_FILENAME_LENGTH) return false
        if (filename == "." || filename == "..") return false
        return filename.none { it == '/' || it == '\\' || it < ' ' }
    }

    private companion object {
        const val ACTION_CHECK_FILE = "com.blazzgrabber.CEK_FILE"
        const val ACTION_TOUCH = "com.blazzgrabber.TOUCH"
        const val EXTRA_FILENAME = "filename"
        const val EXTRA_REF = "ref"
        const val MAX_FILENAME_LENGTH = 180
        const val ERROR_BAD_ARGS = "BAD_ARGS"
        const val ERROR_INTERNAL = "INTERNAL"
        const val ERROR_TOUCH_FAILED = "TOUCH_FAILED"
        const val LOG_TAG = "BlazzResponse"
    }
}
