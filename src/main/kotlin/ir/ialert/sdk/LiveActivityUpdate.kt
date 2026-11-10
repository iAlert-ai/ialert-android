package ir.ialert.sdk

import org.json.JSONObject

/**
 * One Live Activity broadcast as delivered to Android: an FCM data message with
 * `ialert_type = live_activity`. Build or update one ongoing notification per
 * [channelId] (use [notificationId]) and remove it when [isEnd].
 */
public data class LiveActivityUpdate(
    val channelId: String,
    val broadcastId: String?,
    val event: Event,
    val contentState: JSONObject,
    val title: String?,
    val body: String?,
    val sound: String?,
    /** When the update was sent (Unix seconds); drop updates older than one already shown. */
    val timestamp: Long,
    val staleDate: Long?,
    val dismissalDate: Long?,
    val relevanceScore: Double?,
) {
    public enum class Event { UPDATE, END }

    val isEnd: Boolean get() = event == Event.END

    /** A stable id for `NotificationManager.notify()`: every update of the channel replaces the same notification. */
    val notificationId: Int get() = channelId.hashCode()

    public companion object {
        /** Reads `RemoteMessage.data`; `null` when the message is not an iAlert Live Activity update. */
        @JvmStatic
        public fun from(data: Map<String, String>): LiveActivityUpdate? {
            if (data["ialert_type"] != "live_activity") return null
            val channelId = data["ialert_channel"] ?: return null
            val event = when (data["event"]) {
                "update" -> Event.UPDATE
                "end" -> Event.END
                else -> return null
            }
            val state = data["content_state"]?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()

            return LiveActivityUpdate(
                channelId = channelId,
                broadcastId = data["ialert_broadcast"],
                event = event,
                contentState = state,
                title = data["title"],
                body = data["body"],
                sound = data["sound"],
                timestamp = data["timestamp"]?.toLongOrNull() ?: 0,
                staleDate = data["stale_date"]?.toLongOrNull(),
                dismissalDate = data["dismissal_date"]?.toLongOrNull(),
                relevanceScore = data["relevance_score"]?.toDoubleOrNull(),
            )
        }
    }
}
