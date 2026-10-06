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
import java.util.UUID

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
            return
        }

        createChannels(context, notificationManager)

        val isProgress = type == TYPE_PROGRESS
        val notificationTag = intent.getStringExtra(EXTRA_NOTIFICATION_ID)
            ?.trim()
            ?.take(MAX_NOTIFICATION_ID_LENGTH)
            ?.takeIf { it.isNotEmpty() }
            ?: UUID.randomUUID().toString()

        val channelId = rawChannelId(type, notificationTag)
        val soundUri = notificationSoundUri(context)
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, channelId)
        } else {
            Notification.Builder(context).setSound(if (shouldPlaySound) soundUri else null)
        }

        val notification = builder
            .setSmallIcon(R.drawable.blazzgrabber_icon)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setWhen(System.currentTimeMillis())
            .setShowWhen(true)
            .setOnlyAlertOnce(isProgress)
            .setAutoCancel(!isProgress)
            .build()

        try {
            notificationManager.notify(notificationTag, NOTIFICATION_ID, notification)
            Log.i(
                LOG_TAG,
                "NOTIFY_TAMPIL type=$type id=$notificationTag sound=${if (shouldPlaySound) SOUND_BLAZZ else SOUND_NONE}"
            )
        } catch (exception: SecurityException) {
            Log.e(LOG_TAG, "NOTIFY_GAGAL alasan=IZIN_NOTIFIKASI_BELUM_DIBERIKAN")
        }
    }

    private fun createChannels(context: Context, notificationManager: NotificationManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        notificationManager.createNotificationChannel(
            NotificationChannel(
                rawChannelId(type, notificationTag),
                context.getString(R.string.notification_silent_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Status proses dari perintah ADB"
                setSound(null, null)
                enableVibration(false)
            }
        )

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val soundUri = notificationSoundUri(context)
        notificationManager.createNotificationChannel(
            NotificationChannel(
                "blazzgrabber_${rawChannelId(type, notificationTag)}",
                context.getString(R.string.notification_sound_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifikasi selesai atau error"
                setSound(soundUri, audioAttributes)
            }
        )
    }

    private fun notificationSoundUri(context: Context): Uri =
        Uri.parse("android.resource://${context.packageName}/raw/blazz_app_sfx")

    private companion object {
        const val ACTION_NOTIFY = "com.blazzgrabber.NOTIFY"
        const val EXTRA_TYPE = "type"
        const val EXTRA_TEXT = "text"
        const val EXTRA_SOUND = "sound"
        const val TYPE_PROGRESS = "progress"
        const val SOUND_BLAZZ = "blazz"
        const val SOUND_NONE = "none"
        const val EXTRA_NOTIFICATION_ID = "id"
        const val NOTIFICATION_ID = 8301
        const val MAX_TEXT_LENGTH = 200
        const val MAX_NOTIFICATION_ID_LENGTH = 100
        const val LOG_TAG = "BlazzResponse"
        val SOUND_TYPES = setOf("complete", "success", "error")

        private fun rawChannelId(type: String, tag: String): String = when (type) {
            TYPE_PROGRESS -> "progress-$tag"
            else -> "download-$tag"
        }
    }
}