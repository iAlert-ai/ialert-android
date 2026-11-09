package ir.ialert.sdk

import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

public data class HttpRequest(val method: String, val url: String, val headers: Map<String, String>, val body: String?)

public data class HttpResponse(val status: Int, val body: String)

/** Sends one HTTP request; [OkHttpTransport] is the default. Inject a fake in tests. */
public fun interface HttpTransport {
    public suspend fun send(request: HttpRequest): HttpResponse
}

public class OkHttpTransport(private val client: OkHttpClient = OkHttpClient()) : HttpTransport {
    override suspend fun send(request: HttpRequest): HttpResponse {
        val okRequest = Request.Builder()
            .url(request.url)
            .apply { request.headers.forEach { (name, value) -> header(name, value) } }
            .method(request.method, request.body?.toRequestBody("application/json".toMediaType()) ?: if (request.method == "POST" || request.method == "PATCH") ByteArray(0).toRequestBody() else null)
            .build()

        return suspendCoroutine { continuation ->
            client.newCall(okRequest).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) = continuation.resumeWithException(e)

                override fun onResponse(call: Call, response: Response) {
                    response.use { continuation.resume(HttpResponse(it.code, it.body?.string() ?: "")) }
                }
            })
        }
    }
}
