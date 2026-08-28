package dev.chanooh.alert.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_LOCKED_BOOT_COMPLETED
        ) {
            // V1 deliberately does not keep an always-on foreground service.
            // Future KernelSU / transport health initialization hooks live here.
        }
    }
}
