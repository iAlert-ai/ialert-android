package ir.ialert.sdk

import android.app.Activity
import android.content.Context
import android.os.Bundle
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

/**
 * FCM entry point, declared in the SDK's manifest. If your app has its own
 * `FirebaseMessagingService`, call [IAlert.handleFcmMessage] and [IAlert.handleFcmToken] from it.
 */
public class IAlertMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        IAlert.handleFcmToken(this, token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        IAlert.handleFcmMessage(this, message)
    }
}

/** Opens the app (or `data.url`) for a tap on a notification the SDK showed, and acks it. */
public class IAlertOpenActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!AutoSetup.open(this, intent.extras)) {
            packageManager.getLaunchIntentForPackage(packageName)?.let { launch ->
                runCatching { startActivity(launch.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
        }
        finish()
    }
}

/** Every 15 minutes: shows Inbox messages that FCM and the Gateway could not deliver (outages). */
public class IAlertSyncWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val ialert = AutoSetup.restore(applicationContext) ?: return Result.success()
        if (ialert.deviceId == null) return Result.success()

        val latch = CountDownLatch(1)
        suspend { ialert.backgroundSync(AndroidNotifier(applicationContext)) }
            .startCoroutine(Continuation(EmptyCoroutineContext) { latch.countDown() })
        latch.await(5, TimeUnit.MINUTES)

        return Result.success()
    }
}
