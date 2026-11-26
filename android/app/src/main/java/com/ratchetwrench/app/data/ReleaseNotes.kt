// ReleaseNotes.kt — "What's new" for each version, read from the notes file
// bundled in the app (assets/release_notes.json).  Works offline; nothing is
// downloaded.  The same file is used for the GitHub release page.
package com.ratchetwrench.app.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class Release(
    val version: String,
    val date: String = "",
    val new: List<String> = emptyList(),
    val improved: List<String> = emptyList(),
    val fixed: List<String> = emptyList(),
)

@Serializable
private data class ReleaseFile(val releases: List<Release>)

object ReleaseNotes {
    private val json = Json { ignoreUnknownKeys = true }
    @Volatile private var cached: List<Release>? = null

    /** Every release, newest first (empty if the file can't be read). */
    fun all(context: Context): List<Release> = cached ?: runCatching {
        json.decodeFromString(ReleaseFile.serializer(),
            context.assets.open("release_notes.json").bufferedReader().use { it.readText() }).releases
    }.getOrDefault(emptyList()).also { cached = it }

    /** The build number part of "1.0.N" (version codes are N × 1000). */
    fun patch(version: String): Int = version.substringAfterLast('.').toIntOrNull() ?: -1

    /**
     * Everything new since the version this phone last showed notes for:
     * this version plus any in between (skipped updates, or a version that
     * was never published), newest first.  seenCode 0 = only this version.
     */
    fun since(context: Context, version: String, seenCode: Int): List<Release> {
        val cur = patch(version)
        val seen = if (seenCode <= 0) cur - 1 else seenCode / 1000
        return all(context).filter { patch(it.version) in (seen + 1)..cur }
    }

    /** Notes for one version, or null. */
    fun forVersion(context: Context, version: String): Release? = all(context).firstOrNull { it.version == version }
}
