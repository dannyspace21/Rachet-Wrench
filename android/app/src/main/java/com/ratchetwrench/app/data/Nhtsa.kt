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
import kotlinx.serialization.json.booleanOrNull
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

    // vPIC field -> our spec key (same rules as the server's vin.py; both
    // checked against catalog/vin_tests.json).  Empty fields stay absent.
    private val specFields = mapOf(
        "EngineCylinders" to "cylinders", "FuelTypePrimary" to "fuel_type", "BodyClass" to "body_style",
        "DriveType" to "drive_type", "Trim" to "trim", "Seats" to "seating_capacity", "EngineModel" to "engine_code",
        "ValveTrainDesign" to "valve_train", "OtherEngineInfo" to "engine_details", "Turbo" to "turbo",
        "ElectrificationLevel" to "electrification", "Series" to "model_code", "Doors" to "doors",
        "TPMS" to "tpms_type", "ABS" to "abs", "ESC" to "stability_control",
    )

    /** "113.20" -> "113.2", "240.0" -> "240" (like the server's _num). */
    private fun num(v: String?, digits: Int): String? {
        val x = v?.toBigDecimalOrNull()?.setScale(digits, java.math.RoundingMode.HALF_EVEN) ?: return null
        return x.stripTrailingZeros().toPlainString()
    }

    /** Like Python's str.title(): capital after any non-letter. */
    private fun title(s: String): String {
        val b = StringBuilder(); var prevLetter = false
        for (c in s.lowercase()) { b.append(if (!prevLetter && c.isLetter()) c.uppercaseChar() else c); prevLetter = c.isLetter() }
        return b.toString()
    }

    private val modelCache = java.util.concurrent.ConcurrentHashMap<String, List<String>>()
    private val makeOk = Regex("^[A-Za-z0-9 .&'-]{1,40}$")

    /**
     * Model names NHTSA knows for a make (and model year), for cars (cars,
     * SUVs/minivans, pickups) or motorcycles.  Only the make name and year
     * are sent.  Cached for the app session.  Call from a background thread.
     */
    fun models(make: String, year: Int?, kind: String): List<String> {
        val m = make.trim()
        if (!makeOk.matches(m)) return emptyList()
        val key = "$kind|${m.lowercase()}|${year ?: ""}"
        modelCache[key]?.let { return it }
        val types = if (kind == "motorcycle") listOf("motorcycle") else listOf("car", "mpv", "truck")
        val names = sortedSetOf(String.CASE_INSENSITIVE_ORDER)
        for (t in types) {
            val url = okhttp3.HttpUrl.Builder().scheme("https").host("vpic.nhtsa.dot.gov")
                .addPathSegments("api/vehicles/GetModelsForMakeYear/make").addPathSegment(m)
                .apply { if (year != null) addPathSegment("modelyear").addPathSegment(year.toString()) }
                .addPathSegment("vehicletype").addPathSegment(t).addQueryParameter("format", "json").build()
            val body = client.newCall(Request.Builder().url(url).header("Accept", "application/json").build()).execute().use { res ->
                if (!res.isSuccessful) throw IOException("The NHTSA vehicle database answered with an error (${res.code}).")
                res.body.string().take(1024 * 1024)
            }
            JsonFormat.parseToJsonElement(body).jsonObject["Results"]?.jsonArray?.forEach { r ->
                r.jsonObject["Model_Name"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }?.let { names.add(it) }
            }
        }
        return names.toList().take(500).also { modelCache[key] = it }
    }

    /** One recall as NHTSA lists it (for a year/make/model, not a VIN). */
    data class RecallInfo(val campaign: String, val component: String, val summary: String, val consequence: String,
                          val remedy: String, val reportDate: String?, val parkIt: Boolean)

    /**
     * Safety recalls NHTSA lists for a year/make/model.  Only those three are
     * sent (not the VIN).  Tries twice (NHTSA sometimes drops the first
     * request).  Throws IOException with a friendly message.  Background thread.
     */
    fun recalls(make: String, model: String, year: Int): List<RecallInfo> {
        val mk = make.trim(); val md = model.trim()
        if (!makeOk.matches(mk) || !makeOk.matches(md) || year !in 1950..2100) throw IOException("Recalls need the vehicle's year, make and model.")
        val url = okhttp3.HttpUrl.Builder().scheme("https").host("api.nhtsa.gov").addPathSegments("recalls/recallsByVehicle")
            .addQueryParameter("make", mk).addQueryParameter("model", md).addQueryParameter("modelYear", year.toString()).build()
        var body: String? = null
        for (attempt in 0..1) {
            try {
                body = client.newCall(Request.Builder().url(url).header("Accept", "application/json").build()).execute().use { res ->
                    if (!res.isSuccessful) throw IOException("NHTSA answered with an error (${res.code}).")
                    res.body.string().take(4 * 1024 * 1024)
                }
                break
            } catch (e: IOException) {
                if (attempt == 1) throw IOException("Could not reach the NHTSA recall database. Try again later.")
                Thread.sleep(1000)
            }
        }
        return parseRecalls(body ?: "")
    }

    /** NHTSA's recall answer -> tidy rows (same rules as the server). */
    fun parseRecalls(body: String): List<RecallInfo> {
        val results = runCatching { JsonFormat.parseToJsonElement(body).jsonObject["results"]?.jsonArray }.getOrNull() ?: return emptyList()
        return results.mapNotNull { el ->
            val r = el.jsonObject
            fun t(k: String) = r[k]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            fun b(k: String) = r[k]?.jsonPrimitive?.booleanOrNull == true
            val campaign = t("NHTSACampaignNumber").uppercase().filter { it.isLetterOrDigit() }.take(16)
            if (campaign.isEmpty()) return@mapNotNull null
            val d = Regex("""^(\d{2})/(\d{2})/(\d{4})$""").matchEntire(t("ReportReceivedDate"))?.groupValues
            RecallInfo(campaign, t("Component").take(255), t("Summary").take(5000), t("Consequence").take(5000), t("Remedy").take(5000),
                d?.let { "${it[3]}-${it[2]}-${it[1]}" }, b("parkIt") || b("parkOutSide"))
        }
    }

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
        v("DisplacementL")?.toDoubleOrNull()?.let { specs["displacement_l"] = "%.1f".format(java.util.Locale.US, it) }
        v("TransmissionStyle")?.let { style ->
            val speeds = num(v("TransmissionSpeeds"), 0)
            specs["transmission"] = style + if (speeds != null && speeds != "1") ", $speeds-speed" else ""
        }
        num(v("EngineHP"), 0)?.let { specs["horsepower"] = "$it hp" }
        num(v("CurbWeightLB"), 0)?.let { specs["curb_weight"] = "$it lb" }
        num(v("WheelBaseShort"), 1)?.let { specs["wheelbase"] = "$it in" }
        listOf("PlantCity", "PlantState", "PlantCountry").mapNotNull { k -> v(k)?.let { title(it.replace(Regex("""\s*\(.*?\)"""), "").trim()) } }
            .takeIf { it.isNotEmpty() }?.let { specs["plant"] = it.joinToString(", ") }
        // Short engine description, only from returned fields, e.g. "1.8L In-Line4".
        val engine = buildList {
            v("DisplacementL")?.toDoubleOrNull()?.let { add("%.1fL".format(java.util.Locale.US, it)) }
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
