// LocalReport.kt — builds the "Vehicle History Report" PDF ON THE PHONE
// (phone-only mode; when connected, the computer/server builds it).
//
// Uses Android's built-in PdfDocument (no extra library).  Same layout as the
// server's report: logo + title, vehicle summary with cost totals, specs with
// their source labels, then every maintenance/repair entry in date order
// with its icon, details and photos.  The file is saved to Downloads.
package com.ratchetwrench.app.report

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.provider.MediaStore
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.ContextCompat
import com.ratchetwrench.app.R
import com.ratchetwrench.app.data.AppDatabase
import com.ratchetwrench.app.data.Attachment
import com.ratchetwrench.app.data.Vehicle
import com.ratchetwrench.app.domain.Catalog
import com.ratchetwrench.app.ui.miles
import com.ratchetwrench.app.ui.money
import com.ratchetwrench.app.ui.prettyDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter

object LocalReport {
    private const val W = 612 // US Letter, in points
    private const val H = 792
    private const val M = 40f // margin
    private val BRAND = Color.rgb(234, 88, 12)
    private val INK = Color.rgb(17, 24, 39)
    private val MUTED = Color.rgb(100, 116, 139)
    private val LINE = Color.rgb(226, 232, 240)
    private val MAINT = Color.rgb(13, 148, 136)
    private val REPAIR = Color.rgb(220, 38, 38)

    /** Build the PDF and save it to Downloads.  Returns a message for the user. */
    suspend fun createAndSave(context: Context, vehicle: Vehicle, currency: String): String = withContext(Dispatchers.IO) {
        val bytes = build(context, vehicle, currency)
        val name = (vehicle.fullName().ifBlank { vehicle.displayName() }).replace(Regex("[^A-Za-z0-9]+"), "-") + "-history.pdf"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("Could not save the file.")
        resolver.openOutputStream(uri)!!.use { it.write(bytes) }
        values.clear(); values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        withContext(Dispatchers.Main) {
            runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/pdf")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
        "Saved to Downloads: $name"
    }

    /** A tiny page "pen" that moves down the page and starts new pages. */
    private class Pen(val doc: PdfDocument, val title: String) {
        var pageNo = 0
        lateinit var page: PdfDocument.Page
        lateinit var canvas: Canvas
        var y = 0f

        fun newPage() {
            if (pageNo > 0) finish()
            pageNo++
            page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, pageNo).create())
            canvas = page.canvas
            y = M
        }

        fun finish() {
            val p = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 8f; color = MUTED }
            canvas.drawText("Ratchet Wrench - $title", M, H - 22f, p)
            val right = "Page $pageNo"
            canvas.drawText(right, W - M - p.measureText(right), H - 22f, p)
            doc.finishPage(page)
        }

        /** Make sure `need` points fit; otherwise start a new page. */
        fun ensure(need: Float) { if (y + need > H - 50f) newPage() }

        fun text(s: String, size: Float, color: Int, bold: Boolean = false, x: Float = M, width: Float = W - 2 * M): Float {
            val p = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = size; this.color = color; typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            }
            val layout = StaticLayout.Builder.obtain(s, 0, s.length, p, width.toInt()).setAlignment(Layout.Alignment.ALIGN_NORMAL).build()
            ensure(layout.height.toFloat())
            canvas.save(); canvas.translate(x, y); layout.draw(canvas); canvas.restore()
            y += layout.height
            return layout.height.toFloat()
        }
    }

    private suspend fun build(context: Context, v: Vehicle, currency: String): ByteArray {
        val db = AppDatabase.get(context)
        val entries = db.entries().observeFor(v.id).first().sortedWith(compareBy({ it.date }, { it.mileage ?: 0 }))
        val specs = db.specs().observeFor(v.id).first().filter { it.value.isNotBlank() }
        val photos = db.attachments().observeForVehicle(v.id).first()
            .filter { it.entry_id != null && it.content_type.startsWith("image/") }.groupBy { it.entry_id!! }
        val catalog = Catalog.get(context)
        val order = catalog.specs.mapIndexed { i, s -> s.key to i }.toMap()
        val unit = v.mileage_unit
        val title = v.displayName()

        val doc = PdfDocument()
        val pen = Pen(doc, title)
        pen.newPage()
        val c = { pen.canvas }

        // ---- Header: logo tile + title -------------------------------------
        val tile = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BRAND }
        c().drawRoundRect(RectF(M, M, M + 40, M + 40), 9f, 9f, tile)
        ContextCompat.getDrawable(context, R.drawable.ic_launcher_foreground)?.let { d ->
            d.setBounds((M - 10).toInt(), (M - 10).toInt(), (M + 50).toInt(), (M + 50).toInt()); d.draw(c())
        }
        pen.y = M
        pen.text("Vehicle History Report", 20f, INK, bold = true, x = M + 52)
        pen.text("$title  -  generated ${LocalDate.now().format(DateTimeFormatter.ofPattern("MMMM d, yyyy"))}", 10f, MUTED, x = M + 52)
        pen.y = M + 52
        c().drawRect(M, pen.y, W - M, pen.y + 2, tile)
        pen.y += 16

        // ---- Summary ----------------------------------------------------------
        val maint = entries.filter { it.kind == "maintenance" }.sumOf { it.cost_cents ?: 0 }
        val repair = entries.filter { it.kind == "repair" }.sumOf { it.cost_cents ?: 0 }
        pen.text("Summary", 13f, INK, bold = true); pen.y += 4
        val rows = listOf(
            "Vehicle" to v.fullName().ifBlank { "-" }, "Nickname" to v.nickname.ifBlank { "-" },
            "Type" to if (v.kind == "motorcycle") "Motorcycle" else "Car", "VIN" to v.vin.ifBlank { "-" },
            "Current mileage" to miles(v.current_mileage, unit),
            "Records" to "${entries.count { it.kind == "maintenance" }} maintenance, ${entries.count { it.kind == "repair" }} repair",
            "Maintenance cost" to money(maint, currency), "Repair cost" to money(repair, currency), "Total cost" to money(maint + repair, currency),
        )
        val colW = (W - 2 * M) / 2
        rows.chunked(2).forEach { pair ->
            val top = pen.y
            var bottom = top
            pair.forEachIndexed { i, (k, value) ->
                pen.y = top
                val x = M + i * colW
                pen.text(k, 8.5f, MUTED, x = x, width = 90f)
                pen.y = top
                pen.text(value, 10f, INK, bold = true, x = x + 92, width = colW - 96)
                bottom = maxOf(bottom, pen.y)
            }
            pen.y = bottom + 4
        }
        pen.y += 10

        // ---- Specs ------------------------------------------------------------
        pen.text("Specifications", 13f, INK, bold = true); pen.y += 4
        if (specs.isEmpty()) pen.text("No specs recorded yet.", 10f, MUTED)
        specs.sortedBy { order[it.key] ?: 999 }.forEachIndexed { i, s ->
            pen.ensure(16f)
            if (i % 2 == 0) c().drawRect(M, pen.y, W - M, pen.y + 15, Paint().apply { color = Color.rgb(248, 250, 252) })
            val top = pen.y
            pen.text(Catalog.specLabel(context, s.key), 9f, MUTED, x = M + 2, width = 200f); pen.y = top
            pen.text(s.value, 10f, INK, bold = true, x = M + 210, width = 200f); pen.y = top
            pen.text(catalog.spec_sources[s.source] ?: s.source, 8f, MUTED, x = W - M - 110, width = 108f)
            pen.y = top + 15
        }
        pen.y += 14

        // ---- Entries ----------------------------------------------------------
        pen.text("Service history", 13f, INK, bold = true); pen.y += 4
        if (entries.isEmpty()) pen.text("No maintenance or repair records yet.", 10f, MUTED)
        for (e in entries) {
            pen.ensure(70f)
            c().drawRect(M, pen.y, W - M, pen.y + 0.6f, Paint().apply { color = LINE })
            pen.y += 8
            val top = pen.y
            val color = if (e.kind == "repair") REPAIR else MAINT
            ContextCompat.getDrawable(context, Catalog.iconRes(context, e.type_key))?.mutate()?.let { d ->
                d.setTint(color); d.setBounds(M.toInt(), top.toInt(), (M + 22).toInt(), (top + 22).toInt()); d.draw(c())
            }
            val tx = M + 32
            val tw = W - M - tx
            pen.text(e.custom_type.ifBlank { Catalog.typeLabel(context, e.type_key) }, 12f, INK, bold = true, x = tx, width = tw - 90)
            val kindText = if (e.kind == "repair") "REPAIR" else "MAINTENANCE"
            val kp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 8f; this.color = color; typeface = Typeface.DEFAULT_BOLD }
            c().drawText(kindText, W - M - kp.measureText(kindText), top + 10, kp)
            val who = if (e.performed_by == "shop") (if (e.shop_name.isNotBlank()) "Shop: ${e.shop_name}" else "Shop") else "DIY"
            pen.text(listOfNotNull(prettyDate(e.date), e.mileage?.let { miles(it, unit) }, money(e.cost_cents, currency), who)
                .joinToString("   |   "), 9f, MUTED, x = tx, width = tw)
            pen.y += 2
            if (e.description.isNotBlank()) pen.text(e.description, 9.5f, INK, x = tx, width = tw)
            if (e.parts_used.isNotBlank()) pen.text("Parts: ${e.parts_used}", 9.5f, INK, bold = true, x = tx, width = tw)
            if (e.notes.isNotBlank()) pen.text("Notes: ${e.notes}", 9.5f, INK, bold = true, x = tx, width = tw)
            drawPhotos(pen, photos[e.id].orEmpty(), tx, tw)
            pen.y += 8
        }
        pen.finish()
        val out = ByteArrayOutputStream()
        doc.writeTo(out)
        doc.close()
        return out.toByteArray()
    }

    /** Photos two per row, scaled to fit (only photos stored on this phone). */
    private fun drawPhotos(pen: Pen, list: List<Attachment>, x0: Float, width: Float) {
        val files = list.mapNotNull { a -> a.localPath?.let { File(it) }?.takeIf { it.isFile } }
        if (files.isEmpty()) return
        pen.y += 6
        val gap = 8f
        val cellW = (width - gap) / 2
        files.chunked(2).forEach { row ->
            val bitmaps = row.mapNotNull { decodeScaled(it, 900) }
            if (bitmaps.isEmpty()) return@forEach
            val rowH = bitmaps.maxOf { minOf(cellW * it.height / it.width, 190f) }
            pen.ensure(rowH + 6)
            bitmaps.forEachIndexed { i, bmp ->
                val h = minOf(cellW * bmp.height / bmp.width, 190f)
                val w = h * bmp.width / bmp.height
                val x = x0 + i * (cellW + gap)
                pen.canvas.drawBitmap(bmp, null, RectF(x, pen.y, x + w, pen.y + h), Paint(Paint.FILTER_BITMAP_FLAG))
                bmp.recycle()
            }
            pen.y += rowH + 6
        }
    }

    /** Decode an image file at a reduced size (keeps memory low). */
    private fun decodeScaled(file: File, maxSide: Int): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSide) sample *= 2
        com.ratchetwrench.app.camera.Photos.decodeUpright(file, sample) // right way up
    } catch (e: Exception) { null }
}
