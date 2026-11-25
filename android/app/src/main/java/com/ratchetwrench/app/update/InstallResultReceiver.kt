// InstallResultReceiver.kt — Android's installer reports back here.
//
// "Pending user action" means Android wants you to confirm the update: we
// open its standard "Update this app?" screen.  On success Android replaces
// the app (it restarts on the new version).  Not exported: only the system
// installer can reach it, through the PendingIntent the Updater created.
package com.ratchetwrench.app.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build

class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                // Safe: this receiver is NOT exported, so the only sender is
                // Android's installer, answering the install session this app
                // created.  The extra is Android's own "Update this app?" screen.
                @Suppress("UnsafeIntentLaunch")
                confirm?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            PackageInstaller.STATUS_SUCCESS -> Updater.report(UpdateState.Idle)
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                Updater.report(UpdateState.Failed("The update wasn't installed" + (msg?.let { ": $it" } ?: ".")))
            }
        }
    }
}
