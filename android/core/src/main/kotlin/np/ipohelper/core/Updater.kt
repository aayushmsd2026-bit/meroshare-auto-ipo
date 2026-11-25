package np.ipohelper.core

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Finds and downloads new versions of the app from this project's GitHub releases.
 * Android itself then checks the new APK is signed with the same key before installing it.
 */
class Updater(
    private val repoApi: String = "https://api.github.com/repos/aayushmsd2026-bit/meroshare-auto-ipo",
    private val http: OkHttpClient = MeroShare.defaultClient(),
) {
    data class Release(val versionCode: Int, val versionName: String, val apkUrl: String, val shaUrl: String?)

    companion object {
        private val TAG = Regex("""^app-v(\d+)\.(\d+)\.(\d+)$""")
        /** Release tags look like app-v1.0.<build number>; the build number is the app's versionCode. */
        fun versionCodeOf(tag: String): Int? = TAG.matchEntire(tag)?.groupValues?.get(3)?.toIntOrNull()
    }

    private fun get(url: String, accept: String = "application/vnd.github+json"): okhttp3.Response =
        http.newCall(Request.Builder().url(url).header("Accept", accept).header("User-Agent", MeroShare.USER_AGENT).build()).execute()

    /** The newest published version, or null if there is none. */
    fun latest(): Release? {
        get("$repoApi/releases/latest").use { r ->
            if (r.code == 404) return null
            if (!r.isSuccessful) throw IOException("GitHub answered ${r.code}")
            val o = JSONObject(r.body!!.string())
            val tag = o.optString("tag_name")
            val code = versionCodeOf(tag) ?: return null
            val assets = o.optJSONArray("assets") ?: return null
            var apk: String? = null
            var sha: String? = null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                when (a.optString("name")) {
                    "ipo-helper.apk" -> apk = a.optString("browser_download_url")
                    "ipo-helper.apk.sha256" -> sha = a.optString("browser_download_url")
                }
            }
            return apk?.let { Release(code, tag.removePrefix("app-v"), it, sha) }
        }
    }

    /** Download the APK to [dest] and check it against the published SHA-256. */
    fun download(release: Release, dest: File): File {
        val expected = release.shaUrl?.let { url ->
            get(url, "application/octet-stream").use { r ->
                if (!r.isSuccessful) throw IOException("Couldn't get the checksum (${r.code})")
                r.body!!.string().trim().split(Regex("\\s+")).first().lowercase()
            }
        }
        dest.parentFile?.mkdirs()
        val tmp = File(dest.path + ".part")
        val digest = MessageDigest.getInstance("SHA-256")
        get(release.apkUrl, "application/octet-stream").use { r ->
            if (!r.isSuccessful) throw IOException("Download failed (${r.code})")
            r.body!!.byteStream().use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        digest.update(buf, 0, n)
                        out.write(buf, 0, n)
                    }
                }
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (expected != null && expected != actual) {
            tmp.delete()
            throw IOException("The download was damaged (checksum mismatch). Please try again.")
        }
        if (dest.exists()) dest.delete()
        if (!tmp.renameTo(dest)) throw IOException("Couldn't save the update")
        return dest
    }
}
