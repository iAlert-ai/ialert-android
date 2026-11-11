package ir.ialert.sdk

import org.json.JSONObject

/** A notification from the Gateway or the Inbox; [id] is the notification id. */
public data class IAlertMessage(
    val id: String,
    val title: String?,
    val body: String?,
    val image: String?,
    /** Custom data as JSON (an empty object when none). */
    val data: JSONObject,
    val collapseId: String?,
    val createdAt: String?,
    /** Where the SDK got it first. */
    val source: Source,
    /** Sound file name in `res/raw` (with or without extension), `default`, `none` (silent) or null (default). */
    val sound: String? = null,
) {
    public enum class Source { GATEWAY, INBOX, PUSH }
}

/** Device as returned by the iAlert API; [raw] has every field. */
public data class Device(val id: String, val raw: JSONObject)

public enum class AckEvent(public val wire: String) { DELIVERED("delivered"), READ("read"), CLICKED("clicked") }

public enum class ConnectionState { IDLE, CONNECTING, READY, CLOSED }

/** Fields for register/update; `null` leaves a field unchanged. Use [clearExternalUserId] to remove it. */
public data class DeviceFields(
    val externalUserId: String? = null,
    val clearExternalUserId: Boolean = false,
    /** Merged into existing tags; a `null` value removes the tag. Values: String, Number or Boolean. */
    val tags: Map<String, Any?>? = null,
    val locale: String? = null,
    val timezone: String? = null,
    val appVersion: String? = null,
)

/** Error from the iAlert API (`{ message, code, errors? }`) or local validation (status 0). */
public class IAlertException(
    public val status: Int,
    public val code: String,
    message: String,
    public val errors: JSONObject? = null,
) : Exception(message)

public fun interface MessageListener {
    public fun onMessage(message: IAlertMessage)
}

public fun interface ConnectionStateListener {
    public fun onStateChange(state: ConnectionState)
}

/** Removes a listener. */
public fun interface Subscription {
    public fun cancel()
}

/**
 * How the app gets native push (`GET /v1/push-config`). [fcmSource] is `app` when the app's
 * FCM credential is in the iAlert panel ([fcmClient] is its Firebase client from the uploaded
 * `google-services.json`), or null (Gateway and Inbox only).
 */
public data class PushConfig(val fcmSource: String?, val fcmClient: FcmClient?) {
    public data class FcmClient(val projectId: String, val applicationId: String, val apiKey: String, val senderId: String)
}

/** An Auth Push sign-in request carried by a message (`data.ialert_type == "auth_request"`). */
public data class AuthRequestInfo(
    val id: String,
    val numberMatching: Boolean,
    val expiresAt: String?,
    /** Your `context` values (IP, city, device…) to show the user. */
    val context: Map<String, String>,
) {
    public companion object {
        private val RESERVED = setOf("ialert_type", "auth_request_id", "number_matching", "expires_at", "ialert_id", "ialert_sound")

        /** The request a message carries, or null for other messages. */
        @JvmStatic
        public fun from(message: IAlertMessage): AuthRequestInfo? {
            val data = message.data
            if (data.optStringOrNull("ialert_type") != "auth_request") return null
            val id = data.optStringOrNull("auth_request_id") ?: return null
            val context = buildMap {
                for (key in data.keys()) {
                    if (key !in RESERVED) data.optStringOrNull(key)?.let { put(key, it) }
                }
            }

            return AuthRequestInfo(
                id = id,
                numberMatching = data.optStringOrNull("number_matching").let { it == "1" || it == "true" },
                expiresAt = data.optStringOrNull("expires_at"),
                context = context,
            )
        }
    }
}

/** Shows notifications for [IAlert.backgroundSync]; [AndroidNotifier] is the default on Android. */
public interface LocalNotifier {
    /** Ids of iAlert notifications currently in the tray. */
    public fun shownNotificationIds(): Set<String>

    public fun show(message: IAlertMessage)
}
