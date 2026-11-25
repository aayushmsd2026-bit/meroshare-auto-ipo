package np.ipohelper.core

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

val NEPAL: ZoneId = ZoneId.of("Asia/Kathmandu")

object Rules {
    /**
     * Which issues to apply for. Returns the kind ("IPO", "FPO", "RIGHT") or null with the reason it's skipped.
     * Applied: ordinary-share IPOs/FPOs for the general public, and right shares.
     * Skipped: mutual funds, debentures/bonds, reserved quotas (foreign employment, locals, employees).
     */
    fun classify(shareType: String?, group: String?, subGroup: String?, company: String?): Pair<String?, String> {
        val type = (shareType ?: "").uppercase()
        val g = (group ?: "").lowercase()
        val sg = (subGroup ?: "").lowercase()
        val name = (company ?: "").lowercase()
        if ("mutual" in g || "fund" in g || "scheme" in name) return null to "mutual fund"
        if ("debenture" in g || "debenture" in name || "bond" in g) return null to "debenture/bond"
        if ("RIGHT" in type || "right" in sg) return "RIGHT" to "right share"
        if (type != "IPO" && type != "FPO") return null to "share type is ${type.ifEmpty { "unknown" }}"
        if (g.isNotEmpty() && "ordinary" !in g) return null to "share group is $group"
        if (sg.isNotEmpty() && "general public" !in sg) return null to "reserved quota ($subGroup)"
        return type to "$type for general public"
    }

    data class Kitta(val kitta: Int, val reason: String)

    /**
     * How many kitta for an IPO/FPO. Oversubscribed general-public issues are allotted by lottery,
     * one minimum lot per winner, so applying for more than the minimum only ties up money.
     */
    fun chooseKitta(minUnit: Int?): Kitta =
        Kitta((minUnit ?: 10).coerceAtLeast(1), "minimum - more kitta does not improve the lottery")

    /** Right shares: the full entitlement, optionally capped. */
    fun rightKitta(detail: Map<String, Any?>, cap: Int?): Int? {
        for (key in listOf("eligibleKitta", "rightShareEligibleKitta", "eligibleUnit", "maxUnit")) {
            val v = (detail[key] as? Number)?.toInt() ?: (detail[key] as? String)?.toDoubleOrNull()?.toInt()
            if (v != null && v > 0) return if (cap != null) minOf(v, cap) else v
        }
        return null
    }

    private val FORMATS = listOf(
        "MMM d, yyyy h:mm:ss a", "MMM dd, yyyy h:mm:ss a", "MMM d, yyyy", "MMM dd, yyyy",
        "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd",
    ).map { DateTimeFormatter.ofPattern(it, Locale.ENGLISH) }

    fun parseDate(value: Any?): LocalDate? {
        if (value == null) return null
        if (value is Number) return java.time.Instant.ofEpochMilli(value.toLong()).atZone(NEPAL).toLocalDate()
        val s = value.toString().trim()
        if (s.isEmpty()) return null
        for (f in FORMATS) {
            runCatching { return LocalDateTime.parse(s, f).toLocalDate() }
            runCatching { return LocalDate.parse(s, f) }
        }
        return runCatching { java.time.OffsetDateTime.parse(s).atZoneSameInstant(NEPAL).toLocalDate() }.getOrNull()
    }

    /** The day this issue should be applied on. */
    fun applyDate(close: LocalDate, mode: Mode): LocalDate =
        if (mode == Mode.DAY_BEFORE) close.minusDays(1) else close

    /** True when today is the apply day (or later, while the issue is still open). */
    fun isDue(close: LocalDate?, today: LocalDate, mode: Mode): Boolean =
        close != null && !today.isBefore(applyDate(close, mode)) && !today.isAfter(close)
}
