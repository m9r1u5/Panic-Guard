package com.m9r1u5.panicguard

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.telephony.SmsManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Panic service: sends the alert SMS, repeats location SMS when you move,
 * retries failed sends, keeps a local log. Survives app kill (sticky) and reboot.
 */
class PanicService : Service() {

    companion object {
        const val ACTION_START = "com.m9r1u5.panicguard.START"
        const val ACTION_STOP = "com.m9r1u5.panicguard.STOP"
        const val ACTION_SENT = "com.m9r1u5.panicguard.SMS_SENT"
        const val CHANNEL = "pg_sync"
        const val NID = 4711
        const val DEFAULT_MSG =
            "EMERGENCY - I need help. This is an automatic alert from my Panic Guard app. Please call me or come to my location."

        @Volatile var running = false

        fun log(ctx: Context, text: String) {
            synchronized(this) {
                val arr = try { JSONArray(Store.get(ctx, "pLog", "[]")) } catch (e: Exception) { JSONArray() }
                arr.put(JSONObject().put("t", System.currentTimeMillis()).put("m", text))
                val out = JSONArray()
                for (i in maxOf(0, arr.length() - 200) until arr.length()) out.put(arr.get(i))
                Store.set(ctx, "pLog", out.toString())
            }
        }
    }

    private class Msg(val id: Int, val toName: String, val to: String, val kind: String, val text: String) {
        var attempts = 0
        var status = "queued"
        var partsOk = 0
        var partsFail = 0
        var partsTotal = 1
        val t = System.currentTimeMillis()
    }

    private val msgs = mutableListOf<Msg>()
    private var nextId = 1
    private val handler = Handler(Looper.getMainLooper())
    private var lm: LocationManager? = null
    private var lastLoc: Location? = null
    private var sentLoc: Location? = null
    private var fresh = false
    private var initialSent = false
    private var needFollowUp = false
    private var startedAt = 0L
    private var targets = listOf(1, 2)
    private var wake: PowerManager.WakeLock? = null
    private var receiverOn = false

    override fun onBind(i: Intent?): IBinder? = null

    // ------------------------------------------------------------ lifecycle

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP) {
            stopPanic(intent?.getBooleanExtra("safe", false) ?: false)
            return START_NOT_STICKY
        }
        if (!startForegroundSafe()) {
            log(this, "Could not start background service (Android refused). Check Location permission.")
            Store.set(this, "pActive", "false")
            stopSelf()
            return START_NOT_STICKY
        }
        if (action == ACTION_START) {
            Store.set(this, "pTarget", intent?.getStringExtra("target") ?: "both")
            Store.set(this, "pActive", "true")
            if (!running) beginPanic(true) else log(this, "Panic already active - second trigger ignored")
        } else if (Store.get(this, "pActive") == "true") {
            if (!running) beginPanic(false)
        } else {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun startForegroundSafe(): Boolean {
        return try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, "Background sync", NotificationManager.IMPORTANCE_LOW)
                        .apply { setShowBadge(false); lockscreenVisibility = Notification.VISIBILITY_SECRET }
                )
            }
            val n = NotificationCompat.Builder(this, CHANNEL)
                .setContentTitle("Background sync")
                .setContentText("Syncing")
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setVisibility(NotificationCompat.VISIBILITY_SECRET)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .build()
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } else {
                startForeground(NID, n)
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun targetsFrom(t: String) = when (t) { "1" -> listOf(1); "2" -> listOf(2); else -> listOf(1, 2) }

    private fun beginPanic(isNew: Boolean) {
        running = true
        val now = System.currentTimeMillis()
        startedAt = if (isNew) now else (Store.get(this, "pStart").toLongOrNull() ?: now)
        Store.set(this, "pStart", startedAt.toString())
        targets = targetsFrom(Store.get(this, "pTarget", "both"))
        log(this, if (isNew) "PANIC started, alerting " + Store.get(this, "pTarget", "both") else "Panic resumed after restart")

        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "panicguard:panic")
                .apply { acquire(6 * 3600 * 1000L) }
        } catch (e: Exception) { }

        if (!receiverOn) {
            ContextCompat.registerReceiver(this, sentReceiver, IntentFilter(ACTION_SENT), ContextCompat.RECEIVER_NOT_EXPORTED)
            receiverOn = true
        }
        startLocation()

        if (isNew) {
            // Send as soon as the first fresh fix arrives, or after 5 seconds with what we have.
            handler.postDelayed({ if (!initialSent) sendAlert("initial") }, 5000)
        } else {
            initialSent = true
            needFollowUp = true
        }
        scheduleTick()
        saveState()
    }

    private fun stopPanic(safe: Boolean) {
        handler.removeCallbacksAndMessages(null)
        targets = targetsFrom(Store.get(this, "pTarget", "both"))
        if (safe) {
            val text = "I am safe now. The panic alert has been stopped. (Panic Guard)"
            for (n in targets) {
                val num = Store.get(this, "c${n}Num")
                if (num.isBlank()) continue
                try { smsManager().sendTextMessage(num, null, text, null, null) } catch (e: Exception) { }
            }
            log(this, "I'm safe SMS handed to the phone")
        }
        log(this, "Panic stopped (unlocked)")
        try { lm?.removeUpdates(locListener) } catch (e: Exception) { }
        Store.set(this, "pActive", "false")
        running = false
        saveState(false)
        if (receiverOn) { try { unregisterReceiver(sentReceiver) } catch (e: Exception) { }; receiverOn = false }
        try { wake?.let { if (it.isHeld) it.release() } } catch (e: Exception) { }
        stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacksAndMessages(null)
        try { lm?.removeUpdates(locListener) } catch (e: Exception) { }
        if (receiverOn) { try { unregisterReceiver(sentReceiver) } catch (e: Exception) { }; receiverOn = false }
        try { wake?.let { if (it.isHeld) it.release() } } catch (e: Exception) { }
        super.onDestroy()
    }

    // ------------------------------------------------------------ location

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    private val locListener = object : LocationListener {
        override fun onLocationChanged(loc: Location) { onFix(loc) }
        override fun onProviderEnabled(p: String) {}
        override fun onProviderDisabled(p: String) {}
        override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
    }

    private fun startLocation() {
        val l = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        lm = l
        try {
            var best: Location? = null
            for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)) {
                val x = l.getLastKnownLocation(p)
                if (x != null && (best == null || x.time > best.time)) best = x
            }
            lastLoc = best
        } catch (e: SecurityException) {
            log(this, "Location permission missing")
        }
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            try {
                if (l.isProviderEnabled(p)) l.requestLocationUpdates(p, 5000L, 5f, locListener, Looper.getMainLooper())
            } catch (e: Exception) { }
        }
    }

    private fun onFix(loc: Location) {
        val cur = lastLoc
        if (cur == null || !fresh || loc.provider == LocationManager.GPS_PROVIDER ||
            loc.time - cur.time > 30000 || loc.accuracy <= cur.accuracy) {
            lastLoc = loc
        }
        fresh = true
        if (!initialSent) sendAlert("initial")
        else if (needFollowUp) { needFollowUp = false; sendAlert("update") }
        saveState()
    }

    private fun scheduleTick() {
        val min = (Store.get(this, "intervalMin", "2").toIntOrNull() ?: 2).coerceIn(1, 30)
        handler.postDelayed({
            if (running) {
                val l = lastLoc
                val s = sentLoc
                if (l != null && fresh && (s == null || l.distanceTo(s) > 25f)) sendAlert("update")
                scheduleTick()
            }
        }, min * 60_000L)
    }

    // ------------------------------------------------------------ messages

    private fun buildText(kind: String): String {
        val base = Store.get(this, "message").ifBlank { DEFAULT_MSG }
        val prefix = if (kind == "update") "[Panic Guard update] " else ""
        val l = lastLoc ?: return prefix + base + "\nLocation not available yet. I will send it when GPS finds me."
        val ageMin = (System.currentTimeMillis() - l.time) / 60000
        val note = if (!fresh && ageMin >= 1) " (last known, $ageMin min old)" else ""
        val link = String.format(Locale.US, "https://maps.google.com/?q=%.6f,%.6f", l.latitude, l.longitude)
        return prefix + base + "\nMy location$note: $link"
    }

    private fun sendAlert(kind: String) {
        initialSent = true
        val text = buildText(kind)
        sentLoc = if (fresh) lastLoc else null
        if (!fresh) needFollowUp = true
        for (n in targets) {
            val num = Store.get(this, "c${n}Num")
            if (num.isBlank()) { log(this, "Contact $n has no number"); continue }
            val m = Msg(nextId++, Store.get(this, "c${n}Name", "Contact $n"), num, kind, text)
            msgs.add(m)
            log(this, "Sending $kind SMS to ${m.toName}")
            sendMsg(m)
        }
        saveState()
    }

    private fun smsManager(): SmsManager {
        if (Build.VERSION.SDK_INT >= 31) return getSystemService(SmsManager::class.java)
        @Suppress("DEPRECATION")
        return SmsManager.getDefault()
    }

    private fun sendMsg(m: Msg) {
        m.attempts++
        m.status = "sending"
        m.partsOk = 0
        m.partsFail = 0
        try {
            val sm = smsManager()
            val parts = sm.divideMessage(m.text)
            m.partsTotal = parts.size
            val pis = ArrayList<PendingIntent>()
            for (i in parts.indices) {
                val si = Intent(ACTION_SENT).setPackage(packageName).putExtra("id", m.id)
                pis.add(PendingIntent.getBroadcast(this, m.id * 100 + i, si,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            }
            if (parts.size == 1) sm.sendTextMessage(m.to, null, parts[0], pis[0], null)
            else sm.sendMultipartTextMessage(m.to, null, parts, pis, null)
        } catch (e: Exception) {
            m.status = "failed"
            log(this, "SMS to ${m.toName} failed: ${e.message}")
            scheduleRetry(m)
        }
        saveState()
    }

    private val sentReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val m = msgs.find { it.id == i.getIntExtra("id", -1) } ?: return
            if (resultCode == Activity.RESULT_OK) m.partsOk++ else m.partsFail++
            if (m.partsOk + m.partsFail >= m.partsTotal) {
                if (m.partsFail == 0) {
                    m.status = "sent"
                    log(c, "SMS to ${m.toName} SENT (${m.kind})")
                } else {
                    m.status = "failed"
                    log(c, "SMS to ${m.toName} FAILED (try ${m.attempts}), will retry")
                    scheduleRetry(m)
                }
                saveState()
            }
        }
    }

    private fun scheduleRetry(m: Msg) {
        if (m.attempts >= 40) { m.status = "gave up"; log(this, "Gave up sending to ${m.toName}"); return }
        val delays = longArrayOf(15, 30, 60, 120, 300)
        val d = delays[minOf(m.attempts - 1, delays.size - 1)] * 1000
        handler.postDelayed({
            if (running && m.status == "failed") {
                val newer = msgs.any { it.id > m.id && it.to == m.to && it.status == "sent" }
                if (m.kind == "update" && newer) { m.status = "superseded"; saveState() } else sendMsg(m)
            }
        }, d)
    }

    // ------------------------------------------------------------ state for the UI

    private fun saveState(active: Boolean = true) {
        val o = JSONObject()
            .put("active", active)
            .put("startedAt", startedAt)
            .put("target", Store.get(this, "pTarget", "both"))
        val l = lastLoc
        if (l != null) {
            o.put("loc", JSONObject()
                .put("lat", l.latitude).put("lon", l.longitude)
                .put("ageSec", (System.currentTimeMillis() - l.time) / 1000)
                .put("fresh", fresh))
        }
        val arr = JSONArray()
        for (m in msgs.takeLast(20)) {
            arr.put(JSONObject().put("id", m.id).put("name", m.toName).put("kind", m.kind)
                .put("status", m.status).put("attempts", m.attempts).put("t", m.t))
        }
        o.put("msgs", arr)
        Store.set(this, "pState", o.toString())
    }
}
