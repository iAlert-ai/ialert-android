package ir.ialert.sdk

import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecordingNotifier(private val inTray: Set<String> = emptySet()) : LocalNotifier {
    val shown = mutableListOf<IAlertMessage>()

    override fun shownNotificationIds(): Set<String> = inTray

    override fun show(message: IAlertMessage) {
        shown += message
    }
}

class NativePushTest {
    private fun sdk(transport: FakeTransport) = IAlert(
        IAlert.Config(
            publicKey = "ia_pk_live_abcdefghijkl_secret",
            storage = MemoryStorage(),
            apiUrl = "https://api.test/api/v1",
            transport = transport,
            autoConnect = false,
        ),
    )

    private val pushData = mapOf("ialert_id" to "n_1", "ialert_sound" to "order.mp3", "order" to "7", "google.message_id" to "0:1")

    @Test
    fun `an fcm message becomes an iAlert message without fcm keys`() {
        val message = assertNotNull(IAlert.messageFromPush(pushData, "Hi", "Body", "https://img.test/a.png"))

        assertEquals("n_1", message.id)
        assertEquals(IAlertMessage.Source.PUSH, message.source)
        assertEquals("order.mp3", message.sound)
        assertEquals("7", message.data.getString("order"))
        assertEquals(setOf("order"), message.data.keys().asSequence().toSet())
        assertNull(IAlert.messageFromPush(mapOf("other" to "x")))
    }

    @Test
    fun `a push is handed over once, also when the inbox brings it again, and acked delivered`() {
        val transport = FakeTransport(registerResponder, inboxResponder(inboxEntry("n_1")), ackResponder)
        val ialert = sdk(transport)
        val received = mutableListOf<IAlertMessage>()
        ialert.onMessage { received += it }
        blocking { ialert.register(pushToken = "fcm-1") }

        assertNotNull(ialert.didReceivePush(pushData, "Hi", "Body"))
        assertNull(ialert.didReceivePush(pushData, "Hi", "Body"))
        assertTrue(blocking { ialert.syncInbox() }.isEmpty())

        assertEquals(listOf("n_1"), received.map { it.id })
        eventually { transport.requests.any { it.url.endsWith("/inbox/ack") && it.body?.getString("event") == "delivered" } }
    }

    @Test
    fun `a tap is acked as clicked`() {
        val transport = FakeTransport(registerResponder, ackResponder)
        val ialert = sdk(transport)
        blocking { ialert.register() }

        blocking { ialert.didOpenPush(pushData) }

        assertTrue(transport.requests.any { it.url.endsWith("/inbox/ack") && it.body?.getString("event") == "clicked" && it.body.getJSONArray("ids").getString(0) == "n_1" })
    }

    @Test
    fun `background sync shows only undelivered, unpushed messages that are not in the tray or seen`() {
        val transport = FakeTransport(
            registerResponder,
            inboxResponder(inboxEntry("n_5").put("pushed", true), inboxEntry("n_4"), inboxEntry("n_3"), inboxEntry("n_2", deliveredAt = "2026-11-09T08:01:00+00:00"), inboxEntry("n_1")),
            ackResponder,
        )
        val ialert = sdk(transport)
        blocking { ialert.register() }
        ialert.didReceivePush(mapOf("ialert_id" to "n_1"), "seen", "already")

        val notifier = RecordingNotifier(inTray = setOf("n_3"))
        val shown = blocking { ialert.backgroundSync(notifier) }

        assertEquals(1, shown)
        assertEquals(listOf("n_4"), notifier.shown.map { it.id })
        assertTrue(blocking { ialert.backgroundSync(RecordingNotifier()) } == 0)
    }

    @Test
    fun `inbox entries carry their sound`() {
        val entry = inboxEntry("n_1").also { it.getJSONObject("content").put("sound", "none") }
        val ialert = sdk(FakeTransport(registerResponder, inboxResponder(entry), ackResponder))
        blocking { ialert.register() }

        assertEquals("none", blocking { ialert.syncInbox() }.single().sound)
    }

    @Test
    fun `auth requests are read from the message and answered with the device token`() {
        val transport = FakeTransport(registerResponder, { request ->
            if ("/auth-requests/ar_1" in request.url) 200 to JSONObject().put("data", JSONObject().put("status", "approved")) else null
        })
        val ialert = sdk(transport)
        blocking { ialert.register() }
        val message = assertNotNull(
            IAlert.messageFromPush(mapOf("ialert_id" to "n_9", "ialert_type" to "auth_request", "auth_request_id" to "ar_1", "number_matching" to "1", "expires_at" to "2026-11-11T10:00:00Z", "ip" to "1.2.3.4")),
        )
        val info = assertNotNull(AuthRequestInfo.from(message))

        assertEquals("ar_1", info.id)
        assertTrue(info.numberMatching)
        assertEquals(mapOf("ip" to "1.2.3.4"), info.context)
        assertEquals("approved", blocking { ialert.respondToAuthRequest(info.id, approve = true, code = "47") })

        val request = transport.requests.last()
        assertEquals("POST", request.method)
        assertTrue(request.url.endsWith("/devices/dev_1/auth-requests/ar_1"))
        assertEquals("ia_dt_dev_1.sig", request.headers["X-Device-Token"])
        assertEquals("approve", request.body?.getString("decision"))
        assertEquals("47", request.body?.getString("code"))
        assertNull(AuthRequestInfo.from(IAlert.messageFromPush(pushData)!!))
    }

    @Test
    fun `push config reads the shared firebase client`() {
        val client = JSONObject().put("project_id", "p").put("application_id", "1:2:android:3").put("api_key", "k").put("sender_id", "2")
        val transport = FakeTransport({ request ->
            if (request.url.endsWith("/push-config")) 200 to JSONObject().put("data", JSONObject().put("fcm", JSONObject().put("source", "shared").put("client", client))) else null
        })

        val config = blocking { sdk(transport).pushConfig() }

        assertEquals("shared", config.fcmSource)
        assertEquals(PushConfig.FcmClient("p", "1:2:android:3", "k", "2"), config.fcmClient)
    }
}
