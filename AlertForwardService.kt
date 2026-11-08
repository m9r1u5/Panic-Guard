package com.m9r1u5.panicguard

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Optional receiver feature: when the ntfy app shows a Panic Guard alert on THIS phone,
 * forward it as an SMS to a number the user chose. Only active when switched on in Settings.
 * (If the built-in receiver is connected it forwards directly, so this one stays out of the way.)
 */
class AlertForwardService : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        try {
            if (sbn == null) return
            if (Store.get(this, "fwdOn") != "true") return
            val pkg = sbn.packageName
            // The Android ntfy app is io.heckel.ntfy (Play Store / F-Droid); io.ntfy kept for other builds.
            if (!pkg.startsWith("io.heckel.ntfy") && !pkg.startsWith("io.ntfy")) return
            val n = sbn.notification ?: return
            if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) {
                PanicService.log(this, "ntfy: ignored a group summary notification"); return
            }
            val ex = n.extras
            val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
            val body = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT)
                ?: ex.getCharSequence(Notification.EXTRA_TEXT))?.toString() ?: ""
            val all = "$title $body"
            PanicService.log(this, "ntfy notification seen: " + all.take(40))
            if (!all.contains("PANIC ALERT") && !all.contains("Panic Guard")) {
                PanicService.log(this, "ntfy: not a Panic Guard alert, ignored"); return
            }
            if (AlertListenService.connected && Store.get(this, "rxOn") == "true") {
                PanicService.log(this, "ntfy: built-in receiver handles this one"); return
            }
            Forwarder.forward(this, title, body)
        } catch (e: Exception) {
            try { PanicService.log(this, "Forward failed: ${e.message}") } catch (x: Exception) { }
        }
    }
}
