package ir.ialert.sdk

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/** Events from a WebSocket to the Gateway connection. */
public interface WebSocketEvents {
    public fun onOpen(socket: WebSocketClient)
    public fun onText(socket: WebSocketClient, text: String)
    public fun onClosed(socket: WebSocketClient)
}

/** Minimal WebSocket the SDK needs; [OkHttpWebSocketClient] is the default. */
public interface WebSocketClient {
    /** Returns false when the frame could not be queued. */
    public fun send(text: String): Boolean
    public fun close()
}

/** Opens a socket to [url] and reports to [events]. */
public fun interface WebSocketFactory {
    public fun connect(url: String, events: WebSocketEvents): WebSocketClient
}

public class OkHttpWebSocketFactory(private val client: OkHttpClient = OkHttpClient()) : WebSocketFactory {
    override fun connect(url: String, events: WebSocketEvents): WebSocketClient {
        val wrapper = OkHttpWebSocketClient()
        wrapper.socket = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = events.onOpen(wrapper)

            override fun onMessage(webSocket: WebSocket, text: String) = events.onText(wrapper, text)

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = wrapper.finish(events)

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = wrapper.finish(events)
        })
        return wrapper
    }
}

public class OkHttpWebSocketClient internal constructor() : WebSocketClient {
    @Volatile internal var socket: WebSocket? = null
    private var finished = false

    override fun send(text: String): Boolean = socket?.send(text) ?: false

    override fun close() {
        socket?.close(1000, null)
    }

    @Synchronized
    internal fun finish(events: WebSocketEvents) {
        if (finished) return
        finished = true
        events.onClosed(this)
    }
}
