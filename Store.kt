package com.m9r1u5.panicguard

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** Keystore-backed encrypted storage shared by the UI plugin and (later) services. */
object Store {
    val KEYS = listOf(
        "setupDone", "c1Name", "c1Num", "c2Name", "c2Num",
        "message", "bioEnabled", "requireBoth",
        "defaultTarget", "intervalMin", "sendSafe", "volTriple", "camFront", "camMode", "alertMode", "dataTopic", "recording", "legalOk", "neutral"
    )

    @Volatile private var cached: SharedPreferences? = null

    fun prefs(ctx: Context): SharedPreferences {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val key = MasterKey.Builder(ctx.applicationContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            val p = EncryptedSharedPreferences.create(
                ctx.applicationContext, "pg_secure", key,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            cached = p
            return p
        }
    }

    fun get(ctx: Context, k: String, def: String = ""): String =
        prefs(ctx).getString(k, def) ?: def

    fun set(ctx: Context, k: String, v: String) {
        prefs(ctx).edit().putString(k, v).apply()
    }

    // ---------- PIN ----------
    private fun hash(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, 120_000, 256)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }

    fun hasPin(ctx: Context) = prefs(ctx).contains("pinHash")

    fun setPin(ctx: Context, pin: String): Boolean {
        if (!Regex("^\\d{4,8}$").matches(pin)) return false
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        prefs(ctx).edit()
            .putString("pinSalt", Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString("pinHash", Base64.encodeToString(hash(pin, salt), Base64.NO_WRAP))
            .putInt("failCount", 0)
            .putLong("lockUntil", 0L)
            .apply()
        return true
    }

    data class Verify(val ok: Boolean, val lockedSeconds: Int)

    /** After 5 wrong attempts: 30s lockout, doubling each further miss (max 15 min). */
    fun verifyPin(ctx: Context, pin: String): Verify {
        val p = prefs(ctx)
        val now = System.currentTimeMillis()
        val lockUntil = p.getLong("lockUntil", 0L)
        if (now < lockUntil) return Verify(false, ((lockUntil - now) / 1000 + 1).toInt())
        val saltS = p.getString("pinSalt", null)
        val hashS = p.getString("pinHash", null)
        if (saltS == null || hashS == null) return Verify(false, 0)
        val salt = Base64.decode(saltS, Base64.NO_WRAP)
        val expected = Base64.decode(hashS, Base64.NO_WRAP)
        val actual = hash(pin, salt)
        if (MessageDigest.isEqual(expected, actual)) {
            p.edit().putInt("failCount", 0).putLong("lockUntil", 0L).apply()
            return Verify(true, 0)
        }
        val fails = p.getInt("failCount", 0) + 1
        var lockSec = 0
        val e = p.edit().putInt("failCount", fails)
        if (fails >= 5) {
            lockSec = minOf(30L shl minOf(fails - 5, 5), 900L).toInt()
            e.putLong("lockUntil", now + lockSec * 1000L)
        }
        e.apply()
        return Verify(false, lockSec)
    }
}
