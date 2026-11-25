package np.ipohelper.core

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Runs the engine against a fake MeroShare server. */
class EngineTest {
    private val day = LocalDate.of(2026, 11, 26)          // the "today" of these tests
    private fun at(h: Int, m: Int = 0, d: LocalDate = day) = ZonedDateTime.of(d, LocalTime.of(h, m), NEPAL)
    private fun meroDate(d: LocalDate) = d.format(DateTimeFormatter.ofPattern("MMM dd, yyyy", Locale.ENGLISH)) + " 5:00:00 PM"

    private lateinit var server: MockWebServer
    private val applied = mutableListOf<JSONObject>()
    private var authCalls = 0
    private var failNetwork = false
    private var passwordExpiry: String? = null

    private fun issue(id: Int, scrip: String, type: String = "IPO", group: String = "Ordinary Shares",
                      sub: String = "For General Public", close: LocalDate = day, action: String? = null) =
        JSONObject().put("companyShareId", id).put("scrip", scrip).put("companyName", "$scrip Ltd")
            .put("shareTypeName", type).put("shareGroupName", group).put("subGroup", sub)
            .put("statusName", "CREATE_APPROVE").put("issueCloseDate", meroDate(close)).putOpt("action", action)

    private val issues = JSONArray()
        .put(issue(1, "GOOD"))
        .put(issue(2, "LATER", close = day.plusDays(1)))
        .put(issue(3, "MF1", group = "Mutual Fund"))
        .put(issue(4, "DEB", group = "Corporate Debenture"))
        .put(issue(5, "FE", sub = "For Nepalese Citizens Working Abroad"))
        .put(issue(6, "RGT", type = "RESERVED (RIGHT SHARE)", sub = "RIGHT SHARE"))
        .put(issue(7, "DONE", action = "edit"))
        .put(issue(9, "FPOX", type = "FPO"))

    @BeforeTest fun start() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (failNetwork) return MockResponse().setResponseCode(503)
                val path = request.path!!.removePrefix("/api/meroShare")
                val json = { body: Any -> MockResponse().setHeader("Content-Type", "application/json").setBody(body.toString()) }
                return when {
                    path == "/capital/" -> json(JSONArray().put(JSONObject().put("id", 128).put("code", "13700").put("name", "NIC ASIA CAPITAL")))
                    path == "/auth/" -> {
                        authCalls++
                        assertEquals(MeroShare.USER_AGENT, request.getHeader("User-Agent"))
                        val pw = JSONObject(request.body.readUtf8()).getString("password")
                        if (pw != "ok") MockResponse().setResponseCode(401).setBody("""{"message":"Invalid credentials"}""")
                        else json(JSONObject().putOpt("passwordExpiryDate", passwordExpiry)).setHeader("Authorization", "tok")
                    }
                    request.getHeader("Authorization") != "tok" -> MockResponse().setResponseCode(401)
                    path == "/ownDetail/" -> json(JSONObject().put("demat", "130").put("boid", "1301"))
                    path == "/companyShare/applicableIssue/" -> json(JSONObject().put("object", issues))
                    path == "/active/6" -> json(JSONObject().put("minUnit", 1).put("maxUnit", 345).put("sharePerUnit", 100))
                    path.startsWith("/active/") -> json(JSONObject().put("minUnit", 10).put("maxUnit", 50000).put("sharePerUnit", 100))
                    path == "/bank/" -> json(JSONArray().put(JSONObject().put("id", 44).put("code", "1").put("name", "Nabil Bank")))
                    path == "/bank/44" -> json(JSONArray().put(JSONObject().put("id", 9).put("accountNumber", "000").put("accountBranchId", 3).put("accountTypeId", 1)))
                    path == "/applicantForm/share/apply" -> {
                        applied += JSONObject(request.body.readUtf8())
                        json("""{"message":"Share has been applied successfully."}""").setResponseCode(201)
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    @AfterTest fun stop() = server.shutdown()

    private fun engine() = Engine(newClient = { MeroShare(server.url("/api/meroShare").toString()) }, pause = {})
    private val me = Person("p1", "Me", "13700", "u1", "ok", "1234", "CRN1")
    private val dad = Person("p2", "Dad", "13700", "u2", "wrong", "4321", "CRN2")
    private fun appliedSet() = applied.map { it.getString("companyShareId") to it.getString("appliedKitta") }.toSet()

    @Test fun `applies due issues on the last day and skips the rest`() {
        val st = AppState()
        val out = engine().tick(listOf(me), st, at(9, 30))
        assertEquals(setOf("1" to "10", "6" to "345", "9" to "10"), appliedSet())
        assertEquals("applied", st.status["p1"]!!["GOOD"]!!.state)
        assertEquals("wait", st.status["p1"]!!["LATER"]!!.state)
        assertEquals("skip", st.status["p1"]!!["MF1"]!!.state)
        assertEquals("skip", st.status["p1"]!!["DEB"]!!.state)
        assertEquals("skip", st.status["p1"]!!["FE"]!!.state)
        assertEquals("done", st.status["p1"]!!["DONE"]!!.state)
        val n = out.notices.single()
        assertTrue(n.title.startsWith("Applied:") && "GOOD" in n.title && "RGT" in n.title, n.title)
        assertTrue("Rs 34,500" in n.body, n.body)
        // the payload carries the person's PIN and CRN
        assertEquals("1234", applied.first().getString("transactionPIN"))
        assertEquals("CRN1", applied.first().getString("crnNumber"))

        // An hour later nothing is pending: no new run, no new notifications.
        val again = engine().tick(listOf(me), st, at(10, 30))
        assertEquals(null, again.record)
        assertTrue(again.notices.isEmpty())
    }

    @Test fun `does nothing outside 9 AM to 4-45 PM`() {
        val st = AppState()
        assertEquals(null, engine().tick(listOf(me), st, at(7, 0)).record)
        assertEquals(null, engine().tick(listOf(me), st, at(16, 50)).record)
        assertEquals(0, authCalls)
    }

    @Test fun `a check never applies`() {
        val st = AppState()
        val out = engine().run(listOf(me), st, at(11), dryRun = true)
        assertTrue(applied.isEmpty())
        assertTrue(out.record!!.applied.all { "test, not applied" in it })
        assertEquals("Check finished ✓", out.notices.single().title)
    }

    @Test fun `wrong password is reported once and not retried`() {
        val st = AppState()
        val out = engine().tick(listOf(me, dad), st, at(9, 30))
        assertTrue(out.notices.any { it.title == "IPO Helper: problem" && "Dad" in it.body && "Invalid credentials" in it.body })
        assertEquals(2, authCalls)
        // Later ticks: Me has nothing pending, Dad is not retried (protects the account from lockout)
        engine().tick(listOf(me, dad), st, at(10, 30))
        engine().tick(listOf(me, dad), st, at(11, 30))
        assertEquals(2, authCalls)
    }

    @Test fun `network problems are retried on the next tick`() {
        val st = AppState()
        failNetwork = true
        val out = engine().tick(listOf(me), st, at(9, 30))
        assertTrue(st.retry)
        assertTrue(out.record!!.problems.single().contains("Couldn't reach MeroShare"))
        failNetwork = false
        engine().tick(listOf(me), st, at(10, 30))
        assertEquals(setOf("1" to "10", "6" to "345", "9" to "10"), appliedSet())
        assertFalse(st.retry)
    }

    @Test fun `evening heads-up for tomorrow, once`() {
        val st = AppState()
        engine().tick(listOf(me), st, at(9, 30))
        val eve = engine().tick(listOf(me), st, at(18, 30))
        val n = eve.notices.single()
        assertEquals("Applying for LATER tomorrow", n.title)
        assertTrue(engine().tick(listOf(me), st, at(19, 30)).notices.isEmpty())
    }

    @Test fun `2 PM warning when something due today is still not applied`() {
        val st = AppState()
        failNetwork = true
        engine().tick(listOf(me), st, at(9, 30))           // fails, nothing applied
        // statuses come from an earlier successful look (yesterday's run saw GOOD as "wait")
        st.issues["GOOD"] = IssueInfo("GOOD", "GOOD Ltd", "IPO", "For General Public", day.toString(), "IPO", null)
        st.status.getOrPut("p1") { linkedMapOf() }["GOOD"] = IssueStatus("wait", "will apply", at = "x")
        val out = engine().tick(listOf(me), st, at(14, 5))
        assertTrue(out.notices.any { it.title == "GOOD closes today at 5 PM" }, out.notices.toString())
    }

    @Test fun `day-before mode applies one day early`() {
        val st = AppState(mode = Mode.DAY_BEFORE)
        engine().tick(listOf(me), st, at(9, 30))
        assertTrue("2" to "10" in appliedSet())           // LATER closes tomorrow
    }

    @Test fun `password expiry warning`() {
        passwordExpiry = day.plusDays(5).toString()
        val st = AppState()
        val out = engine().tick(listOf(me), st, at(9, 30))
        val w = out.notices.single { it.title == "MeroShare: action needed" }
        assertTrue("MeroShare password expires in 5 days" in w.body, w.body)
    }

    @Test fun `state survives save and load`() {
        val st = AppState()
        engine().tick(listOf(me), st, at(9, 30))
        val back = AppState.fromJson(st.toJson().toString())
        assertEquals(st.toJson().toString(), back.toJson().toString())
        assertEquals("applied", back.status["p1"]!!["GOOD"]!!.state)
    }

    @Test fun `rules`() {
        assertEquals("IPO", Rules.classify("IPO", "Ordinary Shares", "For General Public", "X").first)
        assertEquals(null, Rules.classify("IPO", "Mutual Fund", "", "X Scheme").first)
        assertEquals(LocalDate.of(2026, 11, 26), Rules.parseDate("Nov 26, 2026 5:00:00 PM"))
        assertEquals(LocalDate.of(2026, 11, 26), Rules.parseDate("2026-11-26"))
        assertTrue(Rules.isDue(day, day, Mode.LAST_DAY))
        assertFalse(Rules.isDue(day.plusDays(1), day, Mode.LAST_DAY))
        assertTrue(Rules.isDue(day.plusDays(1), day, Mode.DAY_BEFORE))
    }
}
