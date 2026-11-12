package ir.ialert.sdk

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** Runs a suspend block to completion on the calling thread (no kotlinx.coroutines needed). */
fun <T> blocking(block: suspend () -> T): T {
    val latch = CountDownLatch(1)
    var result: Result<T>? = null
    block.startCoroutine(Continuation(EmptyCoroutineContext) {
        result = it
        latch.countDown()
    })
    if (!latch.await(5, TimeUnit.SECONDS)) fail("timed out")
    return result!!.getOrThrow()
}

fun eventually(timeoutMs: Long = 2_000, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (!condition()) {
        if (System.currentTimeMillis() > deadline) fail("condition not met in time")
        Thread.sleep(5)
    }
}

class Recorded(val method: String, val url: String, val headers: Map<String, String>, val body: JSONObject?)

/** HTTP double that records requests and answers from a list of responders. */
class FakeTransport(private vararg val responders: (Recorded) -> Pair<Int, Any>?) : HttpTransport {
    val requests = CopyOnWriteArrayList<Recorded>()

    override suspend fun send(request: HttpRequest): HttpResponse {
        val recorded = Recorded(request.method, request.url, request.headers, request.body?.let(::JSONObject))
        requests += recorded
        val (status, body) = responders.firstNotNullOfOrNull { it(recorded) } ?: (404 to JSONObject().put("message", "Not found").put("code", "not_found"))
        return HttpResponse(status, body.toString())
    }
}

val registerResponder: (Recorded) -> Pair<Int, Any>? = { request ->
    if (request.method == "POST" && request.url.endsWith("/devices")) {
        201 to JSONObject().put("data", JSONObject().put("id", "dev_1").put("platform", "android")).put("device_token", "ia_dt_dev_1.sig")
    } else {
        null
    }
}

fun inboxResponder(vararg entries: JSONObject): (Recorded) -> Pair<Int, Any>? = { request ->
    if (request.method == "GET" && "/inbox" in request.url) {
        200 to JSONObject().put("data", JSONArray(entries.toList())).put("links", JSONObject().put("next", JSONObject.NULL))
    } else {
        null
    }
}

val ackResponder: (Recorded) -> Pair<Int, Any>? = { request ->
    if (request.url.endsWith("/inbox/ack")) 200 to JSONObject().put("acknowledged", 1) else null
}

fun inboxEntry(id: String, deliveredAt: String? = null): JSONObject = JSONObject()
    .put("id", id)
    .put("content", JSONObject().put("title", "title $id").put("body", "body").put("image", JSONObject.NULL).put("data", JSONObject().put("order", 1)))
    .put("collapse_id", JSONObject.NULL)
    .put("delivered_at", deliveredAt ?: JSONObject.NULL)
    .put("read_at", JSONObject.NULL)
    .put("clicked_at", JSONObject.NULL)
    .put("created_at", "2026-11-09T08:00:00+00:00")

/** WebSocket double driven by the test. */
class FakeSocket(val url: String, private val events: WebSocketEvents) : WebSocketClient {
    val sent = CopyOnWriteArrayList<String>()

    val frames: List<String> get() = sent.toList()

    override fun send(text: String): Boolean {
        sent += JSONObject(text).toString()
        return true
    }

    override fun close() = events.onClosed(this)

    fun serverOpen() = events.onOpen(this)

    fun serverSend(frame: JSONObject) = events.onText(this, frame.toString())

    fun serverDrop() = events.onClosed(this)
}

class SocketRecorder : WebSocketFactory {
    val sockets = CopyOnWriteArrayList<FakeSocket>()

    override fun connect(url: String, events: WebSocketEvents): WebSocketClient = FakeSocket(url, events).also { sockets += it }
}

fun frame(type: String) = JSONObject().put("type", type)

fun json(vararg pairs: Pair<String, Any?>): String = JSONObject().apply { pairs.forEach { (key, value) -> put(key, value ?: JSONObject.NULL) } }.toString()

class IAlertTest {
    private val publicKey = "ia_pk_live_abcdefghijkl_secret"

    private fun sdk(
        transport: FakeTransport,
        storage: KeyValueStorage = MemoryStorage(),
        sockets: SocketRecorder = SocketRecorder(),
        autoConnect: Boolean = true,
    ) = IAlert(
        IAlert.Config(
            publicKey = publicKey,
            storage = storage,
            apiUrl = "https://api.test/api/v1",
            gatewayUrl = "wss://gw.test/v1/connect",
            transport = transport,
            webSocketFactory = sockets,
            autoConnect = autoConnect,
            minReconnectMs = 10,
            maxReconnectMs = 20,
        ),
    )

    @Test
    fun `refuses secret keys`() {
        val error = assertFailsWith<IAlertException> { IAlert("ia_sk_live_x", MemoryStorage()) }
        assertEquals("invalid_public_key", error.code)
    }

    @Test
    fun `registers as an android device with a stable installation id and the public key`() {
        val transport = FakeTransport(registerResponder)
        val storage = MemoryStorage()

        blocking { sdk(transport, storage, autoConnect = false).register(fields = DeviceFields(externalUserId = "user-42", tags = mapOf("plan" to "gold"))) }
        blocking { sdk(transport, storage, autoConnect = false).register() }

        val first = transport.requests[0]
        val body = first.body!!
        assertEquals("Bearer $publicKey", first.headers["Authorization"])
        assertEquals("android", body.getString("platform"))
        assertTrue(Regex("^ia_android_[0-9a-f-]{36}$").matches(body.getString("push_token")))
        assertEquals("user-42", body.getString("external_user_id"))
        assertEquals("gold", body.getJSONObject("tags").getString("plan"))
        assertEquals("0.6.0", body.getString("sdk_version"))
        assertEquals(body.getString("push_token"), transport.requests[1].body!!.getString("push_token"))
    }

    @Test
    fun `sends the fcm token and keeps the registration across restarts`() {
        val transport = FakeTransport(registerResponder)
        val storage = MemoryStorage()

        blocking { sdk(transport, storage, autoConnect = false).register(pushToken = "fcm-token-123456") }

        assertEquals("fcm-token-123456", transport.requests[0].body!!.getString("push_token"))
        assertTrue(Regex("^ia_android_[0-9a-f-]{36}$").matches(transport.requests[0].body!!.getString("installation_id")))
        assertEquals("dev_1", sdk(transport, storage, autoConnect = false).deviceId)
    }

    @Test
    fun `rejects invalid tag keys before calling the api`() {
        val transport = FakeTransport(registerResponder)

        val error = assertFailsWith<IAlertException> { blocking { sdk(transport).register(fields = DeviceFields(tags = mapOf("bad key" to 1))) } }

        assertEquals("invalid_tag_key", error.code)
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun `updates its own device with the device token`() {
        val transport = FakeTransport(registerResponder, { request ->
            if (request.method == "PATCH") 200 to JSONObject().put("data", JSONObject().put("id", "dev_1")) else null
        })
        val ialert = sdk(transport, autoConnect = false)
        blocking { ialert.register() }

        blocking { ialert.setTags(mapOf("plan" to "gold", "old" to null)) }

        val patch = transport.requests.last()
        assertEquals("https://api.test/api/v1/devices/dev_1", patch.url)
        assertEquals("ia_dt_dev_1.sig", patch.headers["X-Device-Token"])
        assertEquals(json("tags" to JSONObject().put("plan", "gold").put("old", JSONObject.NULL)), patch.body.toString())
    }

    @Test
    fun `clears the external user id with null`() {
        val transport = FakeTransport(registerResponder, { request ->
            if (request.method == "PATCH") 200 to JSONObject().put("data", JSONObject().put("id", "dev_1")) else null
        })
        val ialert = sdk(transport, autoConnect = false)
        blocking { ialert.register() }

        blocking { ialert.setExternalUserId(null) }

        assertTrue(transport.requests.last().body!!.isNull("external_user_id"))
    }

    @Test
    fun `forgets the registration when the device token is rejected`() {
        val transport = FakeTransport(registerResponder, { request ->
            if (request.method == "PATCH") 403 to JSONObject().put("message", "Invalid").put("code", "device_token_invalid") else null
        })
        val ialert = sdk(transport, autoConnect = false)
        blocking { ialert.register() }

        val error = assertFailsWith<IAlertException> { blocking { ialert.setExternalUserId("u") } }

        assertEquals(403, error.status)
        assertEquals("device_token_invalid", error.code)
        assertNull(ialert.deviceId)
    }

    @Test
    fun `gateway authenticates answers pings and delivers each message once with an ack`() {
        val transport = FakeTransport(registerResponder, inboxResponder(), ackResponder)
        val sockets = SocketRecorder()
        val ialert = sdk(transport, sockets = sockets)
        val received = CopyOnWriteArrayList<IAlertMessage>()
        ialert.onMessage { received += it }
        blocking { ialert.register() }

        val socket = sockets.sockets.single()
        assertEquals("wss://gw.test/v1/connect", socket.url)
        socket.serverOpen()
        assertEquals(json("type" to "auth", "device_token" to "ia_dt_dev_1.sig"), socket.frames.first())

        socket.serverSend(frame("ready"))
        socket.serverSend(frame("ping"))
        val message = JSONObject().put("id", "n_1").put("title", "Hi").put("body", "There").put("data", JSONObject().put("a", 1))
        socket.serverSend(frame("message").put("message", message))
        socket.serverSend(frame("message").put("message", message))

        eventually { socket.frames.size >= 3 }
        assertEquals(1, received.size)
        assertEquals(IAlertMessage.Source.GATEWAY, received[0].source)
        assertEquals(1, received[0].data.getInt("a"))
        assertEquals(
            listOf(json("type" to "pong"), json("type" to "ack", "ids" to JSONArray(listOf("n_1")), "event" to "delivered")),
            socket.frames.drop(1),
        )
        ialert.disconnect()
    }

    @Test
    fun `syncs missed inbox messages after ready oldest first and acks the undelivered ones`() {
        val transport = FakeTransport(registerResponder, inboxResponder(inboxEntry("n_new"), inboxEntry("n_old", "2026-11-09T08:00:00+00:00")), ackResponder)
        val sockets = SocketRecorder()
        val ialert = sdk(transport, sockets = sockets)
        val received = CopyOnWriteArrayList<String>()
        ialert.onMessage { received += "${it.source}:${it.id}" }
        blocking { ialert.register() }

        val socket = sockets.sockets.single()
        socket.serverOpen()
        socket.serverSend(frame("ready"))

        eventually { socket.frames.size >= 2 }
        assertTrue(transport.requests.any { "/inbox" in it.url && "unread=1" in it.url })
        assertEquals(listOf("INBOX:n_old", "INBOX:n_new"), received.toList())
        assertEquals(json("type" to "ack", "ids" to JSONArray(listOf("n_new")), "event" to "delivered"), socket.frames.last())
        ialert.disconnect()
    }

    @Test
    fun `does not show a message again after a restart`() {
        val storage = MemoryStorage()
        val transport = FakeTransport(registerResponder, inboxResponder(inboxEntry("n_1")), ackResponder)
        val first = sdk(transport, storage, autoConnect = false)
        blocking { first.register() }

        assertEquals(1, blocking { first.syncInbox() }.size)
        assertEquals(0, blocking { sdk(transport, storage, autoConnect = false).syncInbox() }.size)
    }

    @Test
    fun `acks over http when the socket is not ready`() {
        val transport = FakeTransport(registerResponder, ackResponder)
        val ialert = sdk(transport, autoConnect = false)
        blocking { ialert.register() }

        blocking { ialert.ack(listOf("n_1"), AckEvent.CLICKED) }

        assertEquals(json("ids" to JSONArray(listOf("n_1")), "event" to "clicked"), transport.requests.last().body.toString())
        assertEquals("ia_dt_dev_1.sig", transport.requests.last().headers["X-Device-Token"])
    }

    @Test
    fun `reconnects after a drop but stops when the token is invalid`() {
        val transport = FakeTransport(registerResponder, inboxResponder(), ackResponder)
        val sockets = SocketRecorder()
        val ialert = sdk(transport, sockets = sockets)
        val states = CopyOnWriteArrayList<ConnectionState>()
        ialert.onConnectionStateChange { states += it }
        blocking { ialert.register() }

        sockets.sockets[0].serverOpen()
        sockets.sockets[0].serverDrop()
        eventually { sockets.sockets.size == 2 }

        val second = sockets.sockets[1]
        second.serverOpen()
        second.serverSend(frame("error").put("error", "device_token_invalid"))
        second.serverDrop()
        Thread.sleep(100)

        assertEquals(2, sockets.sockets.size)
        assertNull(ialert.deviceId)
        assertEquals(ConnectionState.CLOSED, states.last())
    }

    @Test
    fun `joins and leaves a live activity channel with its device token`() {
        val transport = FakeTransport(registerResponder, { request ->
            if ("/live-activity-channels/" in request.url) 200 to JSONObject().put("subscribed", request.method == "POST") else null
        })
        val ialert = sdk(transport, autoConnect = false)

        blocking { ialert.register(pushToken = "fcm-token-1") }
        blocking { ialert.subscribeLiveActivity("01J0CHANNEL") }
        blocking { ialert.unsubscribeLiveActivity("01J0CHANNEL") }

        val (join, leave) = transport.requests.drop(1)
        assertEquals("POST", join.method)
        assertEquals("https://api.test/api/v1/devices/dev_1/live-activity-channels/01J0CHANNEL", join.url)
        assertEquals("ia_dt_dev_1.sig", join.headers["X-Device-Token"])
        assertEquals("DELETE", leave.method)
        assertEquals(join.url, leave.url)
    }

    @Test
    fun `live activity subscription needs a registration and reports api errors`() {
        val transport = FakeTransport(registerResponder, { request ->
            if ("/live-activity-channels/" in request.url) 422 to JSONObject().put("message", "Register with the FCM token.").put("code", "push_token_required") else null
        })
        val ialert = sdk(transport, autoConnect = false)

        assertEquals("not_registered", assertFailsWith<IAlertException> { blocking { ialert.subscribeLiveActivity("c") } }.code)

        blocking { ialert.register() }
        val error = assertFailsWith<IAlertException> { blocking { ialert.subscribeLiveActivity("c") } }
        assertEquals("push_token_required", error.code)
        assertEquals(422, error.status)
    }

    @Test
    fun `reads live activity updates from fcm data and ignores other messages`() {
        val update = LiveActivityUpdate.from(mapOf(
            "ialert_type" to "live_activity",
            "ialert_channel" to "01J0CHANNEL",
            "ialert_broadcast" to "01J0BROADCAST",
            "event" to "update",
            "timestamp" to "1700000000",
            "content_state" to "{\"home\":2,\"away\":1}",
            "title" to "Goal!",
            "relevance_score" to "0.5",
        ))!!

        assertEquals("01J0CHANNEL", update.channelId)
        assertEquals(LiveActivityUpdate.Event.UPDATE, update.event)
        assertEquals(2, update.contentState.getInt("home"))
        assertEquals("Goal!", update.title)
        assertEquals(1_700_000_000L, update.timestamp)
        assertEquals(0.5, update.relevanceScore)
        assertEquals("01J0CHANNEL".hashCode(), update.notificationId)
        assertTrue(!update.isEnd)

        assertTrue(LiveActivityUpdate.from(mapOf("ialert_type" to "live_activity", "ialert_channel" to "c", "event" to "end"))!!.isEnd)
        assertNull(LiveActivityUpdate.from(mapOf("ialert_id" to "01J0NOTIFICATION")))
        assertNull(LiveActivityUpdate.from(mapOf("ialert_type" to "live_activity", "ialert_channel" to "c", "event" to "start")))
    }

    @Test
    fun `an unknown channel does not cost the device its registration`() {
        val transport = FakeTransport(registerResponder)
        val ialert = sdk(transport, autoConnect = false)

        blocking { ialert.register(pushToken = "fcm-token-1") }
        val error = assertFailsWith<IAlertException> { blocking { ialert.subscribeLiveActivity("missing") } }

        assertEquals(404, error.status)
        assertEquals("dev_1", ialert.deviceId)
    }
}
