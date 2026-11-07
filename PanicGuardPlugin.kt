package com.m9r1u5.panicguard

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.ContactsContract
import android.provider.Settings
import androidx.activity.result.ActivityResult
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.FragmentActivity
import com.getcapacitor.JSObject
import com.getcapacitor.PermissionState
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.ActivityCallback
import com.getcapacitor.annotation.CapacitorPlugin
import com.getcapacitor.annotation.Permission
import com.getcapacitor.annotation.PermissionCallback
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

@CapacitorPlugin(
    name = "PanicGuard",
    permissions = [
        Permission(strings = [Manifest.permission.READ_CONTACTS], alias = "contacts"),
        Permission(strings = [Manifest.permission.SEND_SMS], alias = "sms"),
        Permission(
            strings = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION],
            alias = "location"
        ),
        Permission(strings = [Manifest.permission.ACCESS_BACKGROUND_LOCATION], alias = "bgLocation"),
        Permission(strings = [Manifest.permission.CAMERA], alias = "camera"),
        Permission(strings = [Manifest.permission.RECORD_AUDIO], alias = "microphone"),
        Permission(strings = [Manifest.permission.POST_NOTIFICATIONS], alias = "notifications")
    ]
)
class PanicGuardPlugin : Plugin() {

    private val aliases = listOf(
        "contacts", "sms", "location", "bgLocation", "camera", "microphone", "notifications"
    )

    // ================= Stage 1: app info, share, update =================

    @PluginMethod
    fun getAppInfo(call: PluginCall) {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val ret = JSObject()
        ret.put("versionName", info.versionName ?: "")
        @Suppress("DEPRECATION")
        ret.put("versionCode", info.versionCode)
        call.resolve(ret)
    }

    @PluginMethod
    fun shareText(call: PluginCall) {
        val text = call.getString("text") ?: return call.reject("No text")
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        val chooser = Intent.createChooser(send, call.getString("title") ?: "Share")
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
        call.resolve()
    }

    @PluginMethod
    fun canInstall(call: PluginCall) {
        val ret = JSObject()
        ret.put("allowed", context.packageManager.canRequestPackageInstalls())
        call.resolve(ret)
    }

    @PluginMethod
    fun openInstallSettings(call: PluginCall) {
        val i = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:" + context.packageName)
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(i)
        call.resolve()
    }

    @PluginMethod
    fun downloadAndInstall(call: PluginCall) {
        val url = call.getString("url") ?: return call.reject("No url")
        if (!context.packageManager.canRequestPackageInstalls()) {
            return call.reject("NEEDS_PERMISSION")
        }
        Thread {
            try {
                val dir = File(context.cacheDir, "update").apply { mkdirs() }
                val apk = File(dir, "update.apk")
                if (apk.exists()) apk.delete()
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = true
                conn.connectTimeout = 15000
                conn.readTimeout = 30000
                conn.connect()
                if (conn.responseCode !in 200..299) {
                    call.reject("HTTP " + conn.responseCode)
                    return@Thread
                }
                val total = conn.contentLengthLong
                var done = 0L
                var lastPct = -1
                conn.inputStream.use { input ->
                    apk.outputStream().use { out ->
                        val buf = ByteArray(32 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (total > 0) {
                                val pct = (done * 100 / total).toInt()
                                if (pct != lastPct) {
                                    lastPct = pct
                                    val p = JSObject()
                                    p.put("percent", pct)
                                    notifyListeners("downloadProgress", p)
                                }
                            }
                        }
                    }
                }
                val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", apk)
                val install = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(install)
                call.resolve()
            } catch (e: Exception) {
                call.reject(e.message ?: "Download failed")
            }
        }.start()
    }

    // ================= Stage 2: permissions =================

    private fun stateOf(alias: String): String {
        if (alias == "notifications" && Build.VERSION.SDK_INT < 33) return "granted"
        if (alias == "bgLocation" && Build.VERSION.SDK_INT < 29) return "granted"
        return getPermissionState(alias).toString()
    }

    private fun batteryOk(): Boolean {
        val pm = context.getSystemService(android.content.Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    @PluginMethod
    fun getPermStates(call: PluginCall) {
        val ret = JSObject()
        for (a in aliases) ret.put(a, stateOf(a))
        ret.put("battery", if (batteryOk()) "granted" else "denied")
        call.resolve(ret)
    }

    @PluginMethod
    fun requestPerm(call: PluginCall) {
        val alias = call.getString("alias") ?: return call.reject("No alias")
        if (alias !in aliases) return call.reject("Unknown permission")
        if (stateOf(alias) == "granted") {
            val r = JSObject(); r.put("state", "granted"); call.resolve(r); return
        }
        requestPermissionForAlias(alias, call, "permResult")
    }

    @PermissionCallback
    private fun permResult(call: PluginCall) {
        val alias = call.getString("alias") ?: ""
        val r = JSObject()
        r.put("state", stateOf(alias))
        call.resolve(r)
    }

    @PluginMethod
    fun openBatterySettings(call: PluginCall) {
        val i = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:" + context.packageName)
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(i)
        } catch (e: Exception) {
            context.startActivity(
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
        call.resolve()
    }

    @PluginMethod
    fun listenerEnabled(call: PluginCall) {
        val on = androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
        call.resolve(JSObject().put("enabled", on))
    }

    @PluginMethod
    fun openListenerSettings(call: PluginCall) {
        val i = Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { context.startActivity(i) } catch (e: Exception) {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + context.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        call.resolve()
    }

    @PluginMethod
    fun openAppSettings(call: PluginCall) {
        val i = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:" + context.packageName)
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(i)
        call.resolve()
    }

    // ================= Stage 2: contacts =================

    @PluginMethod
    fun pickContact(call: PluginCall) {
        val i = Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)
        startActivityForResult(call, i, "pickResult")
    }

    @ActivityCallback
    private fun pickResult(call: PluginCall?, result: ActivityResult) {
        if (call == null) return
        val uri = result.data?.data
        if (result.resultCode != Activity.RESULT_OK || uri == null) {
            call.reject("cancelled"); return
        }
        val cols = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        context.contentResolver.query(uri, cols, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val r = JSObject()
                r.put("name", c.getString(0) ?: "")
                r.put("number", (c.getString(1) ?: "").replace(Regex("[^+0-9]"), ""))
                call.resolve(r)
                return
            }
        }
        call.reject("No number found")
    }

    // ================= Stage 2: encrypted settings + PIN =================

    @PluginMethod
    fun getValues(call: PluginCall) {
        val r = JSObject()
        for (k in Store.KEYS) r.put(k, Store.get(context, k))
        r.put("hasPin", Store.hasPin(context))
        call.resolve(r)
    }

    @PluginMethod
    fun setValues(call: PluginCall) {
        val obj = call.getObject("values") ?: return call.reject("No values")
        val it = obj.keys()
        while (it.hasNext()) {
            val k = it.next()
            if (k in Store.KEYS) Store.set(context, k, obj.optString(k, ""))
        }
        call.resolve()
    }

    @PluginMethod
    fun setPin(call: PluginCall) {
        val pin = call.getString("pin") ?: return call.reject("No PIN")
        if (Store.setPin(context, pin)) call.resolve() else call.reject("PIN must be 4-8 digits")
    }

    @PluginMethod
    fun verifyPin(call: PluginCall) {
        val pin = call.getString("pin") ?: ""
        val v = Store.verifyPin(context, pin)
        val r = JSObject()
        r.put("ok", v.ok)
        r.put("lockedSeconds", v.lockedSeconds)
        call.resolve(r)
    }

    // ================= Stage 2: biometrics =================

    @PluginMethod
    fun biometricStatus(call: PluginCall) {
        val s = when (BiometricManager.from(context).canAuthenticate(BIOMETRIC_STRONG)) {
            BiometricManager.BIOMETRIC_SUCCESS -> "available"
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> "none_enrolled"
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> "no_hardware"
            else -> "unavailable"
        }
        val r = JSObject(); r.put("status", s); call.resolve(r)
    }

    @PluginMethod
    fun openBiometricEnroll(call: PluginCall) {
        val i = if (Build.VERSION.SDK_INT >= 30) {
            Intent(Settings.ACTION_BIOMETRIC_ENROLL).putExtra(
                Settings.EXTRA_BIOMETRIC_AUTHENTICATORS_ALLOWED, BIOMETRIC_STRONG
            )
        } else Intent(Settings.ACTION_SECURITY_SETTINGS)
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(i)
        call.resolve()
    }

    @PluginMethod
    fun biometricAuth(call: PluginCall) {
        val act = activity as? FragmentActivity ?: return call.reject("No activity")
        act.runOnUiThread {
            val cb = object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    val r = JSObject(); r.put("success", true); call.resolve(r)
                }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    call.reject(errString.toString(), errorCode.toString())
                }
            }
            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle(call.getString("title") ?: "Unlock Panic Guard")
                .setNegativeButtonText("Use PIN")
                .setAllowedAuthenticators(BIOMETRIC_STRONG)
                .build()
            BiometricPrompt(act, ContextCompat.getMainExecutor(act), cb).authenticate(info)
        }
    }

    // ================= Stage 3: panic =================

    @PluginMethod
    fun startPanic(call: PluginCall) {
        if (Store.get(context, "alertMode", "sms") != "data" && stateOf("sms") != "granted") return call.reject("SMS permission is off")
        val t = call.getString("target") ?: "both"
        try {
            val i = Intent(context, PanicService::class.java)
                .setAction(PanicService.ACTION_START).putExtra("target", t)
            ContextCompat.startForegroundService(context, i)
            call.resolve()
        } catch (e: Exception) {
            call.reject(e.message ?: "Could not start")
        }
    }

    @PluginMethod
    fun stopPanic(call: PluginCall) {
        try {
            val i = Intent(context, PanicService::class.java)
                .setAction(PanicService.ACTION_STOP).putExtra("safe", call.getBoolean("safe") ?: false)
            context.startService(i)
        } catch (e: Exception) {
            Store.set(context, "pActive", "false")
        }
        call.resolve()
    }

    @PluginMethod
    fun getPanicState(call: PluginCall) {
        val r = JSObject(); r.put("json", Store.get(context, "pState", "{}")); call.resolve(r)
    }

    @PluginMethod
    fun getLog(call: PluginCall) {
        val r = JSObject(); r.put("json", Store.get(context, "pLog", "[]")); call.resolve(r)
    }

    @PluginMethod
    fun clearLog(call: PluginCall) {
        Store.set(context, "pLog", "[]"); call.resolve()
    }

    @PluginMethod
    fun listRecordings(call: PluginCall) {
        val dir = java.io.File(context.filesDir, "recordings")
        val arr = org.json.JSONArray()
        var total = 0L
        (dir.listFiles() ?: emptyArray()).sortedBy { it.name }.forEach {
            total += it.length()
            arr.put(org.json.JSONObject().put("name", it.name).put("size", it.length()).put("t", it.lastModified()))
        }
        val r = JSObject()
        r.put("json", arr.toString()); r.put("total", total); r.put("free", context.filesDir.usableSpace)
        call.resolve(r)
    }

    @PluginMethod
    fun vibrate(call: PluginCall) {
        val ms = (call.getInt("ms") ?: 200).toLong()
        try {
            @Suppress("DEPRECATION")
            val v: Vibrator = if (Build.VERSION.SDK_INT >= 31)
                (context.getSystemService(android.content.Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            else context.getSystemService(android.content.Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
            else v.vibrate(ms)
        } catch (e: Exception) { }
        call.resolve()
    }

    // ================= Stage 5: test mode + recordings =================

    @PluginMethod
    fun startTest(call: PluginCall) {
        if (Store.get(context, "alertMode", "sms") != "data" && stateOf("sms") != "granted") return call.reject("SMS permission is off")
        val t = call.getString("target") ?: "both"
        try {
            val i = Intent(context, PanicService::class.java)
                .setAction(PanicService.ACTION_TEST).putExtra("target", t)
            ContextCompat.startForegroundService(context, i)
            call.resolve()
        } catch (e: Exception) {
            call.reject(e.message ?: "Could not start test")
        }
    }

    @PluginMethod
    fun getTestResult(call: PluginCall) {
        val r = JSObject(); r.put("json", Store.get(context, "pTest", "{}")); call.resolve(r)
    }

    private fun recFile(name: String): java.io.File? {
        val dir = java.io.File(context.filesDir, "recordings")
        if (name.contains('/') || name.contains('\\')) return null
        val f = java.io.File(dir, name)
        return if (f.exists() && f.canonicalPath.startsWith(dir.canonicalPath + "/")) f else null
    }

    private fun clipUri(f: java.io.File) =
        FileProvider.getUriForFile(context, context.packageName + ".fileprovider", f)

    @PluginMethod
    fun playRecording(call: PluginCall) {
        val f = recFile(call.getString("name") ?: "") ?: return call.reject("Clip not found")
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(clipUri(f), "video/mp4")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try { context.startActivity(i); call.resolve() } catch (e: Exception) { call.reject("No video player found") }
    }

    @PluginMethod
    fun shareRecording(call: PluginCall) {
        val f = recFile(call.getString("name") ?: "") ?: return call.reject("Clip not found")
        val uri = clipUri(f)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = android.content.ClipData.newRawUri("", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, "Share clip").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { context.startActivity(chooser); call.resolve() } catch (e: Exception) { call.reject("Could not share") }
    }

    @PluginMethod
    fun exportRecording(call: PluginCall) {
        val f = recFile(call.getString("name") ?: "") ?: return call.reject("Clip not found")
        if (Build.VERSION.SDK_INT < 29) return call.reject("Export needs Android 10 or newer")
        val downloads = call.getString("dest") == "downloads"
        try {
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, f.name)
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                put(
                    android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                    (if (downloads) android.os.Environment.DIRECTORY_DOWNLOADS else android.os.Environment.DIRECTORY_MOVIES) + "/PanicGuard"
                )
                put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val collection = if (downloads) android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI
                else android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            val resolver = context.contentResolver
            val uri = resolver.insert(collection, values) ?: return call.reject("Could not create file")
            resolver.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) } }
            val done = android.content.ContentValues().apply { put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
            val r = JSObject()
            r.put("path", (if (downloads) "Downloads" else "Movies") + "/PanicGuard/" + f.name)
            call.resolve(r)
        } catch (e: Exception) {
            call.reject(e.message ?: "Export failed")
        }
    }

    @PluginMethod
    fun deleteRecording(call: PluginCall) {
        val f = recFile(call.getString("name") ?: "") ?: return call.reject("Clip not found")
        if (f.delete()) call.resolve() else call.reject("Could not delete")
    }

    @PluginMethod
    fun deleteAllRecordings(call: PluginCall) {
        (java.io.File(context.filesDir, "recordings").listFiles() ?: emptyArray()).forEach { it.delete() }
        call.resolve()
    }
}
