package com.blazzgrabber

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import android.util.Log
import java.util.Locale

class NotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_NOTIFY) return

        val type = intent.getStringExtra(EXTRA_TYPE)
            ?.trim()
            ?.lowercase(Locale.ROOT)
            .orEmpty()
        val text = intent.getStringExtra(EXTRA_TEXT)?.trim()?.take(MAX_TEXT_LENGTH)
        if (type.isBlank() || text.isNullOrBlank()) {
            Log.e(LOG_TAG, "NOTIFY_GAGAL alasan=TYPE_ATAU_TEXT_KOSONG")
            return
        }

        val sound = intent.getStringExtra(EXTRA_SOUND)?.trim()?.lowercase(Locale.ROOT)
        if (sound != null && sound != SOUND_BLAZZ && sound != SOUND_NONE) {
            Log.e(LOG_TAG, "NOTIFY_GAGAL alasan=SOUND_TIDAK_DIKENAL")
            return
        }

        val shouldPlaySound = when (sound) {
            SOUND_BLAZZ -> true
            SOUND_NONE -> false
            else -> type in SOUND_TYPES
        }
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(LOG_TAG, "NOTIFY_GAGAL alasan=IZIN_NOTIFIKASI_BELUM_DIBERIKAN")
            return
        }

        createChannels(context, notificationManager)

        val isProgress = type == TYPE_PROGRESS
        val notificationId = if (isProgress) PROGRESS_NOTIFICATION_ID else EVENT_NOTIFICATION_ID
        notificationManager.cancel(if (isProgress) EVENT_NOTIFICATION_ID else PROGRESS_NOTIFICATION_ID)

        val channelId = if (shouldPlaySound) SOUND_CHANNEL_ID else SILENT_CHANNEL_ID
        val soundUri = Uri.parse("android.resource://${context.packageName}/${R.raw.blazz_app_sfx}")
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, channelId)
        } else {
            Notification.Builder(context).setSound(if (shouldPlaySound) soundUri else null)
        }

        val notification = builder
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("BlazzGrabber")
            .setContentText(text)
            .setOnlyAlertOnce(isProgress)
            .setAutoCancel(!isProgress)
            .build()

        try {
            notificationManager.notify(notificationId, notification)
            Log.i(LOG_TAG, "NOTIFY_TAMPIL type=$type sound=${if (shouldPlaySound) SOUND_BLAZZ else SOUND_NONE}")
        } catch (exception: SecurityException) {
            Log.e(LOG_TAG, "NOTIFY_GAGAL alasan=IZIN_NOTIFIKASI_BELUM_DIBERIKAN")
        }
    }

    private fun createChannels(context: Context, notificationManager: NotificationManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        if (notificationManager.getNotificationChannel(SILENT_CHANNEL_ID) == null) {
            notificationManager.createNotificationChannel(
                NotificationChannel(
                    SILENT_CHANNEL_ID,
                    "BlazzGrabber status",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Status proses dari perintah ADB"
                    setSound(null, null)
                    enableVibration(false)
                }
            )
        }

        if (notificationManager.getNotificationChannel(SOUND_CHANNEL_ID) == null) {
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            val soundUri = Uri.parse("android.resource://${context.packageName}/${R.raw.blazz_app_sfx}")
            notificationManager.createNotificationChannel(
                NotificationChannel(
                    SOUND_CHANNEL_ID,
                    "BlazzGrabber alerts",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "Notifikasi selesai atau error"
                    setSound(soundUri, audioAttributes)
                }
            )
        }
    }

    private companion object {
        const val ACTION_NOTIFY = "com.blazzgrabber.NOTIFY"
        const val EXTRA_TYPE = "type"
        const val EXTRA_TEXT = "text"
        const val EXTRA_SOUND = "sound"
        const val TYPE_PROGRESS = "progress"
        const val SOUND_BLAZZ = "blazz"
        const val SOUND_NONE = "none"
        const val SILENT_CHANNEL_ID = "blazzgrabber_status"
        const val SOUND_CHANNEL_ID = "blazzgrabber_alerts"
        const val PROGRESS_NOTIFICATION_ID = 8301
        const val EVENT_NOTIFICATION_ID = 8302
        const val MAX_TEXT_LENGTH = 200
        const val LOG_TAG = "BlazzResponse"
        val SOUND_TYPES = setOf("complete", "success", "error")
    }
}