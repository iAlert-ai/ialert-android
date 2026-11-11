# iAlert Android SDK

iAlert SDK for Android (Kotlin, Android library, minSdk 23). Install it and add one line: the SDK registers the device, gets an FCM token, shows notifications and keeps working when FCM is unreachable, through iAlert's own channel: a WebSocket to the iAlert Gateway while the app is visible, the device Inbox, and a background sync every 15 minutes. Each notification reaches the user once, across all channels and restarts.

No `google-services.json`, Firebase setup or `FirebaseMessagingService` is needed: unless you uploaded your own FCM service account in the panel, the SDK uses iAlert's shared Firebase project (`GET /v1/push-config`).

## Install

Add JitPack to `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}
```

Then the dependency in your app module:

```kotlin
implementation("com.github.iAlert-ai:ialert-android:v0.5.0")
```

## Quick start

```kotlin
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        IAlert.start(this, "ia_pk_live_…")
    }
}

IAlert.login("user-42")   // after sign-in; IAlert.login(null) on sign-out
```

`IAlert.start` does all of this:

- Registers the device (`platform: android`) with its FCM token, and again when FCM rotates it.
- Asks for the notification permission on Android 13+ the first time an activity shows (`requestPermission = false` to ask yourself).
- Shows iAlert messages from FCM and the Gateway as notifications, with their image, and acks them as delivered. A tap opens the app, or `data.url` when the message has one, and is acked as clicked.
- Keeps the Gateway socket open while an activity is visible and catches up on the Inbox after each connect.
- Every 15 minutes (WorkManager, with network) shows Inbox messages that no channel delivered, skipping those already in the tray: this is how messages arrive while FCM is blocked.

`IAlert.shared` is the instance it created, with the full API below (`onMessage`, `setTags`, `ack`, …).

### Custom sounds

Put the sound in `res/raw` (for example `res/raw/order.mp3`) and send with `"sound": "order.mp3"`. The SDK creates one notification channel per sound (`ialert_sound_order`), plus `ialert_default` and `ialert_silent` (`"sound": "none"`). On Android 8+ the channel decides the sound, so a sound file added in a later app version gets its channel the first time a message uses it.

### Icon

The small icon is your app icon. For a monochrome one add to your manifest:

```xml
<meta-data android:name="ir.ialert.sdk.notification_icon" android:resource="@drawable/ic_notification" />
```

### Your own FirebaseMessagingService

Only one service receives FCM messages. If your app has its own, forward to the SDK:

```kotlin
override fun onMessageReceived(message: RemoteMessage) {
    if (IAlert.handleFcmMessage(this, message)) return
    // your messages
}

override fun onNewToken(token: String) = IAlert.handleFcmToken(this, token)
```

If your manifest sets `com.google.firebase.messaging.default_notification_channel_id`, add `tools:replace="android:value"` to it.

### Live Activity broadcasts

After `IAlert.start`, `IAlert.shared?.subscribeLiveActivity(channelId)` is all it takes: the SDK shows each channel as one ongoing notification, updated in place and removed on `end`. To build the notification yourself, listen with `IAlert.onLiveActivityUpdate { update -> … }` (the SDK then shows nothing).

### Flutter and React Native

`IAlertBridge` is the entry point the Flutter plugin and the React Native module use: their own client registers the device and runs the Gateway, and this SDK provides the FCM token, notifications, taps and background sync.

### Auth Push

```kotlin
IAlert.shared?.onMessage { message ->
    val request = AuthRequestInfo.from(message) ?: return@onMessage
    // show request.context (IP, city…), ask for request.numberMatching's number, then:
    lifecycleScope.launch { IAlert.shared?.respondToAuthRequest(request.id, approve = true, code = typed) }
}
```

`respondToAuthRequest` returns the recorded status (`approved`, `denied`, `pending`, `expired`). With number matching a wrong number denies.

## Manual setup

For full control, skip `IAlert.start` and drive an instance yourself:


```kotlin
val ialert = IAlert("ia_pk_live_…", SharedPreferencesStorage(applicationContext))

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
- `register(pushToken = …)` sends your FCM token. Without one, a stable installation id is used and messages come over the Gateway and Inbox only. Calling it on every launch is safe.
- From your own `FirebaseMessagingService`: `didReceivePush(message.data, title, body, image)` (deduplicated, acked delivered) and, for taps, `didOpenPush(data)` (acked clicked). `backgroundSync(AndroidNotifier(context))` shows undelivered Inbox messages.
- API calls are `suspend` functions. Listeners run on a background thread.
- `delivered` is acked automatically (`autoAckDelivered = false` turns it off). Report `READ` / `CLICKED` yourself. Acks go over the socket when it is ready, otherwise over HTTP.
- `IAlert.Config` lets you inject `transport`, `webSocketFactory`, `scheduler`, `apiUrl`, `gatewayUrl`, `autoConnect` and the ping/reconnect timings.

## Live Activity broadcasts

One push from your server updates an ongoing notification on every subscribed device (scores, deliveries, flights). Your server creates the channel with `POST /v1/live-activity-channels` and gives its `id` to the app.

Android delivers these through FCM topics, so register with the device's FCM token, then join the channel:

```kotlin
ialert.register(pushToken = fcmToken)
ialert.subscribeLiveActivity(channelId)   // and ialert.unsubscribeLiveActivity(channelId)
```

Subscribe again whenever FCM gives the app a new token. Each broadcast arrives as an FCM data message. With `IAlert.start`, listen with `IAlert.onLiveActivityUpdate { update -> … }`; in your own service, `LiveActivityUpdate.from` reads it (it returns `null` for any other message):

```kotlin
class PushService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        val update = LiveActivityUpdate.from(message.data) ?: return
        val manager = NotificationManagerCompat.from(this)
        if (update.isEnd) { manager.cancel(update.notificationId); return }
        val notification = NotificationCompat.Builder(this, "live_updates")
            .setSmallIcon(R.drawable.ic_live)
            .setContentTitle(update.title ?: "Live")
            .setContentText(update.body ?: update.contentState.toString())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        manager.notify(update.notificationId, notification) // same id: updated in place
    }
}
```

On Android 16 you can also ask for the notification to be promoted to a Live Update (see Android's "Live Updates" guide).

## Development

Needs JDK 17 or later and the Android SDK (`local.properties` with `sdk.dir`, or `ANDROID_HOME`); Gradle comes with the wrapper:

```bash
./gradlew testDebugUnitTest   # fakes for HTTP and WebSocket, no device needed
./gradlew assembleRelease     # the AAR
```

API reference: `docs/api-v1.md` in the iAlert server repository.

## License

Copyright 2026 iAlert.ir

Licensed under the [Apache License, Version 2.0](LICENSE). See [NOTICE](NOTICE).
