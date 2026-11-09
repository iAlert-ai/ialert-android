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
) {
    public enum class Source { GATEWAY, INBOX }
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
