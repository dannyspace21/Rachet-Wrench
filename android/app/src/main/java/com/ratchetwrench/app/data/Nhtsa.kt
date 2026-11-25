// Nhtsa.kt — decodes a VIN directly with the free US-government NHTSA vPIC
// service.  Used in PHONE-ONLY mode (when connected, the computer/server
// does this instead).
//
// This is the same documented outbound connection as the server's (see
// SECURITY.md, connection #1): only the VIN is sent, only to
// https://vpic.nhtsa.dot.gov, redirects are never followed.  Only values the
// service actually returns are used — nothing is guessed — and every spec
// is labelled "From VIN".  Mirrors server/app/vin.py (parse_nhtsa_result).
package com.ratchetwrench.app.data

import com.ratchetwrench.app.domain.Vin
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/** What the decoder found: vehicle fields + spec values (key -> value). */
data class VinResult(
    val year: Int?,
    val make: String?,
    val model: String?,
    val trim: String?,
    val kind: String?,
    val specs: Map<String, String>,
    val warning: String?,
)

object Nhtsa {
    private const val BASE = "https://vpic.nhtsa.dot.gov/api/vehicles/DecodeVinValues/"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    // vPIC field -> our spec key (same list as the server).
    private val specFields = mapOf(
        "DisplacementL" to "displacement_l", "EngineCylinders" to "cylinders", "FuelTypePrimary" to "fuel_type",
        "BodyClass" to "body_style", "DriveType" to "drive_type", "TransmissionStyle" to "transmission",
        "Trim" to "trim", "PlantCity" to "plant", "Seats" to "seating_capacity",
    )

    /** Decode a VIN.  Throws IOException with a friendly message on failure.
     *  Call from a background thread. */
    fun decode(rawVin: String): VinResult {
        val vin = Vin.normalize(rawVin)
        if (!Vin.isValid(vin, requireCheckDigit = false)) throw IOException("That doesn't look like a valid 17-character VIN.")
        // The VIN is A-Z/0-9 only after normalize(), so it is safe in the URL.
        val req = Request.Builder().url("$BASE$vin?format=json").header("Accept", "application/json").build()
        val body = try {
            client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) throw IOException("The NHTSA VIN service answered with an error (${res.code}).")
                res.body.string().take(512 * 1024)
            }
        } catch (e: IOException) {
            throw IOException(e.message?.takeIf { it.startsWith("The NHTSA") } ?: "Could not reach the NHTSA VIN service. Check your internet connection.")
        }
        val results = try { JsonFormat.parseToJsonElement(body).jsonObject["Results"]?.jsonArray } catch (e: Exception) { null }
        val r = results?.firstOrNull()?.jsonObject ?: throw IOException("The NHTSA VIN service returned no data for this VIN.")
        return parse(r, vin)
    }

    /** Turn one vPIC result into our shape (empty / "Not Applicable" / "0" = unknown). */
    fun parse(r: JsonObject, vin: String = ""): VinResult {
        fun v(key: String): String? = r[key]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() && it != "Not Applicable" && it != "0" }
        val type = v("VehicleType")?.uppercase() ?: ""
        var make = v("Make")?.lowercase()?.split(" ")?.joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
        if (make == "Bmw" || make == "Gmc") make = make.uppercase()
        val specs = linkedMapOf<String, String>()
        for ((src, key) in specFields) v(src)?.let { specs[key] = it }
        // Short engine description, only from returned fields, e.g. "1.8L In-Line4".
        val engine = buildList {
            v("DisplacementL")?.toDoubleOrNull()?.let { add("%.1fL".format(it)) }
            val cyl = v("EngineCylinders")
            val cfg = v("EngineConfiguration")?.substringBefore('-')?.trim()
            if (cyl != null) add(if (cfg != null) "$cfg$cyl" else "$cyl-cyl")
            v("EngineModel")?.let { add("($it)") }
        }.joinToString(" ")
        if (engine.isNotBlank()) specs["engine"] = engine
        val warnings = mutableListOf<String>()
        if (vin.isNotEmpty() && !Vin.isValid(vin)) warnings += "The VIN's check digit doesn't match. Double-check it (normal for some non-US vehicles)."
        if (v("ErrorCode") != null && v("ErrorCode") != "0") v("ErrorText")?.let { warnings += it.take(300) }
        return VinResult(
            year = v("ModelYear")?.toIntOrNull(), make = make, model = v("Model"), trim = v("Trim"),
            kind = when { "MOTORCYCLE" in type -> "motorcycle"; type.isNotEmpty() -> "car"; else -> null },
            specs = specs, warning = warnings.joinToString(" ").ifBlank { null },
        )
    }
}
