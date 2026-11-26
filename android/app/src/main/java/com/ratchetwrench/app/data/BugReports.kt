// BugReports.kt — "Report a bug" from inside the app: the report is filed
// straight into the project's GitHub Issues (labels: bug, user-report).
//
// Only what the person typed, plus the technical details the form shows
// them (and they can switch off), is sent — and only when they press Send.
//
// TEMPORARY (owner's choice): the GitHub key ships in the release build
// (BuildConfig.ISSUE_TOKEN, from the RW_ISSUE_TOKEN secret; never in the
// source).  It can only create issues on this one repository.  It is meant
// to move to a relay service — see docs/BUG-REPORTS.md.
package com.ratchetwrench.app.data

import com.ratchetwrench.app.BuildConfig
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

object BugReports {
    /** Can reports be sent from this build? (Local test builds have no key.) */
    val enabled: Boolean get() = BuildConfig.ISSUE_TOKEN.isNotBlank()

    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()

    /** No @mentions (they'd notify GitHub users). */
    fun defuse(text: String) = text.replace("@", "@​").replace("<!--", "&lt;!--")

    fun body(whatHappened: String, steps: String, expected: String, details: String): String = buildString {
        appendLine("**Sent from:** Android app"); appendLine()
        appendLine("## What happened"); appendLine(defuse(whatHappened))
        if (steps.isNotBlank()) { appendLine(); appendLine("## Steps to reproduce"); appendLine(defuse(steps)) }
        if (expected.isNotBlank()) { appendLine(); appendLine("## What was expected"); appendLine(defuse(expected)) }
        if (details.isNotBlank()) { appendLine(); appendLine("## Details (added by the app)"); appendLine("```"); appendLine(details.replace("```", "'''")); appendLine("```") }
        appendLine(); append("_Reported from inside Ratchet Wrench._")
    }

    /** At most 1 a minute and 10 a day from this phone.  Returns a reason when not allowed. */
    fun tooSoon(times: List<Long>, now: Long = System.currentTimeMillis()): String? {
        val day = times.filter { now - it < 86_400_000L }
        return when {
            day.isNotEmpty() && now - day.max() < 60_000L -> "Please wait a minute before sending another report."
            day.size >= 10 -> "That's the limit of 10 reports a day from this phone. Thanks for all the help!"
            else -> null
        }
    }

    /** Files the issue; returns its number.  Call off the main thread.  Throws on failure. */
    fun send(summary: String, whatHappened: String, steps: String, expected: String, details: String): Int {
        val title = defuse("[Bug] " + summary.replace('\n', ' ').take(120))
        val json = JsonObject(mapOf("title" to JsonPrimitive(title),
            "body" to JsonPrimitive(body(whatHappened.ifBlank { summary }, steps, expected, details).take(60_000)),
            "labels" to JsonArray(listOf(JsonPrimitive("bug"), JsonPrimitive("user-report")))))
        val req = Request.Builder().url("https://api.github.com/repos/${BuildConfig.ISSUE_REPO}/issues")
            .header("Authorization", "Bearer ${BuildConfig.ISSUE_TOKEN}")
            .header("Accept", "application/vnd.github+json").header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "RatchetWrench-bug-report")
            .post(json.toString().toRequestBody("application/json".toMediaType())).build()
        client.newCall(req).execute().use { res ->
            if (!res.isSuccessful) throw ApiException(res.code, "GitHub didn't accept the report (${res.code}).")
            return JsonFormat.parseToJsonElement(res.body.string()).jsonObject["number"]!!.jsonPrimitive.int
        }
    }
}
