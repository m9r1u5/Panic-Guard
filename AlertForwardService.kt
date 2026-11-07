package com.m9r1u5.panicguard

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.telephony.SmsManager
import android.os.Build

/**
 * Optional receiver feature: when the ntfy app shows a Panic Guard alert on THIS phone,
 * forward it as an SMS to a number the user chose. Only active when switched on in Settings.
 */
class AlertForwardService : NotificationListenerService() {

    private var lastHash = 0
    private var lastAt = 0L

    @Suppress("DEPRECATION")
    private fun sms(): SmsManager =
        if (Build.VERSION.SDK_INT >= 31) getSystemService(SmsManager::class.java) else SmsManager.getDefault()

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
            // Only Panic Guard alerts, never other ntfy topics.
            PanicService.log(this, "ntfy notification seen: " + all.take(40))
            if (!all.contains("PANIC ALERT") && !all.contains("Panic Guard")) {
                PanicService.log(this, "ntfy: not a Panic Guard alert, ignored"); return
            }
            val isUpdate = body.contains("[Panic Guard update]")
            if (isUpdate && Store.get(this, "fwdUpd") != "true") {
                PanicService.log(this, "ntfy: location update not forwarded (option is off)"); return
            }
            val to = Store.get(this, "fwdNum").trim()
            if (to.isBlank()) { PanicService.log(this, "Forward skipped: no number set"); return }

            // Ignore duplicate posts of the same notification.
            val h = all.hashCode()
            val now = System.currentTimeMillis()
            if (h == lastHash && now - lastAt < 15000) { PanicService.log(this, "ntfy: duplicate ignored"); return }
            lastHash = h; lastAt = now

            // Emoji / special characters make some phones refuse to split the message, so keep plain text only.
            val raw = (if (title.isNotBlank()) "[$title] " else "") + body
            val text = raw.filter { it.code in 32..126 || it == '\n' }.replace(Regex("\\[\\s*\\]"), "").replace(Regex(" +"), " ").trim()
            val sm = sms()
            try {
                val parts = sm.divideMessage(text)
                if (parts.size == 1) sm.sendTextMessage(to, null, parts[0], null, null)
                else sm.sendMultipartTextMessage(to, null, parts, null, null)
            } catch (e: Exception) {
                // Fallback: split by hand into short separate texts.
                PanicService.log(this, "Split failed (${e.message}), sending in short pieces")
                text.chunked(150).forEach { sm.sendTextMessage(to, null, it, null, null) }
            }
            PanicService.log(this, "Forwarded ntfy alert by SMS to $to")
        } catch (e: Exception) {
            try { PanicService.log(this, "Forward failed: ${e.message}") } catch (x: Exception) { }
        }
    }
}
