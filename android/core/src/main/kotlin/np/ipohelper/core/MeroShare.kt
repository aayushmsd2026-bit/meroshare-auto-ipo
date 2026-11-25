package np.ipohelper.core

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class MeroShareException(message: String, val loginProblem: Boolean = false) : Exception(message)

/**
 * Talks to the MeroShare API (the one the MeroShare website uses), from this phone,
 * as a normal client that says who it is.
 */
class MeroShare(
    private val base: String = "https://webbackend.cdsc.com.np/api/meroShare",
    private val http: OkHttpClient = defaultClient(),
) {
    private var token: String? = null
    var authInfo: JSONObject = JSONObject()
        private set

    companion object {
        const val USER_AGENT = "IPOHelper/1.0 (Android; personal MeroShare helper)"
        private val JSON = "application/json".toMediaType()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS).readTimeout(45, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    private fun request(path: String, body: Any? = null): Response {
        val b = Request.Builder().url(base + path)
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
        token?.let { b.header("Authorization", it) }
        if (body != null) b.post(body.toString().toRequestBody(JSON)) else b.get()
        var last: IOException? = null
        repeat(3) { attempt ->
            try {
                val r = http.newCall(b.build()).execute()
                if (r.code < 500) return r
                r.close()
                last = IOException("MeroShare server error ${r.code}")
            } catch (e: IOException) {
                last = e
            }
            Thread.sleep(2000L * (attempt + 1))
        }
        throw MeroShareException("Couldn't reach MeroShare: ${last?.message ?: "no connection"}")
    }

    private fun Response.text(): String = use { it.body?.string().orEmpty() }

    private fun message(code: Int, text: String): String {
        if ("Request Rejected" in text) return "MeroShare's firewall refused the request (support ID in the page). Please try again later."
        return runCatching { JSONObject(text).optString("message") }.getOrNull()?.takeIf { it.isNotEmpty() }
            ?: "MeroShare answered with code $code"
    }

    private fun getJson(path: String): String {
        val r = request(path)
        val code = r.code
        val text = r.text()
        if (code !in 200..299) throw MeroShareException(message(code, text))
        return text
    }

    /** All depository participants, as shown on the MeroShare login screen. */
    fun capitals(): JSONArray = JSONArray(getJson("/capital/"))

    private fun dpId(dp: String): Int {
        val want = dp.trim().lowercase()
        val list = capitals()
        for (i in 0 until list.length()) {
            val o = list.getJSONObject(i)
            if (o.optString("code") == want || o.optString("id") == want || want in o.optString("name").lowercase()) return o.getInt("id")
        }
        throw MeroShareException("DP '$dp' was not found. Pick the DP again in the person's details.", loginProblem = true)
    }

    fun login(dp: String, username: String, password: String) {
        token = null
        val body = JSONObject().put("clientId", dpId(dp)).put("username", username).put("password", password)
        val r = request("/auth/", body)
        val code = r.code
        val auth = r.header("Authorization")
        val text = r.text()
        if (code != 200 || auth.isNullOrEmpty()) throw MeroShareException("Login failed: ${message(code, text)}", loginProblem = true)
        authInfo = runCatching { JSONObject(text) }.getOrDefault(JSONObject())
        if (authInfo.optBoolean("passwordExpired") || authInfo.optBoolean("changePassword"))
            throw MeroShareException("The MeroShare password has expired. Change it on MeroShare, then update it in this app.", loginProblem = true)
        if (authInfo.optBoolean("accountExpired")) throw MeroShareException("The MeroShare account has expired. Renew it with the DP.", loginProblem = true)
        if (authInfo.optBoolean("dematExpired")) throw MeroShareException("The DEMAT account has expired. Renew it with the DP.", loginProblem = true)
        token = auth
    }

    fun ownDetail(): JSONObject = JSONObject(getJson("/ownDetail/"))

    fun applicableIssues(): JSONArray {
        val body = JSONObject()
            .put("filterFieldParams", JSONArray()
                .put(JSONObject().put("key", "companyIssue.companyISIN.script").put("alias", "Scrip"))
                .put(JSONObject().put("key", "companyIssue.companyISIN.company.name").put("alias", "Company Name"))
                .put(JSONObject().put("key", "companyIssue.assignedToClient.name").put("value", "").put("alias", "Issue Manager")))
            .put("page", 1).put("size", 200)
            .put("searchRoleViewConstants", "VIEW_APPLICABLE_SHARE")
            .put("filterDateParams", JSONArray()
                .put(JSONObject().put("key", "minIssueOpenDate").put("condition", "").put("alias", "").put("value", ""))
                .put(JSONObject().put("key", "maxIssueCloseDate").put("condition", "").put("alias", "").put("value", "")))
        val r = request("/companyShare/applicableIssue/", body)
        val code = r.code
        val text = r.text()
        if (code !in 200..299) throw MeroShareException(message(code, text))
        return JSONObject(text).optJSONArray("object") ?: JSONArray()
    }

    fun issueDetail(companyShareId: Long): JSONObject = JSONObject(getJson("/active/$companyShareId"))

    /** The linked bank account to block money from: (bankId, account details). */
    fun bankAccount(bankHint: String?): Pair<Int, JSONObject> {
        val banks = JSONArray(getJson("/bank/"))
        if (banks.length() == 0) throw MeroShareException("No bank is linked to this MeroShare account.")
        var bank = banks.getJSONObject(0)
        if (!bankHint.isNullOrBlank()) {
            val hint = bankHint.lowercase()
            bank = (0 until banks.length()).map { banks.getJSONObject(it) }
                .firstOrNull { hint in it.optString("name").lowercase() || hint == it.optString("code") }
                ?: throw MeroShareException("Bank '$bankHint' isn't linked in MeroShare.")
        }
        val detailText = getJson("/bank/${bank.getInt("id")}")
        val detail = if (detailText.trim().startsWith("[")) JSONArray(detailText).optJSONObject(0) else JSONObject(detailText)
        return bank.getInt("id") to (detail ?: throw MeroShareException("Bank account details are empty."))
    }

    fun apply(payload: JSONObject): Pair<Boolean, String> {
        val r = request("/applicantForm/share/apply", payload)
        val code = r.code
        val text = r.text()
        return (code in 200..201) to message(code, text)
    }
}
