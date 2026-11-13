package ir.ialert.sdk

import android.content.Context
import com.google.firebase.messaging.RemoteMessage
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
 * Most apps only need [IAlert.start]; the constructor is for full manual control.
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
            .put("installation_id", installationId())
            .put("sdk_version", SDK_VERSION)
            .put("device_binding", true)
        if (fields.locale == null) body.put("locale", Locale.getDefault().toLanguageTag())
        if (fields.timezone == null) body.put("timezone", TimeZone.getDefault().id)
        androidBuildField("MODEL")?.let { body.put("device_model", it) }
        androidBuildField("RELEASE", version = true)?.let { body.put("os_version", it) }

        // Proves this install owns the device, so nobody else can re-register it.
        val (device, token) = api.register(body, synchronized(lock) { registration?.deviceToken })
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

    /**
     * Join a Live Activity channel so its broadcasts reach this device as FCM data
     * messages (read them with [LiveActivityUpdate.from]). The device must be
     * registered with its FCM token as `pushToken`; subscribe again after the token changes.
     */
    public suspend fun subscribeLiveActivity(channelId: String) {
        val registration = requireRegistration()
        withToken(forgetOnNotFound = false) { api.liveActivity(registration.deviceId, registration.deviceToken, channelId, subscribe = true) }
    }

    /** Leave a Live Activity channel. */
    public suspend fun unsubscribeLiveActivity(channelId: String) {
        val registration = requireRegistration()
        withToken(forgetOnNotFound = false) { api.liveActivity(registration.deviceId, registration.deviceToken, channelId, subscribe = false) }
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
                // Pushed entries stay unacknowledged: if FCM never brought them (an outage), the
        // server can stop calling them pushed and a later sync shows them.
        runCatching { ack(entries.filter { it.deliveredAt == null && !it.pushed }.map { it.id }, AckEvent.DELIVERED) }
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

    /**
     * Hand an iAlert FCM message to the SDK (from your own `FirebaseMessagingService`; the
     * built-in one does this for you). Listeners get it once, deduplicated against the
     * Gateway and Inbox, and it is acked as delivered. Returns the message, or null when it
     * is not an iAlert message or was already handed over.
     */
    public fun didReceivePush(data: Map<String, String>, title: String? = null, body: String? = null, image: String? = null): IAlertMessage? {
        val message = messageFromPush(data, title, body, image) ?: return null
        return if (receive(message)) message else null
    }

    /** Report a tap on an iAlert notification (its data, e.g. the launch intent extras). */
    public suspend fun didOpenPush(data: Map<String, String>) {
        val message = messageFromPush(data) ?: return
        receive(message)
        runCatching { ack(listOf(message.id), AckEvent.CLICKED) }
    }

    /**
     * Pull Inbox messages that no channel delivered yet and show them with [notifier],
     * skipping those FCM already took (`pushed`) or that are in the notification tray. This is how messages reach a closed
     * app while FCM is unreachable (the built-in WorkManager job runs it every 15 minutes).
     * Returns how many notifications were shown.
     */
    public suspend fun backgroundSync(notifier: LocalNotifier): Int {
        val registration = requireRegistration()
        val entries = withToken { api.inbox(registration.deviceId, registration.deviceToken) }
        val alreadyShown = runCatching { notifier.shownNotificationIds() }.getOrDefault(emptySet())
        var shown = 0

        for (entry in entries.asReversed()) {
            if (entry.deliveredAt != null) continue
            val message = fromInbox(entry)

            // Already handed over (Gateway, FCM while open, an earlier sync): never shown twice.
            if (!receive(message, ack = false)) continue

            // FCM already showed pushed entries, even if the user has dismissed them since.
            if (!entry.pushed && message.id !in alreadyShown && (message.title != null || message.body != null)) {
                notifier.show(message)
                shown++
            }
        }

        // Pushed entries stay unacknowledged: if FCM never brought them (an outage), the
        // server can stop calling them pushed and a later sync shows them.
        runCatching { ack(entries.filter { it.deliveredAt == null && !it.pushed }.map { it.id }, AckEvent.DELIVERED) }

        return shown
    }

    /**
     * Approve or deny an Auth Push sign-in request (see [AuthRequestInfo.from]). With number
     * matching pass the number the user typed; a wrong one denies. Returns the recorded
     * status: `approved`, `denied`, `pending` or `expired`.
     */
    public suspend fun respondToAuthRequest(requestId: String, approve: Boolean, code: String? = null): String {
        val registration = requireRegistration()
        return withToken(forgetOnNotFound = false) {
            api.respondToAuthRequest(registration.deviceId, registration.deviceToken, requestId, approve, code)
        }
    }

    /** How native push is set up for this app (`GET /v1/push-config`), with the Firebase client of [packageName]. */
    public suspend fun pushConfig(packageName: String? = null): PushConfig = api.pushConfig(packageName)

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

    /**
     * A rejected device token means the device was deleted or unsubscribed elsewhere.
     * A 404 means the same, except where the path also names another resource (a channel).
     */
    private suspend fun <T> withToken(forgetOnNotFound: Boolean = true, call: suspend () -> T): T {
        try {
            return call()
        } catch (e: IAlertException) {
            if (e.code == "device_token_invalid" || (forgetOnNotFound && e.status == 404)) {
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
        /**
         * One-line setup, in `Application.onCreate()`: registers the device, gets an FCM token
         * from the app's own Firebase project (its service account and `google-services.json`
         * uploaded in the iAlert panel; nothing in the app), shows iAlert notifications, acks taps and opens
         * `data.url`, keeps the Gateway open while the app is visible and syncs the Inbox every
         * 15 minutes in the background. Asks for the notification permission on Android 13+
         * the first time an activity shows, unless [requestPermission] is false.
         */
        @JvmStatic
        @JvmOverloads
        public fun start(context: Context, publicKey: String, fields: DeviceFields = DeviceFields(), requestPermission: Boolean = true): IAlert =
            AutoSetup.start(context, publicKey, fields, requestPermission)

        /** The instance [start] created, or null before it. */
        @JvmStatic
        public val shared: IAlert? get() = AutoSetup.instance

        /** Set (or with null, clear) the external user id of this device, now or once registered. */
        @JvmStatic
        public fun login(externalUserId: String?): Unit = AutoSetup.login(externalUserId)

        /** Live Activity broadcasts received by the built-in FCM service. Without a listener the SDK shows each channel as an ongoing notification. */
        @JvmStatic
        public fun onLiveActivityUpdate(listener: LiveActivityListener): Subscription {
            AutoSetup.liveActivityListeners += listener
            return Subscription { AutoSetup.liveActivityListeners -= listener }
        }

        /**
         * Route taps yourself (for example to a screen of a single-activity app). Called on the
         * main thread with the notification's `url` (deep link or web link) and its data; return
         * true when handled. Set it in `Application.onCreate()` so cold starts reach it too.
         */
        @JvmStatic
        public fun onNotificationClick(handler: NotificationClickHandler?) {
            AutoSetup.clickHandler = handler
        }

        /** For apps with their own `FirebaseMessagingService`: pass `onMessageReceived` here. Returns true for iAlert messages. */
        @JvmStatic
        public fun handleFcmMessage(context: Context, message: RemoteMessage): Boolean {
            LiveActivityUpdate.from(message.data)?.let { update ->
                val listeners = AutoSetup.liveActivityListeners
                if (listeners.isEmpty()) AndroidNotifier(context).showLiveActivity(update) else listeners.forEach { it.onUpdate(update) }
                return true
            }
            if (message.data["ialert_id"] == null) return false
            val ialert = AutoSetup.restore(context) ?: return true
            val notification = message.notification
            val received = ialert.didReceivePush(message.data, notification?.title, notification?.body, notification?.imageUrl?.toString())
            if (received != null) AutoSetup.bridge?.onPush(message.data, received.title, received.body)
            return true
        }

        /** For apps with their own `FirebaseMessagingService`: pass `onNewToken` here. */
        @JvmStatic
        public fun handleFcmToken(context: Context, token: String): Unit = AutoSetup.onNewToken(context, token)

        public const val SDK_VERSION: String = "0.8.0"
        private const val SEEN_LIMIT = 500

        /** iAlert's own FCM data keys, not part of the message's custom data. */
        private val PUSH_KEYS = setOf("ialert_id", "ialert_url", "ialert_sound", "ialert_title", "ialert_body", "ialert_image", "ialert_sender_id", "ialert_sender_name", "ialert_sender_avatar")
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
            sound = message.optStringOrNull("sound"),
            url = clickUrl(message.optStringOrNull("url"), message.optJSONObject("data")),
            sender = Sender.fromJson(message.optJSONObject("sender")),
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
            sound = entry.sound,
            url = clickUrl(entry.url, entry.data),
            sender = entry.sender,
        )

        /**
         * An iAlert FCM message: `RemoteMessage.data` (with `ialert_id`) plus the
         * notification title/body/image when FCM carried them. Null for other messages.
         */
        @JvmStatic
        public fun messageFromPush(data: Map<String, String>, title: String? = null, body: String? = null, image: String? = null): IAlertMessage? {
            val id = data["ialert_id"]?.takeIf { it.isNotBlank() } ?: return null
            val custom = JSONObject()
            for ((key, value) in data) {
                if (key !in PUSH_KEYS && !key.startsWith("google.") && !key.startsWith("gcm.")) custom.put(key, value)
            }

            return IAlertMessage(
                id = id,
                // Messages with a sender come as FCM data only, with the content in `ialert_*` keys.
                title = title ?: data["ialert_title"],
                body = body ?: data["ialert_body"],
                image = image ?: data["ialert_image"],
                data = custom,
                collapseId = data["collapse_key"],
                createdAt = null,
                source = IAlertMessage.Source.PUSH,
                sound = data["ialert_sound"],
                url = clickUrl(data["ialert_url"], custom),
                sender = Sender.fromPush(data),
            )
        }

        /** The notification's `url`, or `data.url` of messages sent before that field existed. */
        internal fun clickUrl(url: String?, data: JSONObject?): String? =
            url?.takeIf { it.isNotBlank() } ?: data?.optStringOrNull("url")?.takeIf { it.isNotBlank() }

        /** `android.os.Build.MODEL` / `Build.VERSION.RELEASE` by reflection, so the core also runs on a plain JVM. */
        private fun androidBuildField(name: String, version: Boolean = false): String? = runCatching {
            Class.forName(if (version) "android.os.Build\$VERSION" else "android.os.Build").getField(name).get(null) as? String
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }
}
