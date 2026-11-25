package np.ipohelper.core

import org.json.JSONObject
import java.text.NumberFormat
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Decides what to do and does it. All times are Nepal time.
 *
 * Background schedule (the app calls [tick] about every hour, and as soon as the phone is back online):
 *  - 9:00 AM to 4:45 PM: once a day, log in to every account, note open issues, and apply for the ones
 *    whose apply day is today. Later ticks that day retry anything that is due but not yet applied.
 *  - From 2:00 PM: if something due today still isn't applied, warn so you can apply yourself before 5 PM.
 * Notifications are only for problems; everything else is shown in the app.
 */
class Engine(
    private val newClient: () -> MeroShare = { MeroShare() },
    private val pause: (Long) -> Unit = { Thread.sleep(it) },
) {
    companion object {
        val WINDOW_START: LocalTime = LocalTime.of(9, 0)
        val WINDOW_END: LocalTime = LocalTime.of(16, 45)
        val WARN_FROM: LocalTime = LocalTime.of(14, 0)
        const val EXPIRY_WARN_DAYS = 10
        private val rupees = NumberFormat.getIntegerInstance(Locale("en", "IN"))
        fun rs(amount: Long) = "Rs " + rupees.format(amount)
        private val DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)
    }

    data class Outcome(val record: RunRecord?, val notices: List<Notice>)

    /** Background step. */
    fun tick(people: List<Person>, state: AppState, now: ZonedDateTime): Outcome {
        val t = now.withZoneSameInstant(NEPAL)
        val today = t.toLocalDate()
        val time = t.toLocalTime()
        val active = people.filter { it.enabled }
        val notices = mutableListOf<Notice>()
        var record: RunRecord? = null

        val inWindow = !time.isBefore(WINDOW_START) && time.isBefore(WINDOW_END)
        val ranToday = state.lastFullRun?.let { runCatching { ZonedDateTime.parse(it).withZoneSameInstant(NEPAL).toLocalDate() == today }.getOrNull() } == true
        if (active.isNotEmpty() && inWindow && (!ranToday || state.retry || pendingToday(active, state, today))) {
            val out = run(active, state, t, dryRun = false)
            record = out.record
            notices += out.notices
        }

        // Still not applied by 2 PM on the last day: tell the user while there's time to do it by hand.
        if (!time.isBefore(WARN_FROM)) {
            for ((scrip, info) in state.issues) {
                val close = info.close?.let(LocalDate::parse) ?: continue
                if (info.kind == null || close != today) continue
                val who = active.filter { p -> state.status[p.id]?.get(scrip)?.state in setOf("wait", "failed") }
                if (who.isEmpty()) continue
                val key = "missed:$scrip:$today"
                if (key in state.notified) continue
                state.notified[key] = today.toString()
                notices += Notice(key, "$scrip closes today at 5 PM",
                    "Not applied yet for ${who.joinToString { it.name }}. The app keeps trying, but you may want to apply in MeroShare yourself.")
            }
        }

        prune(state, today)
        return Outcome(record, notices)
    }

    private fun pendingToday(people: List<Person>, state: AppState, today: LocalDate): Boolean =
        state.issues.any { (scrip, info) ->
            val close = info.close?.let(LocalDate::parse)
            info.kind != null && Rules.isDue(close, today, state.mode) &&
                people.any { p -> state.status[p.id]?.get(scrip)?.state in setOf("wait", "failed") }
        }

    /** Log in to every account, look at open issues and apply for the due ones (or only report, when [dryRun]). */
    fun run(people: List<Person>, state: AppState, now: ZonedDateTime, dryRun: Boolean): Outcome {
        val t = now.withZoneSameInstant(NEPAL)
        val today = t.toLocalDate()
        val stamp = t.truncatedTo(ChronoUnit.MINUTES).toOffsetDateTime().toString()
        val applied = mutableListOf<String>()
        val problems = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val results = mutableListOf<PersonResult>()
        val fresh = linkedMapOf<String, IssueInfo>()
        val notices = mutableListOf<Notice>()
        val pendingKeys = mutableListOf<String>()

        val active = people.filter { it.enabled }
        if (active.isEmpty()) problems += "Nobody is added yet. Add your family in the app."
        var retry = false

        for (p in active) {
            val lines = mutableListOf<String>()
            val pWarn = mutableListOf<String>()
            var error: String? = null
            val statuses = state.status.getOrPut(p.id) { linkedMapOf() }
            // A wrong password is not retried automatically: repeated failed logins can lock the account.
            if (!dryRun && state.loginFail[p.id] != null) {
                error = "Login failed earlier. Update the password in the app (the app won't retry until then)."
                problems += "${p.name}: $error"
                results += PersonResult(p.name, false, error, emptyList(), emptyList())
                continue
            }
            try {
                val ms = newClient()
                ms.login(p.dp, p.username, p.password)
                val me = ms.ownDetail()
                pWarn += expiryWarnings(listOf(ms.authInfo, me), today)
                val issues = ms.applicableIssues()
                var bank: Pair<Int, JSONObject>? = null
                val seen = mutableSetOf<String>()
                for (i in 0 until issues.length()) {
                    val iss = issues.getJSONObject(i)
                    val scrip = iss.optString("scrip", "?")
                    seen += scrip
                    val close = Rules.parseDate(iss.opt("issueCloseDate"))
                    val (kind, why) = Rules.classify(iss.optString("shareTypeName"), iss.optString("shareGroupName"), iss.optString("subGroup"), iss.optString("companyName"))
                    fresh[scrip] = IssueInfo(scrip, iss.optString("companyName"), iss.optString("shareTypeName"),
                        iss.optString("subGroup").ifEmpty { iss.optString("shareGroupName") }, close?.toString(), kind, if (kind == null) why else null)
                    fun set(state: String, note: String, kitta: Int? = null, amount: Long? = null) {
                        statuses[scrip] = IssueStatus(state, note, kitta, amount, stamp)
                    }
                    if (kind == null) { set("skip", why); continue }
                    val action = iss.optString("action")
                    val statusName = iss.optString("statusName")
                    if (action in setOf("edit", "inProcess", "reapply") || statusName !in setOf("", "null", "CREATE_APPROVE")) {
                        if (statuses[scrip]?.state != "applied") set("done", "already applied")
                        lines += "$scrip: already applied"
                        continue
                    }
                    if (!Rules.isDue(close, today, state.mode)) {
                        val day = close?.let { Rules.applyDate(it, state.mode) }
                        set("wait", if (day != null) "will apply on ${day.format(DAY)}" else "closing date unknown")
                        lines += "$scrip: will apply on ${day?.format(DAY) ?: "?"}"
                        continue
                    }
                    // Due today.
                    val detail = ms.issueDetail(iss.optLong("companyShareId"))
                    val price = detail.optDouble("sharePerUnit", 100.0).takeIf { !it.isNaN() && it > 0 } ?: 100.0
                    val kitta: Int
                    if (kind == "RIGHT") {
                        val k = Rules.rightKitta(detail.toMap(), p.rightMaxKitta)
                        if (k == null) {
                            set("failed", "couldn't find the right share entitlement")
                            problems += "${p.name}: $scrip - couldn't find the right share entitlement"
                            continue
                        }
                        kitta = k
                    } else kitta = Rules.chooseKitta(detail.optInt("minUnit", 10)).kitta
                    val amount = (kitta * price).toLong()
                    if (bank == null) bank = ms.bankAccount(p.bank)
                    val (bankId, acct) = bank
                    val payload = JSONObject()
                        .put("demat", me.optString("demat")).put("boid", me.optString("boid"))
                        .put("accountNumber", acct.optString("accountNumber"))
                        .put("customerId", acct.opt("id")).put("accountBranchId", acct.opt("accountBranchId"))
                        .put("accountTypeId", acct.opt("accountTypeId"))
                        .put("appliedKitta", kitta.toString()).put("crnNumber", p.crn)
                        .put("transactionPIN", p.pin).put("companyShareId", iss.optLong("companyShareId").toString())
                        .put("bankId", bankId)
                    if (dryRun) {
                        lines += "$scrip: would apply $kitta kitta (${rs(amount)})"
                        applied += "${p.name}: $scrip $kitta kitta (${rs(amount)}) - test, not applied"
                        continue
                    }
                    val (ok, msg) = ms.apply(payload)
                    if (ok) {
                        set("applied", "applied $kitta kitta", kitta, amount)
                        lines += "$scrip: applied $kitta kitta (${rs(amount)})"
                        applied += "${p.name}: $scrip $kitta kitta (${rs(amount)})"
                    } else {
                        set("failed", msg)
                        lines += "$scrip: couldn't apply - $msg"
                        problems += "${p.name}: $scrip - $msg"
                    }
                }
                // Issues no longer listed for this person are closed (or applied and hidden).
                statuses.keys.retainAll(seen + statuses.filterValues { it.state == "applied" }.keys)
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
                problems += "${p.name}: $error"
                if (e is MeroShareException && e.loginProblem) state.loginFail[p.id] = today.toString()
                else retry = true
            }
            warnings += pWarn.map { "${p.name}: $it" }
            results += PersonResult(p.name, error == null, error, pWarn, lines)
            pause(1500) // be gentle between accounts
        }

        // Keep what we knew about issues that couldn't be refreshed (e.g. no internet), until they close.
        state.issues.entries.removeIf { e -> e.value.close?.let { LocalDate.parse(it).isBefore(today) } ?: true }
        state.issues.putAll(fresh)
        if (!dryRun) { state.lastFullRun = t.toOffsetDateTime().toString(); state.retry = retry }
        state.status.keys.retainAll(active.map { it.id }.toSet() + people.map { it.id })

        val record = RunRecord(stamp, if (dryRun) "check" else "auto", applied, problems, warnings, results)
        state.history.add(0, record)
        while (state.history.size > AppState.HISTORY) state.history.removeAt(state.history.size - 1)

        // Notifications only when something went wrong in an automatic run (each problem once a day).
        // Successful applications, checks and expiry reminders are shown in the app only.
        if (!dryRun) {
            val failKey = "problems:$today:" + problems.sorted().joinToString("|").hashCode()
            if (problems.isNotEmpty() && failKey !in state.notified) {
                pendingKeys += failKey
                notices += Notice(failKey, "IPO Helper: problem", problems.take(3).joinToString("\n") +
                    (if (problems.size > 3) "\n+${problems.size - 3} more" else "") +
                    (if (retry) "\nIt will try again in about an hour." else "\nOpen the app to fix it."))
            }
            pendingKeys.forEach { state.notified[it] = today.toString() }
        }
        return Outcome(record, notices)
    }

    private fun expiryWarnings(sources: List<JSONObject>, today: LocalDate): List<String> {
        val found = linkedMapOf<String, LocalDate>()
        for (src in sources) for (key in src.keys()) {
            val k = key.lowercase()
            val v = src.opt(key)
            if ("expir" !in k || v is Boolean) continue
            val d = Rules.parseDate(v) ?: continue
            val what = when {
                "password" in k -> "MeroShare password"
                "demat" in k -> "DEMAT account"
                "account" in k -> "MeroShare account"
                else -> "MeroShare $key"
            }
            found[what] = minOf(d, found[what] ?: d)
        }
        return found.mapNotNull { (what, d) ->
            val days = ChronoUnit.DAYS.between(today, d)
            when {
                days < 0 -> "$what expired on ${d.format(DAY)}"
                days <= EXPIRY_WARN_DAYS -> "$what expires in $days day${if (days == 1L) "" else "s"} (${d.format(DAY)})"
                else -> null
            }
        }
    }

    private fun prune(state: AppState, today: LocalDate) {
        val cutoff = today.minusDays(40)
        state.notified.entries.removeIf { runCatching { LocalDate.parse(it.value).isBefore(cutoff) }.getOrDefault(true) }
    }
}

private fun JSONObject.toMap(): Map<String, Any?> = keys().asSequence().associateWith { opt(it) }
