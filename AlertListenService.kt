package com.m9r1u5.panicguard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Built-in alert receiver (Android): keeps a connection open to the alert code(s) on ntfy.sh,
 * so contacts do not need the separate ntfy app. Shows a loud alarm notification with the map link.
 */
class AlertListenService : Service() {

    companion object {
        private const val CH_SVC = "pg_listen"
        private const val CH_ALERT = "pg_alert"
        private const val CH_INFO = "pg_info"
        private const val ID_SVC = 1201
        @Volatile var connected = false

        fun start(ctx: Context) {
            try { ContextCompat.startForegroundService(ctx, Intent(ctx, AlertListenService::class.java)) } catch (e: Exception) {
                try { PanicService.log(ctx, "Receiver could not start: ${e.message}") } catch (x: Exception) { }
            }
        }
        fun stop(ctx: Context) { try { ctx.stopService(Intent(ctx, AlertListenService::class.java)) } catch (e: Exception) { } }
        fun codes(ctx: Context): List<String> =
            Store.get(ctx, "rxCodes").split(Regex("[,\\s]+")).map { it.trim() }
                .filter { Regex("^[A-Za-z0-9_-]{6,64}$").matches(it) }.distinct()
    }

    @Volatile private var stopping = false
    @Volatile private var conn: HttpURLConnection? = null
    private var thread: Thread? = null

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Store.get(this, "rxOn") != "true" || codes(this).isEmpty()) { stopSelf(); return START_NOT_STICKY }
        makeChannels()
        val n = NotificationCompat.Builder(this, CH_SVC)
            .setContentTitle("Panic Guard is listening for alerts")
            .setContentText("Tap to open")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true).setSilent(true)
            .setContentIntent(openApp(null))
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(ID_SVC, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(ID_SVC, n)
        } catch (e: Exception) { PanicService.log(this, "Receiver could not go foreground: ${e.message}"); stopSelf(); return START_NOT_STICKY }
        if (thread?.isAlive != true) {
            stopping = false
            thread = Thread { loop() }.also { it.isDaemon = true; it.start() }
        } else {
            // Codes may have changed: drop the current connection so the loop reconnects with the new list.
            try { conn?.disconnect() } catch (e: Exception) { }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopping = true
        connected = false
        try { conn?.disconnect() } catch (e: Exception) { }
        thread?.interrupt()
        super.onDestroy()
    }

    private fun loop() {
        var backoff = 2000L
        var announced = false
        while (!stopping) {
            val list = codes(this)
            if (Store.get(this, "rxOn") != "true" || list.isEmpty()) { stopSelf(); return }
            try {
                val since = Store.get(this, "rxSince").ifBlank { (System.currentTimeMillis() / 1000).toString() }
                val c = URL("https://ntfy.sh/" + list.joinToString(",") + "/json?since=" + since).openConnection() as HttpURLConnection
                conn = c
                c.connectTimeout = 15000
                c.readTimeout = 120000   // ntfy sends a keepalive about every 45 s
                if (c.responseCode !in 200..299) throw java.io.IOException("HTTP ${c.responseCode}")
                connected = true
                if (!announced) { PanicService.log(this, "Alert receiver connected"); announced = true }
                backoff = 2000L
                c.inputStream.bufferedReader().use { r ->
                    while (!stopping) {
                        val line = r.readLine() ?: break
                        handle(line)
                    }
                }
            } catch (e: Exception) {
                if (!stopping) {
                    if (announced) { PanicService.log(this, "Alert receiver lost connection, retrying"); announced = false }
                }
            } finally {
                connected = false
                try { conn?.disconnect() } catch (e: Exception) { }
            }
            if (stopping) return
            try { Thread.sleep(backoff) } catch (e: InterruptedException) { return }
            backoff = minOf(backoff * 2, 60000L)
        }
    }

    private fun handle(line: String) {
        try {
            if (line.isBlank()) return
            val j = JSONObject(line)
            if (j.optString("event") != "message") return
            val id = j.optString("id")
            if (id.isNotBlank()) Store.set(this, "rxSince", id)
            val title = j.optString("title")
            val body = j.optString("message")
            val topic = j.optString("topic")
            val ageSec = System.currentTimeMillis() / 1000 - j.optLong("time", System.currentTimeMillis() / 1000)
            if (ageSec > 24 * 3600) return
            val isUpdate = body.contains("[Panic Guard update]")
            val isSafe = title.contains("safe", true) && !title.contains("PANIC", true)
            val isTest = title.contains("TEST", true)
            PanicService.log(this, "Alert received: " + (title + " " + body).take(40))
            notifyUser(topic, title, body, isUpdate, isSafe, isTest)
            Forwarder.forward(this, title, body)
        } catch (e: Exception) {
            try { PanicService.log(this, "Alert read failed: ${e.message}") } catch (x: Exception) { }
        }
    }

    private fun makeChannels() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CH_SVC, "Listening for alerts", NotificationManager.IMPORTANCE_LOW)
            .apply { setShowBadge(false) })
        val alarm: Uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val aa = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        nm.createNotificationChannel(NotificationChannel(CH_ALERT, "Panic alerts", NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(alarm, aa)
            enableVibration(true); vibrationPattern = longArrayOf(0, 800, 300, 800, 300, 800)
            enableLights(true); lightColor = 0xFFFF0000.toInt()
            setBypassDnd(true)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        })
        nm.createNotificationChannel(NotificationChannel(CH_INFO, "Alert updates", NotificationManager.IMPORTANCE_DEFAULT))
    }

    private fun openApp(mapUrl: String?): PendingIntent {
        val i = if (mapUrl != null) Intent(Intent.ACTION_VIEW, Uri.parse(mapUrl))
        else (packageManager.getLaunchIntentForPackage(packageName) ?: Intent())
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(this, (mapUrl ?: "app").hashCode(), i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun notifyUser(topic: String, title: String, body: String, isUpdate: Boolean, isSafe: Boolean, isTest: Boolean) {
        makeChannels()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val link = Regex("https?://\\S+").find(body)?.value
        val loud = !isUpdate && !isSafe
        val t = if (title.isNotBlank()) title else "Panic Guard"
        val b = NotificationCompat.Builder(this, if (loud) CH_ALERT else CH_INFO)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(t)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(openApp(link))
            .setPriority(if (loud) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_DEFAULT)
        if (link != null) b.addAction(android.R.drawable.ic_menu_mylocation, "OPEN MAP", openApp(link))
        if (loud) {
            b.setCategory(NotificationCompat.CATEGORY_ALARM)
            b.setFullScreenIntent(openApp(null), true)
            b.setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        } else {
            b.setOnlyAlertOnce(true)
        }
        val n = b.build()
        // A real alert keeps ringing until it is opened or swiped away; tests only ring once.
        if (loud && !isTest) n.flags = n.flags or Notification.FLAG_INSISTENT
        // Updates replace each other per sender; real alerts each get their own notification.
        val nid = if (loud) (3000 + (System.currentTimeMillis() % 100000).toInt()) else (2000 + (topic.hashCode() and 0xFFF))
        try { nm.notify(nid, n) } catch (e: Exception) { PanicService.log(this, "Could not show alert: ${e.message}") }
    }
}
