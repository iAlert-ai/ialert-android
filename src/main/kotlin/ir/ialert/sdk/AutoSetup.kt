package ir.ialert.sdk

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.startCoroutine
import kotlin.coroutines.suspendCoroutine

/**
 * What [IAlert.start] sets up: FCM (the app's own Firebase project, its client from
 * `GET /v1/push-config`), notification channels, display of iAlert messages, taps,
 * the Gateway while the app is visible and a WorkManager sync every 15 minutes.
 */
internal object AutoSetup {
    private const val PREFS = "ialert_setup"
    private const val KEY_PUBLIC_KEY = "public_key"
    private const val KEY_API_URL = "api_url"
    private const val KEY_FCM_TOKEN = "fcm_token"
    private const val KEY_ASKED_PERMISSION = "asked_permission"
    private const val FIREBASE_APP_NAME = "ialert"
    private const val SYNC_WORK = "ialert-sync"
    private const val PERMISSION_REQUEST = 0x1A1E

    @Volatile
    var instance: IAlert? = null
        private set

    val liveActivityListeners = CopyOnWriteArraySet<LiveActivityListener>()

    private val display = Executors.newSingleThreadExecutor { Thread(it, "ialert-display").apply { isDaemon = true } }
    private var fields = DeviceFields()
    private var started = false
    private var visibleActivities = 0
    private var registered = false
    private var registering = false
    private var requestPermission = true
    private var callbacksRegistered = false

    /** Set by [IAlertBridge.attach]: a wrapper (Flutter, React Native) owns registration and the Gateway. */
    @Volatile
    var bridge: IAlertBridge.Events? = null

    @Volatile
    var clickHandler: NotificationClickHandler? = null
    private var hostMode = false
    private var pendingOpen: Map<String, String>? = null
    private var resumedActivity: java.lang.ref.WeakReference<Activity>? = null

    fun start(context: Context, publicKey: String, fields: DeviceFields, requestPermission: Boolean): IAlert {
        val app = context.applicationContext as Application
        val ialert = checkNotNull(restore(app, publicKey))

        synchronized(this) {
            if (started) return ialert
            started = true
            this.fields = fields
            this.requestPermission = requestPermission
        }

        registerCallbacks(app)
        scheduleSync(app)
        register(app)

        return ialert
    }

    /** Wrapper mode: channels, taps, the FCM service and background sync, but no registration or Gateway. */
    fun attachHost(context: Context, requestPermission: Boolean) {
        val app = context.applicationContext as Application
        synchronized(this) {
            hostMode = true
            this.requestPermission = requestPermission
        }
        AndroidNotifier(app).ensureChannels()
        registerCallbacks(app)
        scheduleSync(app)
    }

    /** Remember the wrapper's key and registration so FCM and WorkManager can act on cold starts. */
    fun saveHost(context: Context, publicKey: String, apiUrl: String, deviceId: String?, deviceToken: String?) {
        val app = context.applicationContext
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_PUBLIC_KEY, publicKey).putString(KEY_API_URL, apiUrl).apply()
        if (deviceId != null && deviceToken != null) {
            SharedPreferencesStorage(app).putString(
                "ialert:${publicKey.takeLast(12)}:registration",
                org.json.JSONObject().put("device_id", deviceId).put("device_token", deviceToken).toString(),
            )
            // Re-read the registration on next use.
            synchronized(this) { instance?.disconnect(); instance = null }
        }
    }

    suspend fun hostToken(context: Context, publicKey: String, apiUrl: String): String? {
        saveHost(context, publicKey, apiUrl, null, null)
        val ialert = restore(context) ?: return null
        return currentToken(context.applicationContext, ialert)
    }

    /**
     * The FCM token to register with. The cached one only stands in while push-config cannot be
     * read (offline) or FCM fails; when the app has no FCM credential any more, none is used.
     */
    private suspend fun currentToken(context: Context, ialert: IAlert): String? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val cached = prefs.getString(KEY_FCM_TOKEN, null)
        val config = runCatching { ialert.pushConfig(context.packageName) }.getOrNull() ?: return cached

        if (config.fcmSource != "app") {
            prefs.edit().remove(KEY_FCM_TOKEN).apply()
            return null
        }

        val token = runCatching { fcmToken(context, config) }.getOrNull() ?: return cached
        prefs.edit().putString(KEY_FCM_TOKEN, token).apply()
        return token
    }

    fun takePendingOpen(): Map<String, String>? = synchronized(this) { pendingOpen.also { pendingOpen = null } }

    /** Ask for POST_NOTIFICATIONS now (Android 13+); false when it cannot be asked here. */
    fun askPermissionNow(): Boolean {
        val activity = resumedActivity?.get() ?: return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        val permission = android.Manifest.permission.POST_NOTIFICATIONS
        if (ContextCompat.checkSelfPermission(activity, permission) == PackageManager.PERMISSION_GRANTED) return true
        activity.requestPermissions(arrayOf(permission), PERMISSION_REQUEST)
        return false
    }

    private fun registerCallbacks(app: Application) {
        synchronized(this) {
            if (callbacksRegistered) return
            callbacksRegistered = true
        }
        app.registerActivityLifecycleCallbacks(Lifecycle)
    }

    /** The instance for a cold start by FCM or WorkManager: the key [start] saved last time. */
    fun restore(context: Context, publicKey: String? = null): IAlert? = synchronized(this) {
        instance?.let { return it }
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = publicKey ?: prefs.getString(KEY_PUBLIC_KEY, null) ?: return null
        prefs.edit().putString(KEY_PUBLIC_KEY, key).apply()

        val notifier = AndroidNotifier(app).also { it.ensureChannels() }
        val apiUrl = prefs.getString(KEY_API_URL, null)
        val config = IAlert.Config(key, SharedPreferencesStorage(app), autoConnect = false)
        val ialert = IAlert(if (apiUrl != null) config.copy(apiUrl = apiUrl) else config)

        // Real-time messages (Gateway, FCM while the app runs) become notifications; the
        // Inbox is caught up silently in the foreground and shown by the background sync.
        ialert.onMessage { message ->
            if (message.source != IAlertMessage.Source.INBOX) display.execute { notifier.show(message) }
        }

        instance = ialert
        ialert
    }

    fun login(externalUserId: String?) {
        synchronized(this) {
            fields = if (externalUserId == null) fields.copy(externalUserId = null, clearExternalUserId = true) else fields.copy(externalUserId = externalUserId, clearExternalUserId = false)
        }
        val ialert = instance ?: return
        if (ialert.deviceId == null) return
        launch { runCatching { ialert.setExternalUserId(externalUserId) } }
    }

    /** A new FCM token: register again with it. */
    fun onNewToken(context: Context, token: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_FCM_TOKEN, token).apply()
        if (synchronized(this) { hostMode }) {
            bridge?.onToken(token)
            return
        }
        val ialert = restore(context) ?: return
        launch { runCatching { ialert.register(pushToken = token, fields = synchronized(this) { fields }) } }
    }

    /**
     * A tap on an iAlert notification (ours or one FCM showed): ack it and open its `url`
     * (`data.url` for older messages). A deep link opens inside this app; a web link opens the
     * browser (or a verified App Link). Returns whether the app was brought up or a link opened.
     */
    fun open(context: Context, extras: Bundle?): Boolean {
        val data = extras?.toStringMap() ?: return false
        if (data[AndroidNotifier.EXTRA_ID].isNullOrBlank()) return false
        restore(context)?.let { ialert -> launch { ialert.didOpenPush(data) } }
        if (synchronized(this) { hostMode }) {
            // Kept for takeInitialOpen too: on a cold start the wrapper may not listen yet (it dedupes by id).
            synchronized(this) { pendingOpen = data }
            bridge?.onOpen(data)
        }

        val link = data[AndroidNotifier.EXTRA_URL]?.takeIf { it.isNotBlank() } ?: data["url"]?.takeIf { it.isNotBlank() }
        val handler = clickHandler
        if (handler != null && runCatching { handler.onClick(link, data) }.getOrDefault(false)) {
            return false
        }

        val url = link?.let(Uri::parse)?.takeIf { it.scheme != null } ?: return false

        return openLink(context, url)
    }

    /**
     * Web links go to whatever handles them (browser or a verified App Link). Other schemes are
     * deep links: first resolved inside this app, otherwise the launcher activity gets the link
     * as its intent data (and the `ialert_url` extra) to route it.
     */
    internal fun openLink(context: Context, url: Uri): Boolean {
        val scheme = url.scheme?.lowercase() ?: return false
        if (scheme in BLOCKED_SCHEMES) return false

        val view = Intent(Intent.ACTION_VIEW, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (scheme == "http" || scheme == "https") {
            return runCatching { context.startActivity(view) }.isSuccess
        }

        if (runCatching { context.startActivity(Intent(view).setPackage(context.packageName)) }.isSuccess) return true

        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return false
        return runCatching {
            context.startActivity(launch.setData(url).putExtra(AndroidNotifier.EXTRA_URL, url.toString()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess
    }

    private val BLOCKED_SCHEMES = setOf("javascript", "file", "content", "data", "intent")

    private fun register(context: Context) {
        val ialert = instance ?: return
        synchronized(this) {
            if (registered || registering) return
            registering = true
        }

        launch {
            try {
                val token = currentToken(context, ialert)
                ialert.register(pushToken = token, fields = synchronized(this) { fields })
                synchronized(this) { registered = true }
                if (synchronized(this) { visibleActivities > 0 }) runCatching { ialert.connect() }
            } catch (_: Exception) {
                // Offline or rejected: tried again on the next app start.
            } finally {
                synchronized(this) { registering = false }
            }
        }
    }

    /**
     * An FCM token of the app's own Firebase project: with the client iAlert holds (from the
     * uploaded google-services.json), else the app's default FirebaseApp if it sets one up itself.
     * Null without an FCM credential in the panel: then Gateway and Inbox only.
     */
    private suspend fun fcmToken(context: Context, config: PushConfig): String? {
        val client = config.fcmClient
            ?: return if (FirebaseApp.getApps(context).any { it.name == FirebaseApp.DEFAULT_APP_NAME }) FirebaseMessaging.getInstance().token.await() else null

        val app = FirebaseApp.getApps(context).firstOrNull { it.name == FIREBASE_APP_NAME } ?: FirebaseApp.initializeApp(
            context,
            FirebaseOptions.Builder()
                .setProjectId(client.projectId)
                .setApplicationId(client.applicationId)
                .setApiKey(client.apiKey)
                .setGcmSenderId(client.senderId)
                .build(),
            FIREBASE_APP_NAME,
        )
        return app.get(FirebaseMessaging::class.java).token.await()
    }

    private fun scheduleSync(context: Context) {
        runCatching {
            val request = PeriodicWorkRequestBuilder<IAlertSyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(SYNC_WORK, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }

    private object Lifecycle : Application.ActivityLifecycleCallbacks {
        override fun onActivityStarted(activity: Activity) {
            val first = synchronized(AutoSetup) { ++visibleActivities == 1 }
            if (!first || synchronized(AutoSetup) { hostMode }) return
            if (synchronized(AutoSetup) { registered }) runCatching { instance?.connect() } else register(activity.applicationContext)
        }

        override fun onActivityStopped(activity: Activity) {
            val last = synchronized(AutoSetup) { visibleActivities = maxOf(0, visibleActivities - 1); visibleActivities == 0 }
            if (last && !synchronized(AutoSetup) { hostMode }) instance?.disconnect()
        }

        override fun onActivityResumed(activity: Activity) {
            if (activity !is IAlertOpenActivity) resumedActivity = java.lang.ref.WeakReference(activity)
            // Taps on notifications FCM showed while the app was in the background.
            val intent = activity.intent
            if (activity !is IAlertOpenActivity && intent?.getStringExtra(AndroidNotifier.EXTRA_ID) != null) {
                val extras = intent.extras
                intent.removeExtra(AndroidNotifier.EXTRA_ID)
                open(activity, extras)
            }
            askPermission(activity)
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

        override fun onActivityPaused(activity: Activity) = Unit

        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    private fun askPermission(activity: Activity) {
        if (!requestPermission || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || activity is IAlertOpenActivity) return
        val permission = android.Manifest.permission.POST_NOTIFICATIONS
        if (ContextCompat.checkSelfPermission(activity, permission) == PackageManager.PERMISSION_GRANTED) return
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_ASKED_PERMISSION, false)) return
        prefs.edit().putBoolean(KEY_ASKED_PERMISSION, true).apply()
        activity.requestPermissions(arrayOf(permission), PERMISSION_REQUEST)
    }

    fun launch(block: suspend () -> Unit) {
        block.startCoroutine(Continuation(EmptyCoroutineContext) {})
    }

    private suspend fun <T> Task<T>.await(): T = suspendCoroutine { continuation ->
        addOnCompleteListener { task ->
            if (task.isSuccessful) continuation.resume(task.result) else continuation.resumeWithException(task.exception ?: IllegalStateException("FCM task failed"))
        }
    }

    @Suppress("DEPRECATION")
    private fun Bundle.toStringMap(): Map<String, String> = keySet().mapNotNull { key -> get(key)?.let { key to it.toString() } }.toMap()
}

/** Receives Live Activity broadcasts that reach the built-in FCM service. */
public fun interface LiveActivityListener {
    public fun onUpdate(update: LiveActivityUpdate)
}
