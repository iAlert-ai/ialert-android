package ir.ialert.sdk

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

internal class InboxEntry(
    val id: String,
    val title: String?,
    val body: String?,
    val image: String?,
    val data: JSONObject,
    val collapseId: String?,
    val sound: String?,
    /** APNs/FCM/Web Push already took it, so the system showed it. */
    val pushed: Boolean,
    val deliveredAt: String?,
    val createdAt: String?,
)

/** Thin REST client for the public-key endpoints of API v1. */
internal class ApiClient(baseUrl: String, private val publicKey: String, private val transport: HttpTransport) {
    private val baseUrl = baseUrl.trimEnd('/')

    suspend fun register(body: JSONObject): Pair<Device, String> {
        val result = request("POST", "/devices", body)
        return device(result.getJSONObject("data")) to result.getString("device_token")
    }

    suspend fun update(deviceId: String, deviceToken: String, body: JSONObject): Device =
        device(request("PATCH", "/devices/${escape(deviceId)}", body, deviceToken).getJSONObject("data"))

    suspend fun unsubscribe(deviceId: String, deviceToken: String): Device =
        device(request("POST", "/devices/${escape(deviceId)}/unsubscribe", null, deviceToken).getJSONObject("data"))

    /** Every unread Inbox page (newest first), following `links.next`, up to [maxPages]. */
    suspend fun inbox(deviceId: String, deviceToken: String, maxPages: Int = 5): List<InboxEntry> {
        val entries = mutableListOf<InboxEntry>()
        var next: String? = "$baseUrl/devices/${escape(deviceId)}/inbox?per_page=100&unread=1"
        var page = 0

        while (next != null && page < maxPages) {
            val result = request("GET", next, null, deviceToken)
            val data = result.getJSONArray("data")
            for (i in 0 until data.length()) entries += inboxEntry(data.getJSONObject(i))
            next = result.optJSONObject("links")?.optStringOrNull("next")
            page++
        }

        return entries
    }

    suspend fun ack(deviceId: String, deviceToken: String, ids: List<String>, event: AckEvent) {
        for (chunk in ids.chunked(100)) {
            request("POST", "/devices/${escape(deviceId)}/inbox/ack", JSONObject().put("ids", JSONArray(chunk)).put("event", event.wire), deviceToken)
        }
    }

    /** Join or leave a Live Activity channel (its FCM topic); the device must be registered with its FCM token. */
    suspend fun liveActivity(deviceId: String, deviceToken: String, channelId: String, subscribe: Boolean) {
        request(if (subscribe) "POST" else "DELETE", "/devices/${escape(deviceId)}/live-activity-channels/${escape(channelId)}", null, deviceToken)
    }

    /** Approve or deny an Auth Push request; returns the recorded status. */
    suspend fun respondToAuthRequest(deviceId: String, deviceToken: String, requestId: String, approve: Boolean, code: String?): String {
        val body = JSONObject().put("decision", if (approve) "approve" else "deny")
        if (code != null) body.put("code", code)
        val result = request("POST", "/devices/${escape(deviceId)}/auth-requests/${escape(requestId)}", body, deviceToken)
        return result.optJSONObject("data")?.optStringOrNull("status") ?: "pending"
    }

    /** `GET /push-config`: how native push is set up for this app. */
    suspend fun pushConfig(): PushConfig {
        val data = request("GET", "/push-config", null).optJSONObject("data") ?: JSONObject()
        val fcm = data.optJSONObject("fcm")
        val client = fcm?.optJSONObject("client")
        return PushConfig(
            fcmSource = fcm?.optStringOrNull("source"),
            fcmClient = client?.let {
                PushConfig.FcmClient(
                    projectId = it.optStringOrNull("project_id") ?: return@let null,
                    applicationId = it.optStringOrNull("application_id") ?: return@let null,
                    apiKey = it.optStringOrNull("api_key") ?: return@let null,
                    senderId = it.optStringOrNull("sender_id") ?: return@let null,
                )
            },
        )
    }

    private suspend fun request(method: String, pathOrUrl: String, body: JSONObject?, deviceToken: String? = null): JSONObject {
        val headers = buildMap {
            put("Accept", "application/json")
            put("Authorization", "Bearer $publicKey")
            if (body != null) put("Content-Type", "application/json")
            if (deviceToken != null) put("X-Device-Token", deviceToken)
        }
        val url = if (pathOrUrl.startsWith("http")) pathOrUrl else baseUrl + pathOrUrl

        val response = try {
            transport.send(HttpRequest(method, url, headers, body?.toString()))
        } catch (e: Exception) {
            throw IAlertException(0, "network_error", e.message ?: "Network error")
        }

        val payload = try {
            JSONObject(response.body)
        } catch (e: Exception) {
            null
        }

        if (response.status !in 200..299) {
            throw IAlertException(
                response.status,
                payload?.optStringOrNull("code") ?: "server_error",
                payload?.optStringOrNull("message") ?: "HTTP ${response.status}",
                payload?.optJSONObject("errors"),
            )
        }

        return payload ?: throw IAlertException(response.status, "invalid_response", "Response is not JSON")
    }

    private fun device(json: JSONObject) = Device(json.getString("id"), json)

    private fun inboxEntry(json: JSONObject): InboxEntry {
        val content = json.optJSONObject("content") ?: JSONObject()
        return InboxEntry(
            id = json.getString("id"),
            title = content.optStringOrNull("title"),
            body = content.optStringOrNull("body"),
            image = content.optStringOrNull("image"),
            data = content.optJSONObject("data") ?: JSONObject(),
            collapseId = json.optStringOrNull("collapse_id"),
            sound = content.optStringOrNull("sound"),
            pushed = json.optBoolean("pushed", false),
            deliveredAt = json.optStringOrNull("delivered_at"),
            createdAt = json.optStringOrNull("created_at"),
        )
    }

    private fun escape(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}

/** `optString` returns "null" for JSON null; this returns Kotlin null. */
internal fun JSONObject.optStringOrNull(key: String): String? =
    if (!has(key) || isNull(key)) null else opt(key)?.toString()
