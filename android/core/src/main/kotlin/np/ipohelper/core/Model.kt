package np.ipohelper.core

import org.json.JSONArray
import org.json.JSONObject

/** A family member whose MeroShare account the app applies for. */
data class Person(
    val id: String,
    val name: String,
    val dp: String,           // DP code, e.g. "13700"
    val username: String,
    val password: String,
    val pin: String,
    val crn: String,
    val bank: String? = null, // only needed when several banks are linked
    val rightMaxKitta: Int? = null,
    val enabled: Boolean = true,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("dp", dp).put("username", username)
        .put("password", password).put("pin", pin).put("crn", crn)
        .putOpt("bank", bank).putOpt("rightMaxKitta", rightMaxKitta)
        .put("enabled", enabled)

    /** Same as [toJson] without password and PIN, safe to show in the app's screens. */
    fun toPublicJson(): JSONObject = toJson().apply { remove("password"); remove("pin") }
        .put("hasPassword", password.isNotEmpty()).put("hasPin", pin.isNotEmpty())

    companion object {
        fun fromJson(o: JSONObject) = Person(
            id = o.getString("id"), name = o.getString("name"), dp = o.getString("dp"),
            username = o.getString("username"), password = o.optString("password"), pin = o.optString("pin"),
            crn = o.getString("crn"), bank = o.optStringOrNull("bank"),
            rightMaxKitta = if (o.has("rightMaxKitta") && !o.isNull("rightMaxKitta")) o.getInt("rightMaxKitta") else null,
            enabled = o.optBoolean("enabled", true),
        )
    }
}

/** When to apply. */
enum class Mode { LAST_DAY, DAY_BEFORE }

/** What happened to one issue for one person. */
data class IssueStatus(
    val state: String,          // wait | applied | would | failed | done | skip
    val note: String,
    val kitta: Int? = null,
    val amount: Long? = null,
    val at: String,             // ISO date-time of this status
) {
    fun toJson(): JSONObject = JSONObject().put("state", state).put("note", note)
        .putOpt("kitta", kitta).putOpt("amount", amount).put("at", at)

    companion object {
        fun fromJson(o: JSONObject) = IssueStatus(
            o.getString("state"), o.optString("note"),
            if (o.has("kitta")) o.getInt("kitta") else null,
            if (o.has("amount")) o.getLong("amount") else null,
            o.optString("at"),
        )
    }
}

/** An open issue as last seen on MeroShare. */
data class IssueInfo(
    val scrip: String,
    val company: String,
    val type: String,
    val group: String,
    val close: String?,     // ISO date, Nepal time
    val kind: String?,      // IPO | FPO | RIGHT, or null when skipped
    val skipReason: String?,
) {
    fun toJson(): JSONObject = JSONObject().put("scrip", scrip).put("company", company).put("type", type)
        .put("group", group).putOpt("close", close).putOpt("kind", kind).putOpt("skipReason", skipReason)

    companion object {
        fun fromJson(o: JSONObject) = IssueInfo(
            o.getString("scrip"), o.optString("company"), o.optString("type"), o.optString("group"),
            o.optStringOrNull("close"), o.optStringOrNull("kind"), o.optStringOrNull("skipReason"),
        )
    }
}

/** Per-person result of one run, shown in the Updates screen. */
data class PersonResult(
    val name: String,
    val ok: Boolean,
    val error: String?,
    val warnings: List<String>,
    val lines: List<String>,
) {
    fun toJson(): JSONObject = JSONObject().put("name", name).put("ok", ok).putOpt("error", error)
        .put("warnings", JSONArray(warnings)).put("lines", JSONArray(lines))

    companion object {
        fun fromJson(o: JSONObject) = PersonResult(
            o.getString("name"), o.getBoolean("ok"), o.optStringOrNull("error"),
            o.optJSONArray("warnings").strings(), o.optJSONArray("lines").strings(),
        )
    }
}

/** One run, kept in the history. */
data class RunRecord(
    val time: String,
    val kind: String,              // check | auto
    val applied: List<String>,
    val problems: List<String>,
    val warnings: List<String>,
    val people: List<PersonResult>,
) {
    val ok get() = problems.isEmpty()

    fun toJson(): JSONObject = JSONObject().put("time", time).put("kind", kind).put("ok", ok)
        .put("applied", JSONArray(applied)).put("problems", JSONArray(problems))
        .put("warnings", JSONArray(warnings))
        .put("people", JSONArray(people.map { it.toJson() }))

    companion object {
        fun fromJson(o: JSONObject) = RunRecord(
            o.getString("time"), o.getString("kind"),
            o.optJSONArray("applied").strings(), o.optJSONArray("problems").strings(),
            o.optJSONArray("warnings").strings(),
            o.optJSONArray("people")?.let { a -> (0 until a.length()).map { PersonResult.fromJson(a.getJSONObject(it)) } } ?: emptyList(),
        )
    }
}

/** A phone notification the app should show. [key] stops the same one being shown twice. */
data class Notice(val key: String, val title: String, val body: String)

internal fun JSONObject.optStringOrNull(name: String): String? =
    if (has(name) && !isNull(name)) getString(name).takeIf { it.isNotEmpty() } else null

internal fun JSONArray?.strings(): List<String> =
    if (this == null) emptyList() else (0 until length()).map { getString(it) }
