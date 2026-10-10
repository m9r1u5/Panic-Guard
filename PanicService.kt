package com.m9r1u5.panicguard

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
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
import android.os.VibrationEffect
import android.os.Vibrator
import android.telephony.SmsManager
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Panic service: sends the alert SMS, repeats location SMS when you move,
 * retries failed sends, keeps a local log. Survives app kill (sticky) and reboot.
 */
class PanicService : LifecycleService() {

    companion object {
        const val ACTION_START = "com.m9r1u5.panicguard.START"
        const val ACTION_STOP = "com.m9r1u5.panicguard.STOP"
        const val ACTION_TEST = "com.m9r1u5.panicguard.TEST"
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

    private class Msg(val id: Int, val toName: String, val to: String, val kind: String, val text: String, val isData: Boolean = false) {
        var attempts = 0
        var status = "queued"
        var partsOk = 0
        var partsFail = 0
        var partsTotal = 1
        var fallback: (() -> Unit)? = null
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

    // recording
    private var provider: ProcessCameraProvider? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var recActive = false
    private var recAllowed = false
    private var recSegs = 0
    private var recNote = ""
    private var camRetries = 0
    private var testMode = false
    private var recDone = false
    private var stopCb: (() -> Unit)? = null

    override fun onBind(intent: Intent): IBinder? = super.onBind(intent)

    // ------------------------------------------------------------ lifecycle

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val action = intent?.action
        if (action == ACTION_STOP) {
            stopPanic(intent?.getBooleanExtra("safe", false) ?: false)
            return START_NOT_STICKY
        }
        if (!startForegroundSafe(action == ACTION_START || action == ACTION_TEST)) {
            log(this, "Could not start background service (Android refused). Check Location permission.")
            Store.set(this, "pActive", "false")
            stopSelf()
            return START_NOT_STICKY
        }
        if (action == ACTION_START || action == ACTION_TEST) {
            if (running) {
                log(this, "Already active - new trigger ignored")
                return START_STICKY
            }
            testMode = action == ACTION_TEST
            Store.set(this, "pTarget", intent?.getStringExtra("target") ?: "both")
            Store.set(this, "pActive", if (testMode) "false" else "true")
            beginPanic(true)
        } else if (Store.get(this, "pActive") == "true") {
            if (!running) beginPanic(false)
        } else {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun has(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun startForegroundSafe(fromApp: Boolean): Boolean {
        val n: Notification = try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, "Background sync", NotificationManager.IMPORTANCE_LOW)
                        .apply { setShowBadge(false); lockscreenVisibility = Notification.VISIBILITY_SECRET }
                )
            }
            NotificationCompat.Builder(this, CHANNEL)
                .setContentTitle("Background sync")
                .setContentText("Syncing")
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setVisibility(NotificationCompat.VISIBILITY_SECRET)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .build()
        } catch (e: Exception) { return false }

        if (Build.VERSION.SDK_INT < 29) {
            return try { startForeground(NID, n); recAllowed = fromApp && has(Manifest.permission.CAMERA); true }
            catch (e: Exception) { false }
        }
        val loc = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        // Camera/microphone types may only be started while the app is on screen (Android 11+),
        // never from a boot or background restart.
        var av = 0
        if (fromApp && Build.VERSION.SDK_INT >= 30) {
            if (has(Manifest.permission.CAMERA)) av = av or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            if (has(Manifest.permission.RECORD_AUDIO)) av = av or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        if (av != 0) {
            try {
                startForeground(NID, n, loc or av)
                recAllowed = (av and ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA) != 0
                return true
            } catch (e: Exception) { log(this, "Camera/mic service type refused: ${e.message}") }
        }
        return try { startForeground(NID, n, loc); recAllowed = false; true } catch (e: Exception) { false }
    }

    private fun targetsFrom(t: String) = when (t) { "1" -> listOf(1); "2" -> listOf(2); else -> listOf(1, 2) }

    private fun beginPanic(isNew: Boolean) {
        running = true
        val now = System.currentTimeMillis()
        startedAt = if (isNew) now else (Store.get(this, "pStart").toLongOrNull() ?: now)
        Store.set(this, "pStart", startedAt.toString())
        targets = targetsFrom(Store.get(this, "pTarget", "both"))
        log(this, if (isNew) (if (testMode) "TEST started, to " else "PANIC started, alerting ") + Store.get(this, "pTarget", "both") else "Panic resumed after restart")

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
            if (recAllowed) startRecording()
            else { recNote = "Recording off: camera permission or Android refused"; log(this, recNote); recDone = true }
        }

        if (isNew) {
            // Send as soon as the first fresh fix arrives, or after 5 seconds with what we have.
            handler.postDelayed({ if (!initialSent) sendAlert("initial") }, 5000)
        } else {
            initialSent = true
            needFollowUp = true
        }
        if (testMode) handler.postDelayed({ finishTest() }, 30000) else scheduleTick()
        saveState()
    }

    private fun checkTestDone() {
        if (!testMode || !running || !recDone || !initialSent) return
        if (msgs.any { it.status == "sending" || it.status == "queued" }) return
        finishTest()
    }

    private fun finishTest() {
        if (!testMode || !running) return
        val res = JSONObject()
            .put("t", System.currentTimeMillis())
            .put("clip", recSegs > 0)
            .put("note", recNote)
            .put("loc", if (lastLoc == null) "none" else if (fresh) "live" else "last known")
        val arr = JSONArray()
        for (m in msgs) arr.put(JSONObject().put("name", m.toName).put("status", m.status))
        res.put("msgs", arr)
        Store.set(this, "pTest", res.toString())
        log(this, "TEST finished: " + msgs.joinToString { it.toName + " " + it.status } + ", clip " + (recSegs > 0))
        stopPanic(false)
    }

    private fun stopPanic(safe: Boolean) {
        handler.removeCallbacksAndMessages(null)
        targets = targetsFrom(Store.get(this, "pTarget", "both"))
        if (safe) {
            val text = "I am safe now. The panic alert has been stopped. (Panic Guard)"
            val am = Store.get(this, "alertMode", "sms")
            if (am != "data") for (n in targets) {
                val num = Store.get(this, "c${n}Num")
                if (num.isBlank()) continue
                try { smsManager().sendTextMessage(num, null, text, null, null) } catch (e: Exception) { }
            }
            val tp = Store.get(this, "dataTopic")
            if (am != "sms" && tp.isNotBlank()) Thread {
                try {
                    val c = URL("https://ntfy.sh/" + tp).openConnection() as HttpURLConnection
                    c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 10000; c.readTimeout = 10000
                    c.setRequestProperty("Title", "Panic Guard: safe")
                    c.outputStream.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                    c.responseCode; c.disconnect()
                } catch (e: Exception) { }
            }.start()
            log(this, "I'm safe SMS handed to the phone")
        }
        log(this, if (testMode) "Test finished" else "Panic stopped (unlocked)")
        try { lm?.removeUpdates(locListener) } catch (e: Exception) { }
        Store.set(this, "pActive", "false")
        running = false
        stopRecordingThen { finishStop() }
    }

    private fun finishStop() {
        try { provider?.unbindAll() } catch (e: Exception) { }
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
        try { provider?.unbindAll() } catch (e: Exception) { }
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
        val base = if (testMode) "TEST ONLY - NOT AN EMERGENCY. This is a test of my Panic Guard app. No action needed."
                   else Store.get(this, "message").ifBlank { DEFAULT_MSG }
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
        val mode = Store.get(this, "alertMode", "sms")
        val sendSms: () -> Unit = {
            var idx = 0
            for (n in targets) {
                val num = Store.get(this, "c${n}Num")
                if (num.isBlank()) { log(this, "Contact $n has no number"); continue }
                val m = Msg(nextId++, Store.get(this, "c${n}Name", "Contact $n"), num, kind, text)
                msgs.add(m)
                log(this, "Queued $kind SMS for ${m.toName} (contact $n)")
                // Stagger the messages so the phone/SIM does not drop the second one.
                handler.postDelayed({ if (running) sendMsg(m) }, idx * 2500L)
                idx++
            }
        }
        if (mode == "sms" || mode == "both") sendSms()
        if (mode != "sms") {
            val topic = Store.get(this, "dataTopic")
            if (topic.isBlank()) {
                log(this, "Data alert skipped: no alert code set")
                if (mode == "auto") sendSms()
            } else {
                val m = Msg(nextId++, "Data alert (internet)", topic, kind, text, true)
                if (mode == "auto") m.fallback = sendSms
                msgs.add(m)
                log(this, "Sending $kind data alert")
                sendData(m)
            }
        }
        saveState()
    }

    private fun sendData(m: Msg) {
        m.attempts++
        m.status = "sending"
        saveState()
        val test = testMode
        Thread {
            var ok = false
            try {
                val c = URL("https://ntfy.sh/" + m.to).openConnection() as HttpURLConnection
                c.requestMethod = "POST"
                c.doOutput = true
                c.connectTimeout = 10000
                c.readTimeout = 10000
                c.setRequestProperty("Title", if (test) "Panic Guard TEST" else "PANIC ALERT")
                c.setRequestProperty("Priority", "5")
                c.setRequestProperty("Tags", "rotating_light")
                c.outputStream.use { it.write(m.text.toByteArray(Charsets.UTF_8)) }
                ok = c.responseCode in 200..299
                c.disconnect()
            } catch (e: Exception) { }
            handler.post {
                m.status = if (ok) "sent" else "failed"
                log(this, if (ok) "Data alert SENT (${m.kind})" else "Data alert FAILED (try ${m.attempts}), will retry")
                if (!ok && running) {
                    val fb = m.fallback
                    if (fb != null) { m.fallback = null; log(this, "No internet: sending by SMS instead"); fb() }
                    scheduleRetry(m)
                }
                saveState()
                checkTestDone()
            }
        }.start()
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
                checkTestDone()
            }
        }
    }

    private fun scheduleRetry(m: Msg) {
        if (testMode) return
        if (m.attempts >= 40) { m.status = "gave up"; log(this, "Gave up sending to ${m.toName}"); return }
        val delays = longArrayOf(15, 30, 60, 120, 300)
        val d = delays[minOf(m.attempts - 1, delays.size - 1)] * 1000
        handler.postDelayed({
            if (running && m.status == "failed") {
                val newer = msgs.any { it.id > m.id && it.to == m.to && it.status == "sent" }
                if (m.kind == "update" && newer) { m.status = "superseded"; saveState() } else if (m.isData) sendData(m) else sendMsg(m)
            }
        }, d)
    }

    // ------------------------------------------------------------ recording (CameraX, no preview)

    private fun recDir() = File(filesDir, "recordings").apply { mkdirs() }

    private fun startRecording() {
        recActive = true
        val f = ProcessCameraProvider.getInstance(this)
        f.addListener({
            try {
                val p = f.get()
                provider = p
                var mode = Store.get(this, "camMode")
                if (mode.isBlank()) mode = if (Store.get(this, "camFront") == "false") "back" else "front"
                camMode = mode
                curFront = mode != "back"
                p.unbindAll()
                bindCam(p, curFront)
                startSegment()
            } catch (e: Exception) {
                recActive = false
                recNote = "Camera failed: ${e.message}"
                log(this, recNote)
                if (testMode) recDone = true
                saveState()
                checkTestDone()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private var camMode = "front"
    private var curFront = true

    private fun bindCam(p: ProcessCameraProvider, front: Boolean) {
        val rec = Recorder.Builder()
            .setQualitySelector(QualitySelector.from(Quality.SD, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)))
            .build()
        val vc = VideoCapture.withOutput(rec)
        var sel = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        if (!p.hasCamera(sel)) sel = if (front) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
        p.unbindAll()
        p.bindToLifecycle(this, sel, vc)
        videoCapture = vc
    }

    private fun swapCamera() {
        if (camMode != "both" || testMode) return
        try {
            val p = provider ?: return
            val next = !curFront
            bindCam(p, next)
            curFront = next
        } catch (e: Exception) { log(this, "Camera switch failed: ${e.message}") }
    }

    private val segRunnable = Runnable { recording?.stop() }

    private fun buzz() {
        try {
            val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= 26)
                v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 150, 100, 150, 100, 150), -1))
        } catch (e: Exception) { }
    }

    private fun startSegment() {
        if (!recActive || !running) return
        if (filesDir.usableSpace < 250L * 1024 * 1024) {
            recActive = false
            recNote = "Recording stopped: storage almost full"
            log(this, recNote)
            if (testMode) { recDone = true; checkTestDone() }
            buzz()
            saveState()
            return
        }
        val vc = videoCapture ?: return
        val file = File(recDir(), (if (testMode) "test_" else "panic_") + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4")
        try {
            var pr = vc.output.prepareRecording(this, FileOutputOptions.Builder(file).build())
            if (has(Manifest.permission.RECORD_AUDIO)) pr = pr.withAudioEnabled()
            recording = pr.start(ContextCompat.getMainExecutor(this)) { ev -> onVideoEvent(ev, file) }
            handler.postDelayed(segRunnable, if (testMode) 5_000L else 60_000L)
            if (!recNote.startsWith("Recording stopped")) recNote = ""
        } catch (e: Exception) {
            log(this, "Could not start segment: ${e.message}")
            camRetries++
            if (testMode) { recActive = false; recNote = "Camera failed in test"; recDone = true; checkTestDone() }
            else if (camRetries > 10) { recActive = false; recNote = "Recording stopped: camera error"; buzz() }
            else handler.postDelayed({ startSegment() }, 3000)
        }
        saveState()
    }

    private fun onVideoEvent(ev: VideoRecordEvent, file: File) {
        if (ev !is VideoRecordEvent.Finalize) return
        recording = null
        handler.removeCallbacks(segRunnable)
        val err = ev.hasError()
        if (err) {
            log(this, "Recording clip problem (code ${ev.error})")
            if (file.exists() && file.length() == 0L) file.delete()
        } else {
            recSegs++
            log(this, "Saved clip ${file.name}")
        }
        val cb = stopCb
        if (cb != null) { stopCb = null; cb(); return }
        if (testMode) { recDone = true; recActive = false; saveState(); checkTestDone(); return }
        if (recActive && running) {
            if (err) {
                camRetries++
                if (camRetries > 10) { recActive = false; recNote = "Recording stopped: camera error"; buzz() }
                else handler.postDelayed({ startSegment() }, 3000)
            } else { camRetries = 0; swapCamera(); startSegment() }
        }
        saveState()
    }

    private fun stopRecordingThen(cb: () -> Unit) {
        recActive = false
        val r = recording
        if (r == null) { cb(); return }
        var done = false
        val once: () -> Unit = { if (!done) { done = true; cb() } }
        stopCb = once
        try { r.stop() } catch (e: Exception) { once(); return }
        handler.postDelayed({ once() }, 4000)
    }

    // ------------------------------------------------------------ state for the UI

    private fun saveState(active: Boolean = true) {
        val o = JSONObject()
            .put("active", active)
            .put("startedAt", startedAt)
            .put("target", Store.get(this, "pTarget", "both"))
            .put("test", testMode)
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
        o.put("rec", JSONObject().put("on", recActive).put("segs", recSegs).put("note", recNote).put("allowed", recAllowed))
        Store.set(this, "pState", o.toString())
    }
}
