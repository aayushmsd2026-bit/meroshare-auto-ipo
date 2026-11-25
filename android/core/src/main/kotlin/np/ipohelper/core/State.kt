package np.ipohelper.core

import org.json.JSONArray
import org.json.JSONObject

/** Everything the app remembers between runs (except people, which are stored encrypted separately). */
class AppState(
    var mode: Mode = Mode.LAST_DAY,
    val issues: MutableMap<String, IssueInfo> = linkedMapOf(),
    /** personId -> scrip -> status */
    val status: MutableMap<String, MutableMap<String, IssueStatus>> = linkedMapOf(),
    var lastFullRun: String? = null,
    /** notice key -> date it was shown (so each notification is shown once) */
    val notified: MutableMap<String, String> = linkedMapOf(),
    val history: MutableList<RunRecord> = mutableListOf(),
    /** personId -> date a login failed because of the password/account. No retries until it's fixed. */
    val loginFail: MutableMap<String, String> = linkedMapOf(),
    /** The last automatic run hit a temporary problem (network, server): try again on the next tick. */
    var retry: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("mode", mode.name)
        .put("issues", JSONObject().also { o -> issues.forEach { (k, v) -> o.put(k, v.toJson()) } })
        .put("status", JSONObject().also { o ->
            status.forEach { (p, m) -> o.put(p, JSONObject().also { x -> m.forEach { (s, st) -> x.put(s, st.toJson()) } }) }
        })
        .putOpt("lastFullRun", lastFullRun)
        .put("notified", JSONObject(notified as Map<*, *>))
        .put("history", JSONArray(history.map { it.toJson() }))
        .put("loginFail", JSONObject(loginFail as Map<*, *>))
        .put("retry", retry)

    companion object {
        const val HISTORY = 30

        fun fromJson(text: String?): AppState {
            if (text.isNullOrBlank()) return AppState()
            val o = runCatching { JSONObject(text) }.getOrNull() ?: return AppState()
            val st = AppState(mode = runCatching { Mode.valueOf(o.optString("mode")) }.getOrDefault(Mode.LAST_DAY))
            o.optJSONObject("issues")?.let { i -> i.keys().forEach { k -> st.issues[k] = IssueInfo.fromJson(i.getJSONObject(k)) } }
            o.optJSONObject("status")?.let { s ->
                s.keys().forEach { p ->
                    val m = linkedMapOf<String, IssueStatus>()
                    val x = s.getJSONObject(p)
                    x.keys().forEach { k -> m[k] = IssueStatus.fromJson(x.getJSONObject(k)) }
                    st.status[p] = m
                }
            }
            st.lastFullRun = o.optStringOrNull("lastFullRun")
            o.optJSONObject("notified")?.let { n -> n.keys().forEach { k -> st.notified[k] = n.getString(k) } }
            o.optJSONObject("loginFail")?.let { n -> n.keys().forEach { k -> st.loginFail[k] = n.getString(k) } }
            st.retry = o.optBoolean("retry")
            o.optJSONArray("history")?.let { h -> (0 until h.length()).forEach { st.history.add(RunRecord.fromJson(h.getJSONObject(it))) } }
            return st
        }
    }
}

/** Reads and writes the people list as JSON (the app encrypts it). */
object PeopleJson {
    fun encode(people: List<Person>): String = JSONArray(people.map { it.toJson() }).toString()
    fun decode(text: String?): List<Person> {
        if (text.isNullOrBlank()) return emptyList()
        val a = JSONArray(text)
        return (0 until a.length()).map { Person.fromJson(a.getJSONObject(it)) }
    }
}
