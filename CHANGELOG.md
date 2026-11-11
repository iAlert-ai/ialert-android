# Changelog

## 0.5.0

- Now an Android library (AAR) with FCM built in. `IAlert.start(context, publicKey)`: one-line setup with no Firebase files or `FirebaseMessagingService` in the app; FCM uses the app's own Firebase project, whose service account and `google-services.json` are uploaded in the iAlert panel (`GET /v1/push-config?package=…`). Without them: Gateway, Inbox and background sync.
- Notifications: shown for FCM and Gateway messages with image, tagged with the iAlert id (never twice); taps open the app or `data.url` and are acked clicked; permission asked on Android 13+.
- Custom sounds: channels `ialert_default`, `ialert_silent` and `ialert_sound_<name>` for sounds in `res/raw`; `IAlertMessage.sound`.
- Outages: WorkManager sync every 15 minutes shows undelivered Inbox messages that no push service took (`backgroundSync`, using the Inbox `pushed` flag); the Gateway is open while the app is visible.
- Live Activity broadcasts are shown as one ongoing notification per channel unless the app listens with `IAlert.onLiveActivityUpdate`.
- `IAlertBridge` for the Flutter and React Native wrappers.
- Native push API for your own service: `didReceivePush`, `didOpenPush`, `IAlert.handleFcmMessage`, `IAlert.handleFcmToken`, `IAlert.onLiveActivityUpdate`.
- Auth Push: `respondToAuthRequest` and `AuthRequestInfo.from(message)`.
- `IAlert.login(externalUserId)`, `SharedPreferencesStorage`, `pushConfig()`.
- Versions 0.3.0 and 0.4.0 were skipped on Android to line up with the other SDKs.

## 0.2.0

- Live Activity broadcasts on Android: `subscribeLiveActivity(channelId)` / `unsubscribeLiveActivity(channelId)` join or leave a channel's FCM topic, and `LiveActivityUpdate.from(data)` reads the broadcast from an FCM data message.
- A 404 for an unknown channel no longer clears the device registration.

## 0.1.0

- First release: device registration with a public key, tags and external user id, a live connection to the iAlert Gateway over WebSocket with reconnect, Inbox sync after each connect, delivered/read/clicked acknowledgements and message de-duplication.
- Licensed under Apache 2.0.
