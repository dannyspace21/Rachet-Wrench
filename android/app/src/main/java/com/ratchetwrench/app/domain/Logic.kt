// Logic.kt — rules the phone needs while OFFLINE, mirroring the server:
//   * Vin           — VIN clean-up and check-digit validation
//   * Reminders     — when maintenance is due ("whichever comes first")
//   * Catalog       — maintenance types, spec fields and manufacturer links
//                     (read from the same catalog.json the server uses)
package com.ratchetwrench.app.domain

import android.content.Context
import com.ratchetwrench.app.data.Reminder
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.temporal.ChronoUnit

object Vin {
    private val translit: Map<Char, Int> = buildMap {
        ('0'..'9').forEach { put(it, it - '0') }
        "ABCDEFGH".forEachIndexed { i, c -> put(c, i + 1) }
        "JKLMN".forEachIndexed { i, c -> put(c, i + 1) }
        put('P', 7); put('R', 9)
        "STUVWXYZ".forEachIndexed { i, c -> put(c, i + 2) }
    }
    private val weights = intArrayOf(8, 7, 6, 5, 4, 3, 2, 10, 0, 9, 8, 7, 6, 5, 4, 3, 2)
    private val allowed = Regex("^[A-HJ-NPR-Z0-9]{17}$")

    /** Upper-case, drop spaces, fix letters never used in VINs (I->1, O/Q->0). */
    fun normalize(raw: String): String =
        raw.uppercase().filter { it.isLetterOrDigit() }.replace('I', '1').replace('O', '0').replace('Q', '0')

    fun checkDigit(vin: String): Char {
        val total = vin.mapIndexed { i, c -> (translit[c] ?: 0) * weights[i] }.sum()
        val rem = total % 11
        return if (rem == 10) 'X' else ('0' + rem)
    }

    /** True for a well-formed VIN.  Some non-US vehicles skip the check digit. */
    fun isValid(vin: String, requireCheckDigit: Boolean = true): Boolean =
        allowed.matches(vin) && (!requireCheckDigit || vin[8] == checkDigit(vin))
}

/** Result of a reminder check. */
data class DueStatus(val status: String, val dueMileage: Int?, val dueDate: LocalDate?, val milesLeft: Int?, val daysLeft: Long?) {
    fun describe(unit: String): String {
        val parts = mutableListOf<String>()
        milesLeft?.let { parts += if (it <= 0) "%,d %s overdue".format(-it, unit) else "%,d %s left".format(it, unit) }
        daysLeft?.let { parts += when { it == 0L -> "due today"; it < 0 -> "${-it} days overdue"; else -> "$it days left" } }
        return parts.joinToString(" · ").ifEmpty { "Set when it was last done" }
    }
}

object Reminders {
    const val SOON_MILES = 500
    const val SOON_DAYS = 30L

    /** Same rules as the server: due at whichever limit is reached first. */
    fun status(r: Reminder, currentMileage: Int?, today: LocalDate = LocalDate.now()): DueStatus {
        val dueMiles = if (r.interval_miles != null && r.last_done_mileage != null) r.last_done_mileage + r.interval_miles else null
        val dueDate = if (r.interval_months != null && r.last_done_date != null)
            LocalDate.parse(r.last_done_date).plusMonths(r.interval_months.toLong()) else null
        val milesLeft = if (dueMiles != null && currentMileage != null) dueMiles - currentMileage else null
        val daysLeft = dueDate?.let { ChronoUnit.DAYS.between(today, it) }
        val status = when {
            !r.enabled -> "disabled"
            dueMiles == null && dueDate == null -> "unknown"
            (milesLeft != null && milesLeft <= 0) || (daysLeft != null && daysLeft <= 0) -> "overdue"
            (milesLeft != null && milesLeft <= SOON_MILES) || (daysLeft != null && daysLeft <= SOON_DAYS) -> "due_soon"
            else -> "ok"
        }
        return DueStatus(status, dueMiles, dueDate, milesLeft, daysLeft)
    }
}

@Serializable data class TypeDef(val key: String, val label: String, val category: String, val applies_to: String, val icon: String)
@Serializable data class SpecDef(val key: String, val label: String, val group: String, val hint: String = "")
@Serializable data class MakeLinks(val parts: String? = null, val owners: String? = null,
                                   val moto_parts: String? = null, val moto_owners: String? = null)
@Serializable data class CatalogData(
    val types: List<TypeDef>,
    val specs: List<SpecDef>,
    val spec_sources: Map<String, String>,
    val makes: Map<String, MakeLinks>,
    /** Make names for the Add vehicle form: {"car": [...], "motorcycle": [...]}. */
    val make_names: Map<String, List<String>> = emptyMap(),
)

private val catalogJson = Json { ignoreUnknownKeys = true }

/** The shared catalog, bundled in the app (assets/catalog.json). */
object Catalog {
    @Volatile private var data: CatalogData? = null

    fun get(context: Context): CatalogData = data ?: synchronized(this) {
        data ?: catalogJson.decodeFromString(
            CatalogData.serializer(),
            context.assets.open("catalog.json").bufferedReader().use { it.readText() },
        ).also { data = it }
    }

    fun typeLabel(context: Context, key: String) = get(context).types.firstOrNull { it.key == key }?.label ?: "Custom / other"
    fun specLabel(context: Context, key: String) = if (key == "vin") "VIN" else get(context).specs.firstOrNull { it.key == key }?.label ?: key

    /** Types that apply to a car or a motorcycle. */
    fun typesFor(context: Context, kind: String) = get(context).types.filter { it.applies_to == "both" || it.applies_to == kind }

    /** Official links for a make (never a dealer site); parts falls back to owners.
     *  Makes that build cars AND motorcycles (Honda...) have separate motorcycle links. */
    fun links(context: Context, make: String, kind: String = "car"): MakeLinks {
        val m = get(context).makes.entries.firstOrNull { it.key.equals(make.trim(), ignoreCase = true) }?.value ?: return MakeLinks()
        if (kind == "motorcycle" && (m.moto_parts != null || m.moto_owners != null)) {
            val owners = m.moto_owners ?: m.owners
            return MakeLinks(parts = m.moto_parts ?: owners, owners = owners)
        }
        return MakeLinks(parts = m.parts ?: m.owners, owners = m.owners)
    }

    /** Make names offered when adding a car or a motorcycle. */
    fun makeNames(context: Context, kind: String): List<String> = get(context).make_names[kind].orEmpty()

    /** Drawable for a type icon (generated from the same SVGs).  Uses the
     *  generated direct-reference table — never a by-name lookup, which the
     *  release build's resource shrinker can't see. Unknown keys get the
     *  "Custom / other" icon. */
    @Suppress("UNUSED_PARAMETER")
    fun iconRes(context: Context, key: String): Int =
        com.ratchetwrench.app.ui.TYPE_ICON_RES[key] ?: com.ratchetwrench.app.ui.TYPE_ICON_RES.getValue("custom")
}
