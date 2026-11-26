// ReleaseNotesTest.kt — the bundled release notes load, and the "What's new"
// pop-up appears once after an update and then never again for that version.
package com.ratchetwrench.app

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.ratchetwrench.app.data.ReleaseNotes
import com.ratchetwrench.app.data.Repository
import com.ratchetwrench.app.ui.LocalRepo
import com.ratchetwrench.app.ui.WhatsNewDialog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = TestApp::class)
class ReleaseNotesTest {
    @get:Rule val rule = createComposeRule()
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun notesFileIsValid() {
        val all = ReleaseNotes.all(ctx)
        assertTrue("notes load", all.isNotEmpty())
        val versions = all.map { it.version }
        assertEquals("no version twice", versions.size, versions.toSet().size)
        val numbers = versions.map { v -> v.split('.').map { it.toInt() } }
        val sorted = numbers.sortedWith(compareByDescending<List<Int>> { it[0] }.thenByDescending { it[1] }.thenByDescending { it[2] })
        assertEquals("newest first", sorted, numbers)
        all.forEach { assertTrue("${it.version} has notes", it.new.isNotEmpty() || it.improved.isNotEmpty() || it.fixed.isNotEmpty()) }
    }

    @Test fun whatsNewShowsOnceAfterAnUpdate() {
        val repo = Repository(ctx)
        val top = ReleaseNotes.all(ctx).first()
        val code = top.version.substringAfterLast('.').toInt() * 1000
        repo.store.notesSeenCode = code - 1000 // the previous version was installed
        rule.setContent { CompositionLocalProvider(LocalRepo provides repo) { WhatsNewDialog(top.version, code) } }
        rule.onNodeWithText("What's new in ${top.version}").assertExists()
        rule.onNodeWithText("Got it").performClick()
        rule.waitForIdle()
        assertEquals(0, rule.onAllNodesWithText("What's new in ${top.version}").fetchSemanticsNodes().size)
        assertEquals("remembered", code, repo.store.notesSeenCode)
    }

    /** Skipped updates (or a version never published): their notes show too. */
    @Test fun whatsNewIncludesSkippedVersions() {
        assertEquals(listOf("1.0.26", "1.0.25", "1.0.24"), ReleaseNotes.since(ctx, "1.0.26", 23000).map { it.version })
        assertEquals(listOf("1.0.26"), ReleaseNotes.since(ctx, "1.0.26", 25000).map { it.version })
        assertEquals("fresh install: just this one", listOf("1.0.26"), ReleaseNotes.since(ctx, "1.0.26", 0).map { it.version })
        val repo = Repository(ctx)
        repo.store.notesSeenCode = 23000 // had 1.0.23, updates straight to 1.0.25
        rule.setContent { CompositionLocalProvider(LocalRepo provides repo) { WhatsNewDialog("1.0.25", 25000) } }
        rule.onNodeWithText("What's new in 1.0.25").assertExists()
        rule.onNodeWithText("Also new (from 1.0.24)").assertExists()
    }
}
