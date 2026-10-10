package com.m9r1u5.panicguard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/** If the phone restarts during an active panic, resume the alert service. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action == Intent.ACTION_BOOT_COMPLETED && Store.get(c, "pActive") == "true") {
            try {
                ContextCompat.startForegroundService(c, Intent(c, PanicService::class.java))
            } catch (e: Exception) { }
        }
    }
}
