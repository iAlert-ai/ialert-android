# iAlert Android SDK

iAlert SDK for Android, in Kotlin, with OkHttp as its only dependency; it uses `org.json` from the Android platform. It registers the app as an `android` device and receives notifications while the app runs, over iAlert's own channel: a WebSocket to the iAlert Gateway, plus the device Inbox for anything sent while the app was closed. Each notification reaches your listeners once, across both channels and restarts.

The library is a plain Kotlin/JVM module: it reads `android.os.Build` by reflection, so it builds and tests without the Android SDK. Add it to your app as a normal dependency (`ir.ialert:ialert-android:0.1.0`, once published).

```kotlin
class PrefsStorage(context: Context) : KeyValueStorage {
    private val prefs = context.getSharedPreferences("ialert", Context.MODE_PRIVATE)
    override fun getString(key: String) = prefs.getString(key, null)
    override fun putString(key: String, value: String?) = prefs.edit().putString(key, value).apply()
}

val ialert = IAlert("ia_pk_live_…", PrefsStorage(applicationContext))

ialert.onMessage { message -> runOnUiThread { show(message.title, message.body, message.data) } }
ialert.onConnectionStateChange { state -> Log.d("iAlert", "$state") } // CONNECTING, READY, CLOSED

lifecycleScope.launch {
    ialert.register(fields = DeviceFields(externalUserId = "user-42", tags = mapOf("plan" to "gold")))
    ialert.setTags(mapOf("plan" to "silver", "trial" to null)) // merged; null removes
    ialert.ack(listOf(message.id), AckEvent.CLICKED)
    ialert.unsubscribe()
}
```

- Use the app's **public** key (`ia_pk_…`); secret keys are refused.
- `register(pushToken = …)` sends your FCM token. Without one, a stable installation id is used. Calling it on every launch is safe.
- API calls are `suspend` functions. Listeners run on a background thread.
- `delivered` is acked automatically (`autoAckDelivered = false` turns it off). Report `READ` / `CLICKED` yourself. Acks go over the socket when it is ready, otherwise over HTTP.
- `IAlert.Config` lets you inject `transport`, `webSocketFactory`, `scheduler`, `apiUrl`, `gatewayUrl`, `autoConnect` and the ping/reconnect timings.

## Next step: native push

0.1.0 delivers only while the app runs. Next comes FCM integration: a `FirebaseMessagingService` that passes `onNewToken` to `register(pushToken = …)`, and dedupe of FCM data messages by their `ialert_id` against Gateway/Inbox messages. An Android-library (AAR) wrapper with a SharedPreferences storage built in also belongs to that step.

## Development

Needs JDK 17 or later; Gradle comes with the wrapper:

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew test   # fakes for HTTP and WebSocket, no device needed
```

API reference: `docs/api-v1.md` in the iAlert server repository.

## License

Copyright 2026 iAlert.ir

Licensed under the [Apache License, Version 2.0](LICENSE). See [NOTICE](NOTICE).
