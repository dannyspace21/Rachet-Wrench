// SecureStore.kt — where the app keeps its settings and your login token.
//
// The login token is ENCRYPTED before it is written to storage:
//   * an AES-256 key is created inside the Android Keystore (on most phones a
//     hardware security chip).  The key can never be read out of it — not
//     even by this app — it can only be asked to encrypt/decrypt.
//   * the token is encrypted with AES-GCM (which also detects tampering).
// So copying the app's files off the phone does not reveal the token.
// App data is also excluded from Android backups (see AndroidManifest.xml).
package com.ratchetwrench.app.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecureStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("rw_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_ALIAS = "ratchet_wrench_token_key"
        private const val TRANSFORM = "AES/GCM/NoPadding"

        /** Tests only: the build machine has no Android Keystore, so a
         *  test can hold the token here instead.  Never set by the app. */
        @androidx.annotation.VisibleForTesting
        @Volatile internal var tokenForTests: String? = null
    }

    /** Get (or create on first use) the Keystore key. */
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    /** The login token (decrypted), or null when signed out. */
    var token: String?
        get() {
            tokenForTests?.let { return it }
            val stored = prefs.getString("token_enc", null) ?: return null
            return try {
                val bytes = Base64.decode(stored, Base64.NO_WRAP)
                val iv = bytes.copyOfRange(0, 12)
                val cipher = Cipher.getInstance(TRANSFORM)
                cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
                String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
            } catch (e: Exception) {
                null // key lost or data tampered with: treat as signed out
            }
        }
        set(value) {
            if (tokenForTests != null) { tokenForTests = value; return }
            if (value == null) {
                prefs.edit().remove("token_enc").apply()
                return
            }
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val out = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            prefs.edit().putString("token_enc", Base64.encodeToString(out, Base64.NO_WRAP)).apply()
        }

    /**
     * How the app is used:
     *   ""        not chosen yet (shows the welcome screen)
     *   "local"   phone only — no server, nothing leaves the phone
     *   "server"  connected to the desktop app or a home server (syncs)
     */
    var mode: String
        get() = prefs.getString("mode", "") ?: ""
        set(v) = prefs.edit().putString("mode", v).apply()

    /** Server address, e.g. https://garage.tailnet-name.ts.net */
    var serverUrl: String
        get() = prefs.getString("server_url", "") ?: ""
        set(v) = prefs.edit().putString("server_url", v.trim().trimEnd('/')).apply()

    var username: String
        get() = prefs.getString("username", "") ?: ""
        set(v) = prefs.edit().putString("username", v).apply()

    /** Allow plain http:// — ONLY to a home-network address.  Off by default. */
    var allowHttpLocal: Boolean
        get() = prefs.getBoolean("allow_http_local", false)
        set(v) = prefs.edit().putBoolean("allow_http_local", v).apply()

    /** Light / Dark / System */
    var theme: String
        get() = prefs.getString("theme", "system") ?: "system"
        set(v) = prefs.edit().putString("theme", v).apply()

    var distanceUnit: String
        get() = prefs.getString("distance_unit", "mi") ?: "mi"
        set(v) = prefs.edit().putString("distance_unit", v).apply()

    var currency: String
        get() = prefs.getString("currency", "USD") ?: "USD"
        set(v) = prefs.edit().putString("currency", v).apply()

    var notificationsEnabled: Boolean
        get() = prefs.getBoolean("notify", true)
        set(v) = prefs.edit().putBoolean("notify", v).apply()

    /** Can the server do AI vision for stickers? (from /api/auth/me) */
    var serverAiVision: Boolean
        get() = prefs.getBoolean("server_ai", false)
        set(v) = prefs.edit().putBoolean("server_ai", v).apply()

    /** Sync bookmark: the server's next_since from the last pull. */
    var lastSince: String?
        get() = prefs.getString("last_since", null)
        set(v) = prefs.edit().putString("last_since", v).apply()

    /** The app version whose "What's new" notes were last shown (versionCode). */
    var notesSeenCode: Int
        get() = prefs.getInt("notes_seen", 0)
        set(v) = prefs.edit().putInt("notes_seen", v).apply()

    /** Why the last sync failed (plain English), or null after a good sync. */
    var lastSyncError: String?
        get() = prefs.getString("last_sync_error", null)
        set(v) = prefs.edit().putString("last_sync_error", v).apply()

    var lastSyncOk: String?
        get() = prefs.getString("last_sync_ok", null)
        set(v) = prefs.edit().putString("last_sync_ok", v).apply()

    /** When we last notified about each reminder (so we don't repeat). */
    fun lastNotified(reminderId: String): Long = prefs.getLong("notified_$reminderId", 0)
    fun setLastNotified(reminderId: String, whenMs: Long) = prefs.edit().putLong("notified_$reminderId", whenMs).apply()

    /** Forget everything except the server address and theme (data is wiped separately). */
    fun signOut() {
        val keep = serverUrl to theme
        val seen = notesSeenCode
        prefs.edit().clear().apply()
        serverUrl = keep.first
        theme = keep.second
        notesSeenCode = seen // don't show "What's new" again after signing out
    }

    /** Stop syncing but keep using the app on this phone with the same data. */
    fun disconnectKeepData() {
        token = null
        lastSince = null
        serverAiVision = false
        mode = "local"
    }
}
