package com.blazzgrabber

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors

class NotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_NOTIFY) return

        val params = readParams(context, intent) ?: return

        val imageUrl = intent.getStringExtra(EXTRA_IMAGE)
            ?.trim()
            ?.take(MAX_IMAGE_URL_LENGTH)
            ?.takeIf { it.isNotEmpty() }

        when {
            // Tanpa `image`: perilaku lama, notifikasi langsung tampil tanpa jaringan.
            imageUrl == null -> post(context, params, null)
            !NotificationImageLoader.isSupported(imageUrl) -> {
                Log.e(LOG_TAG, "NOTIFY_TANPA_GAMBAR alasan=URL_TIDAK_DIDUKUNG")
                post(context, params, null)
            }
            else -> {
                // Gambar diambil di luar main thread agar broadcast tidak diblokir.
                // Notifikasi tetap diposting sekali: dengan gambar bila unduhan
                // berhasil, atau teks saja bila gagal.
                val pendingResult = goAsync()
                NotificationImageLoader.execute {
                    try {
                        post(context, params, NotificationImageLoader.load(imageUrl))
                    } catch (exception: Exception) {
                        Log.e(LOG_TAG, "NOTIFY_GAGAL alasan=${exception.javaClass.simpleName}")
                    } finally {
                        pendingResult.finish()
                    }
                }
            }
        }
    }

    /** Membaca dan memvalidasi extra. Mengembalikan null bila notifikasi tidak boleh tampil. */
    private fun readParams(context: Context, intent: Intent): NotifParams? {
        val type = intent.getStringExtra(EXTRA_TYPE)
            ?.trim()
            ?.lowercase(Locale.ROOT)
            .orEmpty()
        val text = intent.getStringExtra(EXTRA_TEXT)?.trim()?.take(MAX_TEXT_LENGTH)
        if (type.isBlank() || text.isNullOrBlank()) {
            Log.e(LOG_TAG, "NOTIFY_GAGAL alasan=TYPE_ATAU_TEXT_KOSONG")
            return null
        }

        val title = intent.getStringExtra(EXTRA_TITLE)
            ?.trim()
            ?.take(MAX_TEXT_LENGTH)
            ?.takeIf { it.isNotEmpty() }
            ?: context.getString(R.string.app_name)

        val sound = intent.getStringExtra(EXTRA_SOUND)?.trim()?.lowercase(Locale.ROOT)
        if (sound != null && sound != SOUND_BLAZZ && sound != SOUND_NONE) {
            Log.e(LOG_TAG, "NOTIFY_GAGAL alasan=SOUND_TIDAK_DIKENAL")
            return null
        }

        val soundResourceName = when {
            sound == SOUND_NONE -> null
            type in DOWNLOAD_TYPES -> DOWNLOAD_SOUND_RESOURCE
            type in SUCCESS_TYPES -> SUCCESS_SOUND_RESOURCE
            type in ERROR_TYPES -> ERROR_SOUND_RESOURCE
            sound == SOUND_BLAZZ -> DOWNLOAD_SOUND_RESOURCE
            else -> null
        }
        val channelId = soundResourceName?.let { soundChannelId(context, it) }
            ?: context.getString(R.string.notification_silent_channel_id)
        val notificationTag = intent.getStringExtra(EXTRA_NOTIFICATION_ID)
            ?.trim()
            ?.take(MAX_NOTIFICATION_ID_LENGTH)
            ?.takeIf { it.isNotEmpty() }
            ?: UUID.randomUUID().toString()

        return NotifParams(
            type = type,
            title = title,
            text = text,
            tag = notificationTag,
            isProgress = type in DOWNLOAD_TYPES,
            channelId = channelId,
            soundUri = soundResourceName?.let { notificationSoundUri(context, it) }
        )
    }

    private fun post(context: Context, params: NotifParams, bitmap: Bitmap?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(LOG_TAG, "NOTIFY_GAGAL alasan=IZIN_NOTIFIKASI_BELUM_DIBERIKAN")
            return
        }

        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createChannels(context, notificationManager)

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, params.channelId)
        } else {
            Notification.Builder(context).setSound(params.soundUri)
        }

        if (bitmap != null) builder.setLargeIcon(bitmap)

        val notification = builder
            .setSmallIcon(R.drawable.blazzgrabber_icon)
            .setContentTitle(params.title)
            .setContentText(params.text)
            .setStyle(Notification.BigTextStyle().bigText(params.text))
            .setWhen(System.currentTimeMillis())
            .setShowWhen(true)
            .setOnlyAlertOnce(params.isProgress)
            .setAutoCancel(!params.isProgress)
            .build()

        try {
            notificationManager.notify(params.tag, NOTIFICATION_ID, notification)
            Log.i(
                LOG_TAG,
                "NOTIFY_TAMPIL type=${params.type} id=${params.tag} " +
                    "sound=${params.soundUri?.lastPathSegment ?: SOUND_NONE} " +
                    "gambar=${if (bitmap != null) "ya" else "tidak"}"
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

    private data class NotifParams(
        val type: String,
        val title: String,
        val text: String,
        val tag: String,
        val isProgress: Boolean,
        val channelId: String,
        val soundUri: Uri?
    )

    private companion object {
        const val ACTION_NOTIFY = "com.blazzgrabber.NOTIFY"
        const val EXTRA_TYPE = "type"
        const val EXTRA_TITLE = "title"
        const val EXTRA_TEXT = "text"
        const val EXTRA_SOUND = "sound"
        const val EXTRA_IMAGE = "image"
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
        const val MAX_IMAGE_URL_LENGTH = 2048
        const val LOG_TAG = "BlazzResponse"
        val DOWNLOAD_TYPES = setOf(TYPE_PROGRESS, TYPE_DOWNLOAD)
        val SUCCESS_TYPES = setOf("complete", "success")
        val ERROR_TYPES = setOf("error")
    }
}

/**
 * Mengambil gambar thumbnail notifikasi dari URL. Bersifat opsional: setiap kegagalan
 * dikembalikan sebagai null sehingga notifikasi tetap tampil dengan teks saja.
 *
 * Gambar hanya disimpan di memori (tidak pernah menjadi file), jadi tidak pernah masuk
 * MediaStore/galeri dan tidak memengaruhi urutan galeri file unduhan.
 */
private object NotificationImageLoader {
    private const val LOG_TAG = "BlazzResponse"
    private const val CONNECT_TIMEOUT_MS = 3000
    private const val READ_TIMEOUT_MS = 3000
    private const val MAX_BYTES = 5 * 1024 * 1024
    private const val MAX_EDGE_PX = 256
    private const val CACHE_ENTRIES = 8
    private const val FAILURE_TTL_MS = 30_000L
    private const val BUFFER_BYTES = 16 * 1024

    private data class Entry(val bitmap: Bitmap?, val at: Long)

    // Satu thread: unduhan dan pemostingan notifikasi berjalan berurutan, sehingga
    // update dengan `id` yang sama tidak saling menimpa secara terbalik.
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "blazz-notif-image").apply { isDaemon = true }
    }

    private val cache = object : LinkedHashMap<String, Entry>(CACHE_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>): Boolean =
            size > CACHE_ENTRIES
    }

    fun execute(task: () -> Unit) = executor.execute(task)

    fun isSupported(url: String): Boolean =
        when (Uri.parse(url).scheme?.lowercase(Locale.ROOT)) {
            "http", "https" -> true
            else -> false
        }

    fun load(url: String): Bitmap? {
        cached(url)?.let { return it.bitmap }

        val bitmap = try {
            fetch(url)
        } catch (exception: Exception) {
            Log.e(LOG_TAG, "NOTIFY_TANPA_GAMBAR alasan=${exception.javaClass.simpleName}")
            null
        }
        store(url, bitmap)
        return bitmap
    }

    @Synchronized
    private fun cached(url: String): Entry? {
        val entry = cache[url] ?: return null
        if (entry.bitmap == null && System.currentTimeMillis() - entry.at > FAILURE_TTL_MS) {
            cache.remove(url)
            return null
        }
        return entry
    }

    @Synchronized
    private fun store(url: String, bitmap: Bitmap?) {
        cache[url] = Entry(bitmap, System.currentTimeMillis())
    }

    private fun fetch(url: String): Bitmap? {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("Accept", "image/*")

            val code = connection.responseCode
            if (code !in 200..299) {
                Log.e(LOG_TAG, "NOTIFY_TANPA_GAMBAR alasan=HTTP_$code")
                return null
            }
            val declared = connection.contentLength.toLong()
            if (declared > MAX_BYTES) {
                Log.e(LOG_TAG, "NOTIFY_TANPA_GAMBAR alasan=TERLALU_BESAR")
                return null
            }

            val bytes = connection.inputStream.use { it.readLimited() } ?: return null
            return decode(bytes)
        } finally {
            connection.disconnect()
        }
    }

    /** Membaca seluruh body, berhenti bila melewati [MAX_BYTES]. */
    private fun InputStream.readLimited(): ByteArray? {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER_BYTES)
        var total = 0
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            total += read
            if (total > MAX_BYTES) {
                Log.e(LOG_TAG, "NOTIFY_TANPA_GAMBAR alasan=TERLALU_BESAR")
                return null
            }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun decode(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            Log.e(LOG_TAG, "NOTIFY_TANPA_GAMBAR alasan=BUKAN_GAMBAR")
            return null
        }

        var sampleSize = 1
        val longestEdge = maxOf(bounds.outWidth, bounds.outHeight)
        while (longestEdge / sampleSize > MAX_EDGE_PX) sampleSize *= 2

        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        if (bitmap == null) {
            Log.e(LOG_TAG, "NOTIFY_TANPA_GAMBAR alasan=DEKODE_GAGAL")
        }
        return bitmap
    }
}
