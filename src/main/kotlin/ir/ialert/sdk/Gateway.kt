package ir.ialert.sdk

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

internal interface GatewayHandlers {
    fun onReady()
    fun onMessage(message: JSONObject)
    /** The token was rejected: reconnecting would not help. */
    fun onUnauthorized()
    fun onStateChange(state: ConnectionState)
}

/**
 * One WebSocket to the iAlert Gateway: authenticates with the device token,
 * answers pings, sends acks and reconnects with jittered exponential backoff.
 */
internal class GatewayConnection(
    private val url: String,
    private val deviceToken: String,
    private val factory: WebSocketFactory,
    private val scheduler: ScheduledExecutorService,
    private val handlers: GatewayHandlers,
    private val pingIntervalMs: Long,
    private val minReconnectMs: Long,
    private val maxReconnectMs: Long,
) : WebSocketEvents {
    private val lock = Any()
    private var socket: WebSocketClient? = null
    private var opened = false
    private var stopped = true
    private var ready = false
    private var attempt = 0
    private var reconnectTask: ScheduledFuture<*>? = null
    private var pingTask: ScheduledFuture<*>? = null
    private val pendingAcks = mutableListOf<Pair<List<String>, AckEvent>>()

    val isReady: Boolean get() = synchronized(lock) { ready }

    fun start() {
        synchronized(lock) {
            if (!stopped) return
            stopped = false
        }
        open()
    }

    fun stop() {
        val socket = synchronized(lock) {
            stopped = true
            ready = false
            clearTimers()
            socket.also { this.socket = null }
        }
        socket?.close()
        handlers.onStateChange(ConnectionState.CLOSED)
    }

    /** Sends an ack now, or once the socket is ready again. */
    fun ack(ids: List<String>, event: AckEvent) = synchronized(lock) {
        for (chunk in ids.chunked(100)) {
            if (!ready || !send(ackFrame(chunk, event))) pendingAcks += chunk to event
        }
    }

    private fun open() {
        handlers.onStateChange(ConnectionState.CONNECTING)
        val created = try {
            factory.connect(url, this)
        } catch (e: Exception) {
            scheduleReconnect()
            return
        }
        synchronized(lock) {
            // onOpen may already have run on another thread; keep whichever socket is current.
            if (socket == null) socket = created
        }
    }

    override fun onOpen(socket: WebSocketClient) {
        synchronized(lock) {
            if (stopped) return
            this.socket = socket
            opened = true
            send(JSONObject().put("type", "auth").put("device_token", deviceToken))
        }
    }

    override fun onText(socket: WebSocketClient, text: String) {
        val frame = try {
            JSONObject(text)
        } catch (e: Exception) {
            return
        }

        when (frame.optStringOrNull("type")) {
            "ready" -> {
                synchronized(lock) {
                    if (socket !== this.socket) return
                    ready = true
                    attempt = 0
                    pingTask = scheduler.scheduleWithFixedDelay({
                        synchronized(lock) { send(JSONObject().put("type", "ping")) }
                    }, pingIntervalMs, pingIntervalMs, TimeUnit.MILLISECONDS)
                    flushAcks()
                }
                handlers.onStateChange(ConnectionState.READY)
                handlers.onReady()
            }
            "message" -> frame.optJSONObject("message")?.let(handlers::onMessage)
            "ping" -> synchronized(lock) { send(JSONObject().put("type", "pong")) }
            "error" -> {
                val error = frame.optStringOrNull("error")
                if (error == "device_token_invalid" || error == "auth_required") {
                    synchronized(lock) {
                        stopped = true
                        clearTimers()
                    }
                    handlers.onUnauthorized()
                }
            }
        }
    }

    override fun onClosed(socket: WebSocketClient) {
        val reconnect = synchronized(lock) {
            if (socket !== this.socket) return
            this.socket = null
            opened = false
            ready = false
            clearTimers()
            !stopped
        }
        if (reconnect) scheduleReconnect()
    }

    /** Lock held. */
    private fun send(frame: JSONObject): Boolean {
        val socket = socket ?: return false
        if (!opened) return false
        return try {
            socket.send(frame.toString())
        } catch (e: Exception) {
            false
        }
    }

    private fun ackFrame(ids: List<String>, event: AckEvent) =
        JSONObject().put("type", "ack").put("ids", JSONArray(ids)).put("event", event.wire)

    /** Lock held. */
    private fun flushAcks() {
        val pending = pendingAcks.toList()
        pendingAcks.clear()
        for ((ids, event) in pending) {
            if (!send(ackFrame(ids, event))) pendingAcks += ids to event
        }
    }

    private fun scheduleReconnect() {
        handlers.onStateChange(ConnectionState.CONNECTING)
        synchronized(lock) {
            if (stopped) return
            val ceiling = min(maxReconnectMs.toDouble(), minReconnectMs * 2.0.pow(attempt))
            val delay = (ceiling / 2 + Random.nextDouble() * (ceiling / 2)).toLong()
            attempt++
            reconnectTask = scheduler.schedule({
                val shouldOpen = synchronized(lock) {
                    reconnectTask = null
                    !stopped
                }
                if (shouldOpen) open()
            }, delay, TimeUnit.MILLISECONDS)
        }
    }

    /** Lock held. */
    private fun clearTimers() {
        reconnectTask?.cancel(false)
        reconnectTask = null
        pingTask?.cancel(false)
        pingTask = null
    }
}
