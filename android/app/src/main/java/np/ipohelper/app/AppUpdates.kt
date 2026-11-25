package np.ipohelper.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import np.ipohelper.core.Updater
import org.json.JSONObject
import java.io.File

/** Checks GitHub for a newer app version (at most every few hours) and installs it when you tap Update. */
object AppUpdates {
    private const val PREFS = "updates"
    private const val CHECK_EVERY_MS = 6 * 60 * 60 * 1000L

    fun currentCode(ctx: Context): Long = ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode
    fun currentName(ctx: Context): String = ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: ""

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** What the app knows: {current, currentName, available: {code, name} | null, checkedAt}. */
    fun info(ctx: Context): JSONObject {
        val p = prefs(ctx)
        val latest = p.getInt("code", 0)
        val o = JSONObject().put("current", currentCode(ctx)).put("currentName", currentName(ctx)).put("checkedAt", p.getLong("checkedAt", 0))
        if (latest > currentCode(ctx)) o.put("available", JSONObject().put("code", latest).put("name", p.getString("name", "")))
        return o
    }

    /** Ask GitHub for the latest version. With [force] = false this is skipped if it was checked recently. */
    fun check(ctx: Context, force: Boolean): JSONObject {
        val p = prefs(ctx)
        if (!force && System.currentTimeMillis() - p.getLong("checkedAt", 0) < CHECK_EVERY_MS) return info(ctx)
        val r = Updater().latest()
        p.edit().putLong("checkedAt", System.currentTimeMillis())
            .putInt("code", r?.versionCode ?: 0).putString("name", r?.versionName ?: "").apply()
        return info(ctx)
    }

    /** Android asks once whether this app may install updates. */
    fun canInstall(ctx: Context) = ctx.packageManager.canRequestPackageInstalls()

    fun openInstallPermission(ctx: Context) {
        ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Download the newest APK (checked against its SHA-256) and open Android's install screen. */
    fun downloadAndInstall(ctx: Context) {
        val updater = Updater()
        val r = updater.latest() ?: error("No update found.")
        if (r.versionCode <= currentCode(ctx)) error("You already have the latest version.")
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val apk = updater.download(r, File(dir, "ipo-helper.apk"))
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", apk)
        ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
