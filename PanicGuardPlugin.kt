package com.m9r1u5.panicguard

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

@CapacitorPlugin(name = "PanicGuard")
class PanicGuardPlugin : Plugin() {

    @PluginMethod
    fun getAppInfo(call: PluginCall) {
        val pm = context.packageManager
        val info = pm.getPackageInfo(context.packageName, 0)
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
                val uri = FileProvider.getUriForFile(
                    context, context.packageName + ".fileprovider", apk
                )
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
}
