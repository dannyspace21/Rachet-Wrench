// Components.kt — small building blocks shared by the screens.
// Buttons and rows are at least 48dp tall so they're easy to tap.
package com.ratchetwrench.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ratchetwrench.app.R
import com.ratchetwrench.app.data.Api
import com.ratchetwrench.app.data.Attachment
import com.ratchetwrench.app.data.SecureStore
import com.ratchetwrench.app.domain.Catalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Currency

/** A maintenance/repair type icon on a coloured rounded square. */
@Composable
fun TypeBadge(typeKey: String, kind: String = "maintenance", size: Dp = 48.dp) {
    val ctx = LocalContext.current
    val x = LocalExtra.current
    val (bg, fg) = when (kind) {
        "repair" -> x.repairSoft to x.repair
        "neutral" -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurface
        else -> x.maintSoft to x.maint
    }
    Box(Modifier.size(size).clip(RoundedCornerShape(14.dp)).background(bg), contentAlignment = Alignment.Center) {
        Icon(painterResource(Catalog.iconRes(ctx, typeKey)), contentDescription = null, tint = fg, modifier = Modifier.size(size * 0.58f))
    }
}

@Composable
fun UiIcon(name: String, modifier: Modifier = Modifier.size(22.dp), tint: Color = MaterialTheme.colorScheme.onSurface,
           description: String? = null) {
    // Direct-reference table (see IconResources.kt); description = what a
    // screen reader says for icon-only buttons.
    val id = UI_ICON_RES[name] ?: return
    Icon(painterResource(id), contentDescription = description, modifier = modifier, tint = tint)
}

@Composable
fun Pill(text: String, bg: Color, fg: Color) {
    Text(text, color = fg, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(bg).padding(horizontal = 10.dp, vertical = 3.dp))
}

@Composable
fun StatusPill(status: String) {
    val x = LocalExtra.current
    when (status) {
        "overdue" -> Pill("Overdue", x.repairSoft, x.repair)
        "due_soon" -> Pill("Due soon", x.warnSoft, x.warn)
        "ok" -> Pill("OK", x.okSoft, x.ok)
        else -> Pill(if (status == "disabled") "Off" else "Set last done", MaterialTheme.colorScheme.surfaceVariant, x.muted)
    }
}

@Composable
fun KindPill(kind: String) {
    val x = LocalExtra.current
    if (kind == "repair") Pill("Repair", x.repairSoft, x.repair) else Pill("Maintenance", x.maintSoft, x.maint)
}

@Composable
fun SectionCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
    }
}

@Composable
fun PrimaryButton(text: String, icon: String? = null, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick, enabled = enabled, modifier = modifier.heightIn(min = 52.dp), shape = RoundedCornerShape(14.dp)) {
        if (icon != null) { UiIcon(icon, Modifier.size(20.dp), MaterialTheme.colorScheme.onPrimary); Spacer(Modifier.width(8.dp)) }
        Text(text, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun SecondaryButton(text: String, icon: String? = null, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedButton(onClick, enabled = enabled, modifier = modifier.heightIn(min = 52.dp), shape = RoundedCornerShape(14.dp)) {
        if (icon != null) { UiIcon(icon, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)) }
        Text(text, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** Labelled text field with a large touch area. */
@Composable
fun Field(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, numeric: Boolean = false,
          singleLine: Boolean = true, hint: String? = null, password: Boolean = false) {
    OutlinedTextField(value, onChange, modifier = modifier.fillMaxWidth(), label = { Text(label) }, singleLine = singleLine,
        minLines = if (singleLine) 1 else 3,
        supportingText = hint?.let { { Text(it) } },
        visualTransformation = if (password) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = when { numeric -> KeyboardType.Number; password -> KeyboardType.Password; else -> KeyboardType.Text }),
        shape = RoundedCornerShape(14.dp))
}

/** Two-to-four option toggle (Maintenance / Repair, Light / Dark / System …). */
@Composable
fun Segmented(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    Row(Modifier.clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for ((value, label) in options) {
            val on = value == selected
            Box(Modifier.clip(RoundedCornerShape(11.dp))
                .background(if (on) MaterialTheme.colorScheme.surface else Color.Transparent)
                .then(if (on) Modifier.border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(11.dp)) else Modifier)
                .heightIn(min = 44.dp)
                .clickable { onSelect(value) }
                .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center) {
                Text(label, fontWeight = FontWeight.SemiBold,
                    color = if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Photos: show a local (not yet uploaded) photo, or fetch the server's
// thumbnail with the login token.  Thumbnails are cached in memory.
// ---------------------------------------------------------------------------
private val thumbCache = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
    override fun sizeOf(key: String, value: Bitmap) = value.byteCount
}

@Composable
fun PhotoThumb(att: Attachment, size: Dp = 72.dp, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    var bmp by remember(att.id) { mutableStateOf(thumbCache.get(att.id)) }
    LaunchedEffect(att.id, att.has_file) {
        if (bmp != null) return@LaunchedEffect
        bmp = withContext(Dispatchers.IO) {
            try {
                val bytes = when {
                    att.localPath != null && java.io.File(att.localPath).exists() -> java.io.File(att.localPath).readBytes()
                    att.has_file -> Api(SecureStore(ctx)).bytes("/api/attachments/${att.id}/thumb")
                    else -> null
                } ?: return@withContext null
                val opts = BitmapFactory.Options().apply { inSampleSize = if (bytes.size > 600_000) 4 else 1 }
                com.ratchetwrench.app.camera.Photos.decodeUpright(bytes, opts.inSampleSize)?.also { thumbCache.put(att.id, it) }
            } catch (e: Exception) { null }
        }
    }
    Box(modifier.size(size).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        val b = bmp
        if (b != null) Image(b.asImageBitmap(), contentDescription = att.caption.ifBlank { "Photo" }, contentScale = ContentScale.Crop, modifier = Modifier.size(size))
        else UiIcon(if (att.content_type == "application/pdf") "report" else "image")
        if (!att.has_file) {
            // Small badge: waiting to upload.
            Box(Modifier.align(Alignment.BottomEnd).padding(4.dp).size(18.dp).clip(RoundedCornerShape(9.dp)).background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center) { UiIcon("upload", Modifier.size(12.dp), MaterialTheme.colorScheme.onPrimary) }
        }
    }
}

// ---------------------------------------------------------------------------
// Formatting
// ---------------------------------------------------------------------------
fun money(cents: Int?, currency: String): String {
    if (cents == null) return "—"
    val f = NumberFormat.getCurrencyInstance()
    runCatching { f.currency = Currency.getInstance(currency) }
    return f.format(cents / 100.0)
}

fun miles(value: Int?, unit: String) = if (value == null) "—" else "%,d %s".format(value, unit)

fun prettyDate(iso: String?): String = try {
    if (iso == null) "—" else LocalDate.parse(iso.take(10)).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
} catch (e: Exception) { iso ?: "—" }

/** "Nov 26, 2026, 2:15 PM" (local time) from an ISO timestamp. */
fun prettyDateTime(iso: String?): String = try {
    if (iso == null) "—" else java.time.Instant.parse(iso).atZone(java.time.ZoneId.systemDefault())
        .format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT))
} catch (e: Exception) { prettyDate(iso) }

fun parseMoney(text: String): Int? = text.filter { it.isDigit() || it == '.' }.toBigDecimalOrNull()?.movePointRight(2)?.toInt()

@Composable
fun AppLogo(size: Dp = 72.dp) {
    // The launcher foreground drawn on the brand-orange tile.
    Box(Modifier.size(size).clip(RoundedCornerShape(size * 0.22f)).background(Color(0xFFEA580C)), contentAlignment = Alignment.Center) {
        Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null, modifier = Modifier.size(size * 1.5f))
    }
}

/** Top bar used by every screen (implementation in screens/GarageScreen.kt). */
@Composable
fun TopBar(title: String, onBack: (() -> Unit)? = null, actions: @Composable () -> Unit = {}) =
    com.ratchetwrench.app.ui.screens.TopBarImpl(title, onBack, actions)
