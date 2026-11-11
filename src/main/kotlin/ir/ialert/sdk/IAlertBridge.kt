package ir.ialert.sdk

import android.content.Context
import android.os.Handler
import android.os.Looper

/**
 * For SDK wrappers (the Flutter plugin, the React Native module) whose own client registers
 * the device and runs the Gateway. The Android SDK then provides the native parts only: the
 * FCM token, notification channels and display, taps (opening `data.url`), and the
 * WorkManager background sync. Apps use [IAlert.start] instead.
 */
public object IAlertBridge {
    /** Callbacks to the wrapper, always on the main thread. */
    public interface Events {
        public fun onToken(token: String)

        public fun onPush(data: Map<String, String>, title: String?, body: String?)

        public fun onOpen(data: Map<String, String>)
    }

    private val main = Handler(Looper.getMainLooper())

    /** Start the native parts; call once the wrapper's engine is up. */
    @JvmStatic
    @JvmOverloads
    public fun attach(context: Context, events: Events, requestPermission: Boolean = false) {
        AutoSetup.bridge = object : Events {
            override fun onToken(token: String) {
                main.post { events.onToken(token) }
            }

            override fun onPush(data: Map<String, String>, title: String?, body: String?) {
                main.post { events.onPush(data, title, body) }
            }

            override fun onOpen(data: Map<String, String>) {
                main.post { events.onOpen(data) }
            }
        }
        AutoSetup.attachHost(context, requestPermission)
    }

    @JvmStatic
    public fun detach() {
        AutoSetup.bridge = null
    }

    /** The FCM token for this app (iAlert's shared Firebase project or the app's own), or null. Calls back on the main thread. */
    @JvmStatic
    public fun token(context: Context, publicKey: String, apiUrl: String, callback: (String?) -> Unit) {
        AutoSetup.launch {
            val token = runCatching { AutoSetup.hostToken(context, publicKey, apiUrl) }.getOrNull()
            main.post { callback(token) }
        }
    }

    /** The registration the wrapper got, so pushes and background sync work while it is not running. */
    @JvmStatic
    public fun setRegistration(context: Context, publicKey: String, apiUrl: String, deviceId: String, deviceToken: String) {
        AutoSetup.saveHost(context, publicKey, apiUrl, deviceId, deviceToken)
    }

    /** Ask for the notification permission (Android 13+). Returns true when already granted or not needed. */
    @JvmStatic
    public fun requestPermission(): Boolean = AutoSetup.askPermissionNow()

    /** The data of the notification tap that launched the app before the wrapper attached, once. */
    @JvmStatic
    public fun takeInitialOpen(): Map<String, String>? = AutoSetup.takePendingOpen()
}
