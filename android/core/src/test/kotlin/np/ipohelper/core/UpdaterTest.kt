package np.ipohelper.core

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class UpdaterTest {
    private lateinit var server: MockWebServer
    private val apk = ByteArray(200_000) { (it % 251).toByte() }
    private var sha = MessageDigest.getInstance("SHA-256").digest(apk).joinToString("") { "%02x".format(it) }
    private var noRelease = false

    @BeforeTest fun start() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/repo/releases/latest" -> if (noRelease) MockResponse().setResponseCode(404) else MockResponse().setBody(
                    JSONObject().put("tag_name", "app-v1.0.7").put("assets", JSONArray()
                        .put(JSONObject().put("name", "ipo-helper.apk").put("browser_download_url", server.url("/dl/apk").toString()))
                        .put(JSONObject().put("name", "ipo-helper.apk.sha256").put("browser_download_url", server.url("/dl/sha").toString()))
                    ).toString())
                "/dl/apk" -> MockResponse().setResponseCode(302).setHeader("Location", server.url("/cdn/apk"))   // like GitHub's redirect
                "/cdn/apk" -> MockResponse().setBody(Buffer().write(apk))
                "/dl/sha" -> MockResponse().setBody("$sha  ipo-helper.apk\n")
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
    }

    @AfterTest fun stop() = server.shutdown()

    private fun updater() = Updater(server.url("/repo").toString())

    @Test fun `finds the latest release and its version`() {
        val r = updater().latest()!!
        assertEquals(7, r.versionCode)
        assertEquals("1.0.7", r.versionName)
    }

    @Test fun `downloads and verifies the apk`() {
        val dest = File(Files.createTempDirectory("upd").toFile(), "ipo-helper.apk")
        updater().download(updater().latest()!!, dest)
        assertContentEquals(apk, dest.readBytes())
    }

    @Test fun `rejects a damaged download`() {
        sha = "0".repeat(64)
        val dest = File(Files.createTempDirectory("upd").toFile(), "ipo-helper.apk")
        assertFailsWith<IOException> { updater().download(updater().latest()!!, dest) }
        assertFalse(dest.exists())
    }

    @Test fun `no release yet`() {
        noRelease = true
        assertNull(updater().latest())
    }

    @Test fun `version codes from tags`() {
        assertEquals(12, Updater.versionCodeOf("app-v1.0.12"))
        assertNull(Updater.versionCodeOf("v1.0"))
    }
}
