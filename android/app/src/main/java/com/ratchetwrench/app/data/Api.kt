// Api.kt — talks to YOUR Ratchet Wrench server (and nothing else).
//
// Connection rules (checked on EVERY request, not just at login):
//   * https:// is always allowed.
//   * http:// (unencrypted) is refused unless you switched on "Allow HTTP on
//     my home network" in Settings AND the address is a private/home one
//     (192.168.x.x, 10.x.x.x, 172.16-31.x.x, 100.64-127.x.x Tailscale,
//     localhost, *.local, *.lan, *.home.arpa).
//   * Requests may only go to the server address you configured.
//
// The login token is sent as "Authorization: Bearer <token>".
package com.ratchetwrench.app.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/** An error to show the user (message comes from the server when possible). */
class ApiException(val status: Int, message: String) : IOException(message)

val JsonFormat = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }

object UrlPolicy {
    /** Is this host a private/home-network address? (no DNS lookup) */
    fun isPrivateHost(host: String): Boolean {
        val h = host.lowercase().trim('[', ']')
        if (h == "localhost" || h.endsWith(".local") || h.endsWith(".lan") || h.endsWith(".home.arpa") || h.endsWith(".internal")) return true
        val parts = h.split(".").mapNotNull { it.toIntOrNull() }
        if (parts.size == 4 && h.count { it == '.' } == 3) {
            val (a, b) = parts[0] to parts[1]
            return a == 10 || a == 127 || (a == 192 && b == 168) || (a == 172 && b in 16..31) || (a == 100 && b in 64..127)
        }
        // IPv6 loopback / unique-local (fc00::/7) / link-local (fe80::/10)
        if (h.contains(':')) return try {
            val ip = InetAddress.getByName(h)
            ip.isLoopbackAddress || ip.isLinkLocalAddress || ip.isSiteLocalAddress || h.startsWith("fc") || h.startsWith("fd")
        } catch (e: Exception) { false }
        return false
    }

    /** Explain why a server address is not allowed, or null if it is fine. */
    fun problem(url: String, allowHttpLocal: Boolean): String? {
        val parsed = url.toHttpUrlOrNull() ?: return "Enter a full address starting with https://"
        if (parsed.scheme == "https") return null
        if (!allowHttpLocal) return "Plain http:// is blocked. Use https://, or turn on \"Allow HTTP on my home network\"."
        if (!isPrivateHost(parsed.host)) return "http:// is only allowed for home-network addresses. Use https:// for anything else."
        return null
    }
}

class Api(private val store: SecureStore) {
    private val json = JsonFormat

    /** Refuses any request that breaks the connection rules above. */
    private val guard = Interceptor { chain ->
        val req = chain.request()
        val base = store.serverUrl.toHttpUrlOrNull()
        val p = UrlPolicy.problem(req.url.toString(), store.allowHttpLocal)
        if (p != null) throw ApiException(0, p)
        if (base == null || req.url.host != base.host || req.url.port != base.port) throw ApiException(0, "Blocked request to an unknown server.")
        chain.proceed(req)
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(guard)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .followRedirects(false) // never follow a redirect to another site
        .build()

    private fun url(path: String): HttpUrl =
        (store.serverUrl + path).toHttpUrlOrNull() ?: throw ApiException(0, "The server address is not valid.")

    private fun request(path: String): Request.Builder {
        val b = Request.Builder().url(url(path)).header("Accept", "application/json")
        store.token?.let { b.header("Authorization", "Bearer $it") }
        return b
    }

    /** Run a request and return the response body text (throws on errors). */
    private fun execute(req: Request): String {
        client.newCall(req).execute().use { res ->
            val body = res.body.string()
            if (!res.isSuccessful) {
                val msg = try {
                    val el = json.parseToJsonElement(body).jsonObject["detail"]
                    el?.jsonPrimitive?.content ?: "Request failed (${res.code})."
                } catch (e: Exception) { "Request failed (${res.code})." }
                throw ApiException(res.code, msg)
            }
            return body
        }
    }

    fun get(path: String): String = execute(request(path).get().build())

    fun send(method: String, path: String, body: JsonElement): String =
        execute(request(path).method(method, body.toString().toRequestBody("application/json".toMediaType())).build())

    /** Download raw bytes (thumbnails, PDF report). */
    fun bytes(path: String): ByteArray {
        client.newCall(request(path).get().build()).execute().use { res ->
            if (!res.isSuccessful) throw ApiException(res.code, "Download failed (${res.code}).")
            return res.body.bytes()
        }
    }

    /** Upload one file as multipart/form-data. */
    fun upload(path: String, file: File, mime: String, fields: Map<String, String>): String {
        val form = MultipartBody.Builder().setType(MultipartBody.FORM)
        fields.forEach { (k, v) -> form.addFormDataPart(k, v) }
        form.addFormDataPart(if (path.endsWith("/scan")) "files" else "file", file.name, file.asRequestBody(mime.toMediaType()))
        return execute(request(path).post(form.build() as RequestBody).build())
    }

    // ---- Convenience calls ------------------------------------------------
    /** Sign in; returns the token. */
    fun login(username: String, password: String): String {
        val body = JsonObject(mapOf(
            "username" to kotlinx.serialization.json.JsonPrimitive(username),
            "password" to kotlinx.serialization.json.JsonPrimitive(password),
            "client" to kotlinx.serialization.json.JsonPrimitive("android"),
        ))
        val res = json.parseToJsonElement(send("POST", "/api/auth/login", body)).jsonObject
        return res["token"]?.jsonPrimitive?.content ?: throw ApiException(0, "The server did not return a login token.")
    }

    fun logout() { runCatching { send("POST", "/api/auth/logout", JsonObject(emptyMap())) } }

    fun me(): JsonObject = json.parseToJsonElement(get("/api/auth/me")).jsonObject
}
