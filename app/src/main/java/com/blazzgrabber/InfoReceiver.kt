package com.blazzgrabber

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.util.Log

/** Action [ACTION_INFO]: laporan kondisi perangkat untuk worker ("device doctor"). */
class InfoReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_INFO) return

        val appContext = context.applicationContext
        val pendingResult = goAsync()
        Thread {
            val response = try {
                buildInfo(appContext, intent.getStringExtra(EXTRA_REF))
            } catch (exception: Exception) {
                Log.e(LOG_TAG, "INFO_GAGAL alasan=${exception.javaClass.simpleName}")
                JsonResponse.fail(ERROR_INTERNAL).withRef(intent.getStringExtra(EXTRA_REF))
            }
            try {
                pendingResult.setResultData(response.build())
            } catch (exception: Exception) {
                Log.e(LOG_TAG, "RESPONS_GAGAL alasan=${exception.javaClass.simpleName}")
            }
            pendingResult.finish()
        }.start()
    }

    private fun buildInfo(context: Context, ref: String?): JsonResponse {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode.toLong()
        }

        val manageStorage = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            Environment.isExternalStorageManager()
        val permissions = JsonResponse()
            .bool("storage_write", granted(context, Manifest.permission.WRITE_EXTERNAL_STORAGE))
            .bool("storage_read", granted(context, Manifest.permission.READ_EXTERNAL_STORAGE))
            .bool("notifications", granted(context, Manifest.permission.POST_NOTIFICATIONS))
            .bool("manage_storage", manageStorage)

        val response = JsonResponse.ok()
            .str("version_name", packageInfo.versionName ?: "0")
            .num("version_code", versionCode)
            .num("sdk", Build.VERSION.SDK_INT)
            .raw("perm", permissions.build())
            .num("storage_free_mb", freeBytes() / (1024 * 1024))
            .num("battery_pct", batteryPercent(context))
            .str("media_dir", GalleryStore.mediaDir(context).absolutePath)
        return response.withRef(ref)
    }

    private fun granted(context: Context, permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun freeBytes(): Long = try {
        StatFs(Environment.getExternalStorageDirectory().absolutePath).availableBytes
    } catch (exception: Exception) {
        0L
    }

    private fun batteryPercent(context: Context): Int {
        val manager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        return manager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
    }

    private fun JsonResponse.withRef(ref: String?): JsonResponse =
        if (ref.isNullOrEmpty()) this else this.str("ref", ref)

    private companion object {
        const val ACTION_INFO = "com.blazzgrabber.INFO"
        const val EXTRA_REF = "ref"
        const val ERROR_INTERNAL = "INTERNAL"
        const val LOG_TAG = "BlazzResponse"
    }
}
