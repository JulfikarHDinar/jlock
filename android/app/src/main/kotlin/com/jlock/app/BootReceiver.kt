package com.jlock.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Restarts the Usage-Access lock service after a reboot or an app update (the accessibility engine is
 * restarted by the system on its own). No-op when the accessibility engine is selected.
 *
 * After a reboot it starts the service at LOCKED_BOOT_COMPLETED, before the phone's first unlock: the service waits
 * for the unlock and locks from that moment. BOOT_COMPLETED alone came too late — Android hands it to every app that
 * asks, one by one, so on a phone with many apps jLock could get it a minute or more after the unlock.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            // Only device-protected storage is readable this early: the engine choice is mirrored there.
            Intent.ACTION_LOCKED_BOOT_COMPLETED ->
                if (LockStore.bootEngine(context) == LockStore.ENGINE_USAGE) UsageAccessLockService.start(context)
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> UsageAccessLockService.startIfSelected(context)
        }
    }
}
