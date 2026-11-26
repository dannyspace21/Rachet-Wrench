// Workers.kt — background jobs run by Android's WorkManager.
//
//  * SyncWorker      — runs a sync whenever the phone is online: right after
//                      you change something, and every 6 hours.  If the
//                      server can't be reached it retries later with backoff.
//  * ReminderWorker  — twice a day, checks every reminder ON THE PHONE (no
//                      internet needed) and shows a local notification for
//                      anything due soon or overdue.  Each reminder notifies
//                      at most once every 3 days.  No email, ever.
package com.ratchetwrench.app.sync

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.ratchetwrench.app.MainActivity
import com.ratchetwrench.app.R
import com.ratchetwrench.app.data.ApiException
import com.ratchetwrench.app.data.AppDatabase
import com.ratchetwrench.app.data.SecureStore
import com.ratchetwrench.app.domain.Catalog
import com.ratchetwrench.app.domain.Reminders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            SyncEngine(applicationContext).run()
            Result.success()
        } catch (e: ApiException) {
            // 401 = signed out on the server; don't hammer it.
            if (e.status == 401 || e.status == 0) Result.failure() else Result.retry()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        /** Sync as soon as the phone is online (collapses repeated requests). */
        fun runSoon(context: Context) {
            val req = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(online)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("sync-now", ExistingWorkPolicy.REPLACE, req)
        }

        fun schedulePeriodic(context: Context) {
            val req = PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS).setConstraints(online).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("sync-periodic", ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}

class ReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        checkAndNotify(applicationContext)
        Result.success()
    }

    companion object {
        const val CHANNEL = "reminders"
        private const val RENOTIFY_MS = 3L * 24 * 60 * 60 * 1000

        fun schedule(context: Context) {
            val req = PeriodicWorkRequestBuilder<ReminderWorker>(12, TimeUnit.HOURS).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("reminders", ExistingPeriodicWorkPolicy.KEEP, req)
        }

        fun createChannel(context: Context) {
            val channel = NotificationChannel(CHANNEL, context.getString(R.string.channel_reminders), NotificationManager.IMPORTANCE_DEFAULT)
            channel.description = context.getString(R.string.channel_reminders_desc)
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        /** Can this app show notifications right now? (permission + not blocked in Android settings) */
        fun canNotify(context: Context): Boolean =
            (android.os.Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
                NotificationManagerCompat.from(context).areNotificationsEnabled()

        /** How many reminders are due or overdue right now (whether or not notified). */
        suspend fun dueCount(context: Context): Int {
            val db = AppDatabase.get(context)
            val vehicles = db.vehicles().all().associateBy { it.id }
            return db.reminders().all().count { r ->
                val v = vehicles[r.vehicle_id] ?: return@count false
                Reminders.status(r, v.current_mileage).status.let { it == "due_soon" || it == "overdue" }
            }
        }

        /**
         * Check all reminders on the phone and notify about due ones.  The
         * twice-a-day check repeats a reminder at most every 3 days;
         * [force] ("Check reminders now") shows every due one immediately.
         */
        suspend fun checkAndNotify(context: Context, force: Boolean = false): Int {
            val store = SecureStore(context)
            if (!store.notificationsEnabled) return 0
            if (!canNotify(context)) return 0
            val db = AppDatabase.get(context)
            val vehicles = db.vehicles().all().associateBy { it.id }
            var sent = 0
            val now = System.currentTimeMillis()
            for (r in db.reminders().all()) {
                val v = vehicles[r.vehicle_id] ?: continue
                val st = Reminders.status(r, v.current_mileage)
                if (st.status != "due_soon" && st.status != "overdue") continue
                if (!force && now - store.lastNotified(r.id) < RENOTIFY_MS) continue
                val what = r.title.ifBlank { Catalog.typeLabel(context, r.type_key) }
                val open = PendingIntent.getActivity(context, r.id.hashCode(),
                    Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                val n = NotificationCompat.Builder(context, CHANNEL)
                    .setSmallIcon(R.drawable.ic_ui_bell)
                    .setContentTitle((if (st.status == "overdue") "Overdue: " else "Due soon: ") + what)
                    .setContentText("${v.displayName()} — ${st.describe(v.mileage_unit)}")
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .setPriority(if (st.status == "overdue") NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
                    .build()
                try {
                    NotificationManagerCompat.from(context).notify(r.id.hashCode(), n)
                    store.setLastNotified(r.id, now)
                    sent++
                } catch (e: SecurityException) { /* permission revoked meanwhile */ }
            }
            return sent
        }
    }
}
