package ir.ialert.sdk

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Shows iAlert messages as system notifications, on the channels the server names in FCM
 * messages: `ialert_default`, `ialert_silent` and `ialert_sound_<name>` (a sound in `res/raw`).
 * Each notification is tagged with its iAlert id, so a message is never in the tray twice.
 * A tap opens the app (or `data.url`) and is acked as clicked.
 */
public class AndroidNotifier(context: Context) : LocalNotifier {
    private val context = context.applicationContext
    private val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    /** Create the default and silent channels, plus one per sound in `res/raw`. */
    public fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        channel(DEFAULT_CHANNEL)
        channel(SILENT_CHANNEL)
        rawSounds().forEach { channel(SOUND_CHANNEL_PREFIX + it) }
    }

    override fun shownNotificationIds(): Set<String> = manager.activeNotifications.mapNotNull { it.tag }.toSet()

    override fun show(message: IAlertMessage) {
        if (message.title == null && message.body == null) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val channelId = channelFor(message.sound)
        channel(channelId)

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(smallIcon())
            .setContentTitle(message.title)
            .setContentText(message.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message.body))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openIntent(message))

        if (message.sound == "none") builder.setSilent(true)
        soundUri(message.sound)?.let { builder.setSound(it) }

        message.image?.let(::download)?.let { bitmap ->
            builder.setLargeIcon(bitmap).setStyle(NotificationCompat.BigPictureStyle().bigPicture(bitmap).setSummaryText(message.body))
        }

        try {
            NotificationManagerCompat.from(context).notify(message.id, NOTIFICATION_ID, builder.build())
        } catch (_: SecurityException) {
            // Notifications not permitted.
        }
    }

    /**
     * A Live Activity broadcast as one ongoing notification per channel, updated in place
     * and removed on `end`. Used when the app registers no [IAlert.onLiveActivityUpdate] listener.
     */
    public fun showLiveActivity(update: LiveActivityUpdate) {
        val manager = NotificationManagerCompat.from(context)
        val tag = LIVE_TAG_PREFIX + update.channelId
        if (update.isEnd) {
            manager.cancel(tag, NOTIFICATION_ID)
            return
        }
        if (update.title == null && update.body == null) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        channel(DEFAULT_CHANNEL)
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val builder = NotificationCompat.Builder(context, DEFAULT_CHANNEL)
            .setSmallIcon(smallIcon())
            .setContentTitle(update.title)
            .setContentText(update.body)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setWhen(update.timestamp * 1000)
            .setShowWhen(update.timestamp > 0)
        launch?.let { builder.setContentIntent(PendingIntent.getActivity(context, tag.hashCode(), it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)) }

        try {
            manager.notify(tag, NOTIFICATION_ID, builder.build())
        } catch (_: SecurityException) {
            // Notifications not permitted.
        }
    }

    private fun openIntent(message: IAlertMessage): PendingIntent {
        val intent = Intent(context, IAlertOpenActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(EXTRA_ID, message.id)
        for (key in message.data.keys()) {
            message.data.optStringOrNull(key)?.let { intent.putExtra(key, it) }
        }

        return PendingIntent.getActivity(context, message.id.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun channel(id: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || manager.getNotificationChannel(id) != null) return

        val name = when (id) {
            DEFAULT_CHANNEL -> context.getString(R.string.ialert_channel_default)
            SILENT_CHANNEL -> context.getString(R.string.ialert_channel_silent)
            else -> context.getString(R.string.ialert_channel_sound, id.removePrefix(SOUND_CHANNEL_PREFIX))
        }
        val importance = if (id == SILENT_CHANNEL) NotificationManager.IMPORTANCE_LOW else NotificationManager.IMPORTANCE_HIGH
        val channel = NotificationChannel(id, name, importance)

        when (id) {
            SILENT_CHANNEL -> channel.setSound(null, null)
            DEFAULT_CHANNEL -> Unit
            else -> soundUri(id.removePrefix(SOUND_CHANNEL_PREFIX))?.let {
                val attributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                channel.setSound(it, attributes)
            }
        }

        manager.createNotificationChannel(channel)
    }

    private fun channelFor(sound: String?): String = when {
        sound == null || sound == "default" -> DEFAULT_CHANNEL
        sound == "none" -> SILENT_CHANNEL
        rawId(rawName(sound)) == 0 -> DEFAULT_CHANNEL
        else -> SOUND_CHANNEL_PREFIX + rawName(sound)
    }

    private fun soundUri(sound: String?): Uri? {
        if (sound == null || sound == "default" || sound == "none") return null
        val id = rawId(rawName(sound)).takeIf { it != 0 } ?: return null
        return Uri.parse("${ContentResolver.SCHEME_ANDROID_RESOURCE}://${context.packageName}/$id")
    }

    @Suppress("DiscouragedApi")
    private fun rawId(name: String): Int = context.resources.getIdentifier(name, "raw", context.packageName)

    /** Names in the app's `R.raw`, by reflection (empty when R is not where the package name says). */
    private fun rawSounds(): List<String> = runCatching {
        Class.forName(context.packageName + ".R" + "$" + "raw").fields.map { it.name }
    }.getOrDefault(emptyList())

    private fun smallIcon(): Int {
        val meta = runCatching {
            context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA).metaData
        }.getOrNull()
        return meta?.getInt(ICON_META, 0)?.takeIf { it != 0 }
            ?: meta?.getInt("com.google.firebase.messaging.default_notification_icon", 0)?.takeIf { it != 0 }
            ?: context.applicationInfo.icon.takeIf { it != 0 }
            ?: android.R.drawable.ic_dialog_info
    }

    private fun download(url: String): Bitmap? = runCatching {
        imageClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) null else response.body?.byteStream()?.use(BitmapFactory::decodeStream)
        }
    }.getOrNull()

    public companion object {
        public const val DEFAULT_CHANNEL: String = "ialert_default"
        public const val SILENT_CHANNEL: String = "ialert_silent"
        public const val SOUND_CHANNEL_PREFIX: String = "ialert_sound_"

        /** Optional `<meta-data>` naming a monochrome drawable for the small icon. */
        public const val ICON_META: String = "ir.ialert.sdk.notification_icon"
        internal const val EXTRA_ID = "ialert_id"
        private const val LIVE_TAG_PREFIX = "ialert_live:"
        private const val NOTIFICATION_ID = 0x1A1E

        private val imageClient by lazy { OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build() }

        /** `res/raw` name of a sound file, as the server builds it: lower case, no extension. */
        internal fun rawName(sound: String): String = sound.replace(Regex("\\.[A-Za-z0-9]+$"), "").lowercase()
    }
}
