// Updater.kt — the app updates ITSELF from your GitHub Releases page.
//
// What happens (automatically when the app opens, at most every 6 hours, or
// when you tap "Check for updates" in Settings):
//   1. Ask GitHub for the latest release (api.github.com).  The release tag
//      "v1.0.N" means version code N x 1000; if that's not newer than this
//      app, stop.
//   2. Download the APK and the release's SHA256SUMS.txt (github.com, which
//      redirects to GitHub's download servers).  On mobile data this waits
//      for your tap; on Wi-Fi it downloads straight away.
//   3. SAFETY CHECKS — the update is thrown away unless ALL pass:
//        * its SHA-256 matches the one published in SHA256SUMS.txt
//        * it is the same app (package name com.ratchetwrench.app)
//        * its version is newer than the installed one
//        * it is signed with the SAME certificate as this app (your
//          permanent key) — so nobody else can push an "update"
//   4. Hand it to Android's installer (PackageInstaller).  Android shows its
//      "Update this app?" prompt (from Android 12, later updates can install
//      without the prompt once the app has updated itself once).
//
// Network: OUTBOUND CONNECTION #4 (documented in SECURITY.md).  Only GitHub
// hosts are allowed (checked on every redirect), only https, and nothing
// about you or your data is sent — just the standard download requests.
// It can be switched off in Settings.
package com.ratchetwrench.app.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Build
import com.ratchetwrench.app.BuildConfig
import com.ratchetwrench.app.data.JsonFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** What the updater is doing (shown as a banner on the Garage screen). */
sealed class UpdateState {
    data object Idle : UpdateState()
    data class Available(val version: String) : UpdateState()      // waiting for Wi-Fi or a tap
    data class Downloading(val version: String, val percent: Int) : UpdateState()
    data class Installing(val version: String) : UpdateState()
    data class Failed(val message: String) : UpdateState()
    data object NeedsPermission : UpdateState()                     // "Install unknown apps" is off
    data object UpToDate : UpdateState()
}

object Updater {
    private const val REPO = "dannyspace21/Rachet-Wrench"
    private const val LATEST = "https://api.github.com/repos/$REPO/releases/latest"
    private val ALLOWED_HOSTS = setOf(
        "api.github.com", "github.com", "objects.githubusercontent.com", "release-assets.githubusercontent.com",
    )
    private const val CHECK_EVERY_MS = 6L * 60 * 60 * 1000

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state

    /** Only GitHub hosts, only https — checked again on every redirect hop. */
    private val githubOnly = Interceptor { chain ->
        val url = chain.request().url
        if (!url.isHttps || url.host !in ALLOWED_HOSTS) throw IOException("Blocked update download from ${url.host}")
        chain.proceed(chain.request())
    }

    private val client = OkHttpClient.Builder()
        .addNetworkInterceptor(githubOnly)
        .followRedirects(true)
        .followSslRedirects(false) // never https -> http
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private data class Release(val version: String, val code: Long, val apkUrl: String, val apkName: String, val sumsUrl: String)

    fun currentVersion(): String = BuildConfig.VERSION_NAME

    private fun prefs(context: Context) = context.getSharedPreferences("rw_updates", Context.MODE_PRIVATE)

    fun autoEnabled(context: Context) = prefs(context).getBoolean("auto", true)
    fun setAutoEnabled(context: Context, on: Boolean) = prefs(context).edit().putBoolean("auto", on).apply()

    /**
     * Called when the app opens.  Checks at most every 6 hours, downloads on
     * Wi-Fi straight away, and installs.  Never throws.
     */
    suspend fun autoCheck(context: Context) {
        if (!autoEnabled(context) || BuildConfig.DEBUG) return
        val last = prefs(context).getLong("last_check", 0)
        if (System.currentTimeMillis() - last < CHECK_EVERY_MS) return
        check(context, userAsked = false)
    }

    /** Check now.  userAsked = true downloads even on mobile data. */
    suspend fun check(context: Context, userAsked: Boolean) {
        try {
            val release = withContext(Dispatchers.IO) { latestRelease() }
            prefs(context).edit().putLong("last_check", System.currentTimeMillis()).apply()
            if (release == null || release.code <= BuildConfig.VERSION_CODE.toLong()) {
                _state.value = if (userAsked) UpdateState.UpToDate else UpdateState.Idle
                return
            }
            val metered = context.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered ?: true
            if (metered && !userAsked) {
                _state.value = UpdateState.Available(release.version)
                return
            }
            downloadAndInstall(context, release)
        } catch (e: Exception) {
            // Automatic checks fail quietly (e.g. offline); a tap shows why.
            _state.value = if (userAsked) UpdateState.Failed(e.message ?: "Update check failed.") else UpdateState.Idle
        }
    }

    /** Read the latest release from GitHub. */
    private fun latestRelease(): Release? {
        val req = Request.Builder().url(LATEST).header("Accept", "application/vnd.github+json").build()
        val body = client.newCall(req).execute().use { res ->
            if (!res.isSuccessful) throw IOException("GitHub answered ${res.code}.")
            res.body.string().take(2_000_000)
        }
        val json = JsonFormat.parseToJsonElement(body).jsonObject
        val tag = json["tag_name"]?.jsonPrimitive?.content ?: return null
        val n = Regex("""^v1\.0\.(\d+)$""").find(tag)?.groupValues?.get(1)?.toLongOrNull() ?: return null
        val assets = json["assets"]?.jsonArray?.map { it.jsonObject } ?: return null
        fun asset(match: (String) -> Boolean) = assets.firstOrNull { match(it["name"]!!.jsonPrimitive.content) }
        val apk = asset { it.startsWith("ratchet-wrench-") && it.endsWith(".apk") } ?: return null
        val sums = asset { it == "SHA256SUMS.txt" } ?: return null
        return Release(
            version = tag.removePrefix("v"), code = n * 1000,
            apkUrl = apk["browser_download_url"]!!.jsonPrimitive.content, apkName = apk["name"]!!.jsonPrimitive.content,
            sumsUrl = sums["browser_download_url"]!!.jsonPrimitive.content,
        )
    }

    /** Download (or tap-to-download from the banner) and install. */
    suspend fun downloadAndInstall(context: Context) {
        try {
            val release = withContext(Dispatchers.IO) { latestRelease() } ?: return
            if (release.code > BuildConfig.VERSION_CODE.toLong()) downloadAndInstall(context, release)
        } catch (e: Exception) {
            _state.value = UpdateState.Failed(e.message ?: "Update failed.")
        }
    }

    private suspend fun downloadAndInstall(context: Context, release: Release) {
        // Android must allow this app to install updates ("Install unknown apps").
        if (!context.packageManager.canRequestPackageInstalls()) {
            _state.value = UpdateState.NeedsPermission
            return
        }
        _state.value = UpdateState.Downloading(release.version, 0)
        val apk = withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "updates").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
            val expected = fetchText(release.sumsUrl).lines()
                .firstOrNull { it.trim().endsWith(release.apkName) }?.trim()?.split(Regex("\\s+"))?.firstOrNull()?.lowercase()
                ?: throw IOException("The release has no checksum for the APK.")
            val file = File(dir, "update.apk")
            val digest = MessageDigest.getInstance("SHA-256")
            client.newCall(Request.Builder().url(release.apkUrl).build()).execute().use { res ->
                if (!res.isSuccessful) throw IOException("Download failed (${res.code}).")
                val total = res.body.contentLength().takeIf { it > 0 } ?: 70_000_000L
                if (total > 300_000_000L) throw IOException("The update file is unexpectedly large.")
                var done = 0L
                res.body.byteStream().use { input ->
                    file.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n); digest.update(buf, 0, n); done += n
                            if (done > 300_000_000L) throw IOException("The update file is unexpectedly large.")
                            _state.value = UpdateState.Downloading(release.version, ((done * 100) / total).toInt().coerceIn(0, 99))
                        }
                    }
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (actual != expected) { file.delete(); throw IOException("The download didn't match its published checksum, so it was discarded.") }
            verifySameAppAndSigner(context, file)
            file
        }
        _state.value = UpdateState.Installing(release.version)
        withContext(Dispatchers.IO) { install(context, apk) }
    }

    private fun fetchText(url: String): String =
        client.newCall(Request.Builder().url(url).build()).execute().use { res ->
            if (!res.isSuccessful) throw IOException("Download failed (${res.code}).")
            res.body.string().take(100_000)
        }

    /** Same package, newer version, signed with exactly the same certificate. */
    private fun verifySameAppAndSigner(context: Context, apk: File) {
        val pm = context.packageManager
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val mine = pm.getPackageInfo(context.packageName, flags)
        val update = pm.getPackageArchiveInfo(apk.path, flags) ?: throw IOException("The downloaded update isn't a valid app.")
        if (update.packageName != context.packageName) throw IOException("The download is a different app; discarded.")
        if (update.longVersionCode <= mine.longVersionCode) throw IOException("The download isn't newer; discarded.")
        if (certs(update) != certs(mine) || certs(mine).isEmpty()) {
            apk.delete()
            throw IOException("The update is not signed with this app's key, so it was discarded.")
        }
    }

    private fun certs(info: PackageInfo): Set<String> {
        val signing = info.signingInfo ?: return emptySet()
        val sigs = if (signing.hasMultipleSigners()) signing.apkContentsSigners else signing.signingCertificateHistory
        return sigs.orEmpty().map { s -> MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) } }.toSet()
    }

    /** Give the APK to Android's installer.  Android shows "Update this app?"
     *  unless it allows the app to update itself without asking. */
    private fun install(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("update.apk", 0, apk.length()).use { out ->
                    input.copyTo(out)
                    session.fsync(out)
                }
            }
            val intent = Intent(context, InstallResultReceiver::class.java)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE // the installer adds the result
            session.commit(PendingIntent.getBroadcast(context, sessionId, intent, flags).intentSender)
        }
    }

    internal fun report(state: UpdateState) { _state.value = state }
}
