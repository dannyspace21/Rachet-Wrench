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

    /** Notes for one version, or null. */
    fun forVersion(context: Context, version: String): Release? = all(context).firstOrNull { it.version == version }
}
