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

        val title = intent.getStringExtra(EXTRA_TITLE)
            ?.trim()
            ?.take(MAX_TEXT_LENGTH)
            ?.takeIf { it.isNotEmpty() }
            ?: context.getString(R.string.app_name)

        val sound = intent.getStringExtra(EXTRA_SOUND)?.trim()?.lowercase(Locale.ROOT)
        if (sound != null && sound != SOUND_BLAZZ && sound != SOUND_NONE) {
            Log.e(LOG_TAG, "NOTIFY_GAGAL alasan=SOUND_TIDAK_DIKENAL")
            return
        }

        val soundResourceName = when {
            sound == SOUND_NONE -> null
            type in DOWNLOAD_TYPES -> DOWNLOAD_SOUND_RESOURCE
            type in SUCCESS_TYPES -> SUCCESS_SOUND_RESOURCE
            type in ERROR_TYPES -> ERROR_SOUND_RESOURCE
            sound == SOUND_BLAZZ -> DOWNLOAD_SOUND_RESOURCE
            else -> null
        }
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val isProgress = type in DOWNLOAD_TYPES
        val notificationTag = intent.getStringExtra(EXTRA_NOTIFICATION_ID)
            ?.trim()
            ?.take(MAX_NOTIFICATION_ID_LENGTH)
            ?.takeIf { it.isNotEmpty() }
            ?: UUID.randomUUID().toString()

        createChannels(context, notificationManager)

        val channelId = soundResourceName?.let { soundChannelId(context, it) }
            ?: context.getString(R.string.notification_silent_channel_id)
        val soundUri = soundResourceName?.let { notificationSoundUri(context, it) }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, channelId)
        } else {
            Notification.Builder(context).setSound(soundUri)
        }

        val notification = builder
            .setSmallIcon(R.drawable.blazzgrabber_icon)
            .setContentTitle(title)
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
                "NOTIFY_TAMPIL type=$type id=$notificationTag sound=${soundResourceName ?: SOUND_NONE}"
            )
        } catch (exception: SecurityException) {
            Log.e(LOG_TAG, "NOTIFY_GAGAL alasan=IZIN_NOTIFIKASI_BELUM_DIBERIKAN")
        }
    }

    private fun createChannels(context: Context, notificationManager: NotificationManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        notificationManager.createNotificationChannel(
            NotificationChannel(
                context.getString(R.string.notification_silent_channel_id),
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

        notificationManager.createNotificationChannels(
            listOf(
                createSoundChannel(
                    context,
                    context.getString(R.string.notification_sound_channel_id),
                    context.getString(R.string.notification_sound_channel_name),
                    DOWNLOAD_SOUND_RESOURCE,
                    audioAttributes
                ),
                createSoundChannel(
                    context,
                    soundChannelId(context, SUCCESS_SOUND_RESOURCE),
                    context.getString(R.string.notification_success_channel_name),
                    SUCCESS_SOUND_RESOURCE,
                    audioAttributes
                ),
                createSoundChannel(
                    context,
                    soundChannelId(context, ERROR_SOUND_RESOURCE),
                    context.getString(R.string.notification_error_channel_name),
                    ERROR_SOUND_RESOURCE,
                    audioAttributes
                )
            )
        )
    }

    private fun createSoundChannel(
        context: Context,
        channelId: String,
        channelName: String,
        soundResourceName: String,
        audioAttributes: AudioAttributes
    ) = NotificationChannel(
        channelId,
        channelName,
        NotificationManager.IMPORTANCE_DEFAULT
    ).apply {
        description = "Notifikasi suara $channelName"
        setSound(notificationSoundUri(context, soundResourceName), audioAttributes)
    }

    private fun soundChannelId(context: Context, soundResourceName: String): String {
        val baseId = context.getString(R.string.notification_sound_channel_id)
        return when (soundResourceName) {
            SUCCESS_SOUND_RESOURCE -> "${baseId}_success"
            ERROR_SOUND_RESOURCE -> "${baseId}_error"
            else -> baseId
        }
    }

    private fun notificationSoundUri(context: Context, soundResourceName: String): Uri =
        Uri.parse("android.resource://${context.packageName}/raw/$soundResourceName")

    private companion object {
        const val ACTION_NOTIFY = "com.blazzgrabber.NOTIFY"
        const val EXTRA_TYPE = "type"
        const val EXTRA_TITLE = "title"
        const val EXTRA_TEXT = "text"
        const val EXTRA_SOUND = "sound"
        const val TYPE_PROGRESS = "progress"
        const val TYPE_DOWNLOAD = "download"
        const val SOUND_BLAZZ = "blazz"
        const val SOUND_NONE = "none"
        const val DOWNLOAD_SOUND_RESOURCE = "blazz_app_sfx"
        const val SUCCESS_SOUND_RESOURCE = "blazz_app_sfx_success"
        const val ERROR_SOUND_RESOURCE = "blazz_app_sfx_error"
        const val EXTRA_NOTIFICATION_ID = "id"
        const val NOTIFICATION_ID = 8301
        const val MAX_TEXT_LENGTH = 200
        const val MAX_NOTIFICATION_ID_LENGTH = 100
        const val LOG_TAG = "BlazzResponse"
        val DOWNLOAD_TYPES = setOf(TYPE_PROGRESS, TYPE_DOWNLOAD)
        val SUCCESS_TYPES = setOf("complete", "success")
        val ERROR_TYPES = setOf("error")
    }
}