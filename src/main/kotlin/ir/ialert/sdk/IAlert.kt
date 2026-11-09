package ir.ialert.sdk

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

/**
 * iAlert Android SDK. Registers the app as an `android` device, keeps a Gateway
 * socket open while the app runs, syncs the Inbox after every (re)connect and
 * hands each notification to listeners exactly once.
 *
 * Listeners run on a background thread; post to the main thread to touch UI.
 */
public class IAlert(private val config: Config) {
    public constructor(publicKey: String, storage: KeyValueStorage) : this(Config(publicKey, storage))

    public data class Config(
        /** Public key of the app (`ia_pk_…`). */
        val publicKey: String,
        /** Persists the registration and dedupe ids; back it with SharedPreferences. */
        val storage: KeyValueStorage,
        val apiUrl: String = "https://api.ialert.ir/api/v1",
        val gatewayUrl: String = "wss://gw.ialert.ir/v1/connect",
        val transport: HttpTransport = OkHttpTransport(),
        val webSocketFactory: WebSocketFactory = OkHttpWebSocketFactory(),
        val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { Thread(it, "ialert").apply { isDaemon = true } },
        /** Connect to the Gateway right after [register]. */
        val autoConnect: Boolean = true,
        /** Ack `delivered` for every message handed to listeners. */
        val autoAckDelivered: Boolean = true,
        /** Client ping interval; the Gateway drops a socket after about 75 s of silence. */
        val pingIntervalMs: Long = 30_000,
        val minReconnectMs: Long = 1_000,
        val maxReconnectMs: Long = 60_000,
    )

    private class Registration(val deviceId: String, val deviceToken: String)

    private val api = ApiClient(config.apiUrl, config.publicKey, config.transport)
    private val prefix = "ialert:${config.publicKey.takeLast(12)}:"
    private val lock = Any()
    private val messageListeners = CopyOnWriteArraySet<MessageListener>()
    private val stateListeners = CopyOnWriteArraySet<ConnectionStateListener>()
    private var gateway: GatewayConnection? = null
    private var registration: Registration?
    private var seen: MutableList<String>
    private var syncing = false

    init {
        if (!config.publicKey.startsWith("ia_pk_")) {
            throw IAlertException(0, "invalid_public_key", "Use the app's public key (ia_pk_…); secret keys must never ship in apps.")
        }

        registration = read("registration")?.let {
            runCatching { JSONObject(it) }.getOrNull()?.let { json -> Registration(json.getString("device_id"), json.getString("device_token")) }
        }
        seen = read("seen")?.let { raw ->
            runCatching { JSONArray(raw) }.getOrNull()?.let { array -> MutableList(array.length()) { array.getString(it) } }
        } ?: mutableListOf()
    }

    /** The registered device id, or null before [register]. */
    public val deviceId: String? get() = synchronized(lock) { registration?.deviceId }

    /**
     * Register (or re-register) this install. Safe on every launch. [pushToken] is the
     * FCM token once native push is wired; without it a stable installation id is used.
     */
    public suspend fun register(pushToken: String? = null, fields: DeviceFields = DeviceFields()): Device {
        val body = body(fields)
            .put("platform", "android")
            .put("push_token", pushToken ?: installationId())
            .put("sdk_version", SDK_VERSION)
        if (fields.locale == null) body.put("locale", Locale.getDefault().toLanguageTag())
        if (fields.timezone == null) body.put("timezone", TimeZone.getDefault().id)
        androidBuildField("MODEL")?.let { body.put("device_model", it) }
        androidBuildField("RELEASE", version = true)?.let { body.put("os_version", it) }

        val (device, token) = api.register(body)
        synchronized(lock) {
            registration = Registration(device.id, token)
            write("registration", JSONObject().put("device_id", device.id).put("device_token", token).toString())
        }

        if (config.autoConnect) connect()

        return device
    }

    /** Update the external user id, tags (merged) or locale of this device. */
    public suspend fun update(fields: DeviceFields): Device {
        val body = body(fields)
        val registration = requireRegistration()
        return withToken { api.update(registration.deviceId, registration.deviceToken, body) }
    }

    /** Merge tags; a `null` value removes the tag. */
    public suspend fun setTags(tags: Map<String, Any?>): Device = update(DeviceFields(tags = tags))

    public suspend fun setExternalUserId(externalUserId: String?): Device =
        update(if (externalUserId == null) DeviceFields(clearExternalUserId = true) else DeviceFields(externalUserId = externalUserId))

    /** Stop receiving notifications; call [register] to subscribe again. */
    public suspend fun unsubscribe(): Device {
        val registration = requireRegistration()
        disconnect()
        return withToken { api.unsubscribe(registration.deviceId, registration.deviceToken) }
    }

    /** Open the Gateway socket (done by [register] unless `autoConnect` is off). */
    public fun connect() {
        val registration = requireRegistration()
        val gateway = synchronized(lock) {
            if (gateway != null) return
            GatewayConnection(
                url = config.gatewayUrl,
                deviceToken = registration.deviceToken,
                factory = config.webSocketFactory,
                scheduler = config.scheduler,
                handlers = object : GatewayHandlers {
                    override fun onReady() = launch { runCatching { syncInbox() } }

                    override fun onMessage(message: JSONObject) {
                        receive(fromGateway(message))
                    }

                    override fun onUnauthorized() {
                        synchronized(lock) { this@IAlert.gateway = null }
                        forget()
                        emit(ConnectionState.CLOSED)
                    }

                    override fun onStateChange(state: ConnectionState) = emit(state)
                },
                pingIntervalMs = config.pingIntervalMs,
                minReconnectMs = config.minReconnectMs,
                maxReconnectMs = config.maxReconnectMs,
            ).also { this.gateway = it }
        }
        gateway.start()
    }

    public fun disconnect() {
        val gateway = synchronized(lock) { gateway.also { this.gateway = null } }
        gateway?.stop()
    }

    /**
     * Pull unread Inbox entries and hand the new ones to listeners, oldest first.
     * Returns an empty list when a sync is already running.
     */
    public suspend fun syncInbox(): List<IAlertMessage> {
        synchronized(lock) {
            if (syncing) return emptyList()
            syncing = true
        }

        try {
            val registration = requireRegistration()
            val entries = withToken { api.inbox(registration.deviceId, registration.deviceToken) }
            val received = entries.asReversed().map(::fromInbox).filter { receive(it, ack = false) }

            if (config.autoAckDelivered) {
                runCatching { ack(entries.filter { it.deliveredAt == null }.map { it.id }, AckEvent.DELIVERED) }
            }

            return received
        } finally {
            synchronized(lock) { syncing = false }
        }
    }

    /** Report that the user read or clicked notifications (by notification id). */
    public suspend fun ack(ids: List<String>, event: AckEvent) {
        if (ids.isEmpty()) return
        val registration = requireRegistration()
        val gateway = synchronized(lock) { gateway }

        if (gateway != null && gateway.isReady) {
            gateway.ack(ids, event)
            return
        }

        withToken { api.ack(registration.deviceId, registration.deviceToken, ids, event) }
    }

    /** Listen for notifications; cancel the returned subscription to stop. */
    public fun onMessage(listener: MessageListener): Subscription {
        messageListeners += listener
        return Subscription { messageListeners -= listener }
    }

    public fun onConnectionStateChange(listener: ConnectionStateListener): Subscription {
        stateListeners += listener
        return Subscription { stateListeners -= listener }
    }

    /** Dedupe by notification id across Gateway, Inbox and restarts. */
    private fun receive(message: IAlertMessage, ack: Boolean = true): Boolean {
        synchronized(lock) {
            if (message.id in seen) return false
            seen.add(message.id)
            if (seen.size > SEEN_LIMIT) seen = seen.takeLast(SEEN_LIMIT).toMutableList()
            write("seen", JSONArray(seen).toString())
        }

        for (listener in messageListeners) {
            try {
                listener.onMessage(message)
            } catch (e: Exception) {
                Thread.currentThread().uncaughtExceptionHandler?.uncaughtException(Thread.currentThread(), e)
            }
        }

        if (ack && config.autoAckDelivered) {
            launch { runCatching { ack(listOf(message.id), AckEvent.DELIVERED) } }
        }

        return true
    }

    private fun emit(state: ConnectionState) {
        for (listener in stateListeners) listener.onStateChange(state)
    }

    private fun requireRegistration(): Registration =
        synchronized(lock) { registration } ?: throw IAlertException(0, "not_registered", "Call register() first.")

    private fun forget() = synchronized(lock) {
        registration = null
        write("registration", null)
    }

    /** A rejected device token means the device was deleted or unsubscribed elsewhere. */
    private suspend fun <T> withToken(call: suspend () -> T): T {
        try {
            return call()
        } catch (e: IAlertException) {
            if (e.code == "device_token_invalid" || e.status == 404) {
                disconnect()
                forget()
            }
            throw e
        }
    }

    private fun installationId(): String = synchronized(lock) {
        read("installation") ?: "ia_android_${UUID.randomUUID()}".also { write("installation", it) }
    }

    private fun read(key: String): String? = runCatching { config.storage.getString(prefix + key) }.getOrNull()

    private fun write(key: String, value: String?) {
        runCatching { config.storage.putString(prefix + key, value) }
    }

    private fun launch(block: suspend () -> Unit) {
        block.startCoroutine(Continuation(EmptyCoroutineContext) {})
    }

    public companion object {
        public const val SDK_VERSION: String = "0.1.0"
        private const val SEEN_LIMIT = 500
        private val TAG_KEY = Regex("^[A-Za-z0-9_.:-]{1,64}$")

        private fun body(fields: DeviceFields): JSONObject {
            val body = JSONObject()

            if (fields.clearExternalUserId) body.put("external_user_id", JSONObject.NULL)
            fields.externalUserId?.let { body.put("external_user_id", it) }

            fields.tags?.let { tags ->
                val invalid = tags.keys.filterNot(TAG_KEY::matches).sorted()
                if (invalid.isNotEmpty()) {
                    throw IAlertException(0, "invalid_tag_key", "Tag keys must match [A-Za-z0-9_.:-]{1,64}: ${invalid.joinToString(", ")}")
                }
                body.put("tags", JSONObject().apply { tags.forEach { (key, value) -> put(key, value ?: JSONObject.NULL) } })
            }

            fields.locale?.let { body.put("locale", it) }
            fields.timezone?.let { body.put("timezone", it) }
            fields.appVersion?.let { body.put("app_version", it) }

            return body
        }

        private fun fromGateway(message: JSONObject) = IAlertMessage(
            id = message.opt("id")?.toString() ?: "",
            title = message.optStringOrNull("title"),
            body = message.optStringOrNull("body"),
            image = message.optStringOrNull("image"),
            data = message.optJSONObject("data") ?: JSONObject(),
            collapseId = message.optStringOrNull("collapse_id"),
            createdAt = message.optStringOrNull("created_at"),
            source = IAlertMessage.Source.GATEWAY,
        )

        private fun fromInbox(entry: InboxEntry) = IAlertMessage(
            id = entry.id,
            title = entry.title,
            body = entry.body,
            image = entry.image,
            data = entry.data,
            collapseId = entry.collapseId,
            createdAt = entry.createdAt,
            source = IAlertMessage.Source.INBOX,
        )

        /** `android.os.Build.MODEL` / `Build.VERSION.RELEASE` by reflection, so the core also runs on a plain JVM. */
        private fun androidBuildField(name: String, version: Boolean = false): String? = runCatching {
            Class.forName(if (version) "android.os.Build\$VERSION" else "android.os.Build").getField(name).get(null) as? String
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }
}
