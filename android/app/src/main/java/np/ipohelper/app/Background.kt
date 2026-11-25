package np.ipohelper.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import np.ipohelper.core.Engine
import np.ipohelper.core.Notice
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * Runs about every hour, whether or not the app is open, and only when the phone has internet.
 * If the phone is offline at that moment, Android runs it as soon as the internet is back.
 */
class TickWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        Background.tick(applicationContext)
        return Result.success()
    }
}

object Background {
    private const val PERIODIC = "ipo-helper-hourly"
    private const val NOW = "ipo-helper-now"
    const val CHANNEL = "ipo-helper"

    private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun schedule(ctx: Context) {
        val req = PeriodicWorkRequestBuilder<TickWorker>(1, TimeUnit.HOURS, 15, TimeUnit.MINUTES)
            .setConstraints(online).build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    /** Run a background step soon (e.g. right after adding a person). */
    fun kick(ctx: Context) {
        val req = OneTimeWorkRequestBuilder<TickWorker>().setConstraints(online).build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, req)
    }

    fun tick(ctx: Context) {
        val store = Store(ctx)
        if (!Store.lock.tryLock(10, TimeUnit.MINUTES)) return
        try {
            val state = store.state()
            val out = Engine().tick(store.people(), state, ZonedDateTime.now())
            store.saveState(state)
            out.notices.forEach { notify(ctx, it) }
        } finally {
            Store.lock.unlock()
        }
    }

    fun createChannel(ctx: Context) {
        val ch = NotificationChannel(CHANNEL, "IPO applications", NotificationManager.IMPORTANCE_HIGH)
        ch.description = "When something is applied, when a password needs attention, and reminders."
        ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    fun canNotify(ctx: Context): Boolean =
        (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(ctx).areNotificationsEnabled()

    fun notify(ctx: Context, n: Notice) {
        if (!canNotify(ctx)) return
        val open = Intent(ctx, MainActivity::class.java).putExtra("screen", "updates")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(ctx, n.key.hashCode(), open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val note = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(0xFF165E83.toInt())
            .setContentTitle(n.title)
            .setContentText(n.body.lineSequence().first())
            .setStyle(NotificationCompat.BigTextStyle().bigText(n.body))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify(n.key.hashCode(), note)
        } catch (_: SecurityException) { /* permission was removed */ }
    }
}
