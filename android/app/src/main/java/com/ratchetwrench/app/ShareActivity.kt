// ShareActivity.kt — "Share → Ratchet Wrench" for text.
//
// Web owner's manuals like Audi's only open in a browser (they build their
// pages with JavaScript and block automatic downloads).  On the phone you
// select the Technical data text there and share it to Ratchet Wrench; this
// screen-less activity takes ONLY that plain text (no files, no links are
// opened), keeps it in memory and opens the app's "which vehicle?" screen.
// The text then goes to the owner's-manual reader, where every value is
// reviewed before anything is saved.
package com.ratchetwrench.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.compose.runtime.mutableStateOf

/** Text shared into the app, waiting to be read as owner's-manual text. */
object SharedManualText {
    const val MAX_CHARS = 150_000
    val pending = mutableStateOf<String?>(null)

    /** Plain text only: control characters removed, length capped. */
    fun clean(raw: String?): String? =
        raw?.filter { it == '\n' || it == '\t' || !it.isISOControl() }?.take(MAX_CHARS)?.takeIf { it.isNotBlank() }

    fun take(): String? = pending.value.also { pending.value = null }
}

class ShareActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = if (intent?.action == Intent.ACTION_SEND && intent.type?.startsWith("text/") == true)
            SharedManualText.clean(intent.getStringExtra(Intent.EXTRA_TEXT)) else null
        if (text != null) {
            SharedManualText.pending.value = text
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
        finish()
    }
}
