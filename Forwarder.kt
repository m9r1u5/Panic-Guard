package com.m9r1u5.panicguard

import android.content.Context
import android.os.Build
import android.telephony.SmsManager

/** Sends a received Panic Guard alert on as an SMS (the "forward" setting). Shared by both receivers. */
object Forwarder {
    private var lastHash = 0
    private var lastAt = 0L

    @Suppress("DEPRECATION")
    private fun sms(ctx: Context): SmsManager =
        if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(SmsManager::class.java) else SmsManager.getDefault()

    @Synchronized
    fun forward(ctx: Context, title: String, body: String) {
        try {
            if (Store.get(ctx, "fwdOn") != "true") return
            val isUpdate = body.contains("[Panic Guard update]")
            if (isUpdate && Store.get(ctx, "fwdUpd") != "true") {
                PanicService.log(ctx, "Alert: location update not forwarded (option is off)"); return
            }
            val to = Store.get(ctx, "fwdNum").trim()
            if (to.isBlank()) { PanicService.log(ctx, "Forward skipped: no number set"); return }
            val h = body.trim().hashCode()
            val now = System.currentTimeMillis()
            if (h == lastHash && now - lastAt < 60000) { PanicService.log(ctx, "Alert: duplicate ignored"); return }
            lastHash = h; lastAt = now

            // Emoji / special characters make some phones refuse to split the message, so keep plain text only.
            val raw = (if (title.isNotBlank()) "[$title] " else "") + body
            val text = raw.filter { it.code in 32..126 || it == '\n' }
                .replace(Regex("\\[\\s+"), "[").replace(Regex(" +"), " ").trim()
            val sm = sms(ctx)
            try {
                val parts = sm.divideMessage(text)
                if (parts.size == 1) sm.sendTextMessage(to, null, parts[0], null, null)
                else sm.sendMultipartTextMessage(to, null, parts, null, null)
            } catch (e: Exception) {
                PanicService.log(ctx, "Split failed (${e.message}), sending in short pieces")
                text.chunked(150).forEach { sm.sendTextMessage(to, null, it, null, null) }
            }
            PanicService.log(ctx, "Forwarded alert by SMS to $to")
        } catch (e: Exception) {
            try { PanicService.log(ctx, "Forward failed: ${e.message}") } catch (x: Exception) { }
        }
    }
}
