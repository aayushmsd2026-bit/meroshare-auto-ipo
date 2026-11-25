package np.ipohelper.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import np.ipohelper.core.Engine
import np.ipohelper.core.MeroShare
import np.ipohelper.core.Mode
import np.ipohelper.core.Person
import org.json.JSONArray
import org.json.JSONObject
import java.time.ZonedDateTime
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class IpoHelperApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Background.createChannel(this)
        Background.schedule(this)
    }
}

/** The app's screens are a small local web page (assets/index.html); this class connects it to the phone. */
class MainActivity : Activity() {
    private lateinit var web: WebView
    private val worker = Executors.newSingleThreadExecutor()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        setContentView(web)
        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false      // only the app's own bundled page
            allowContentAccess = false
        }
        web.webViewClient = object : WebViewClient() {
            // Links to other sites (e.g. MeroShare) open in the browser, never inside the app.
            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                val url = req.url.toString()
                if (url.startsWith("file:///android_asset/")) return false
                startActivity(Intent(Intent.ACTION_VIEW, req.url)); return true
            }
        }
        web.addJavascriptInterface(Bridge(), "IPO")
        val screen = intent.getStringExtra("screen") ?: "home"
        web.loadUrl("file:///android_asset/index.html#$screen")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra("screen")?.let { web.evaluateJavascript("location.hash='$it'; window.reload && reload();", null) }
    }

    override fun onResume() {
        super.onResume()
        if (::web.isInitialized) web.evaluateJavascript("window.reload && reload();", null)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        web.evaluateJavascript("window.goBack ? goBack() : false") { handled ->
            if (handled != "true") @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    override fun onDestroy() {
        worker.shutdown(); super.onDestroy()
    }

    private fun reply(id: String, result: JSONObject) = runOnUiThread {
        web.evaluateJavascript("window.onNative && onNative(${JSONObject.quote(id)}, ${result})", null)
    }

    /** Called from the page's JavaScript. Passwords and PINs go in, but never come back out. */
    inner class Bridge {
        private val store get() = Store(this@MainActivity)
        private val ctx get() = this@MainActivity

        @JavascriptInterface
        fun getData(): String {
            val state = store.state()
            val people = store.people()
            val pm = getSystemService(PowerManager::class.java)
            return JSONObject()
                .put("people", JSONArray(people.map { it.toPublicJson().put("loginFailed", state.loginFail.containsKey(it.id)) }))
                .put("state", state.toJson().apply { remove("notified") })
                .put("canNotify", Background.canNotify(ctx))
                .put("batteryUnrestricted", pm.isIgnoringBatteryOptimizations(packageName))
                .put("manufacturer", Build.MANUFACTURER)
                .put("version", packageManager.getPackageInfo(packageName, 0).versionName)
                .toString()
        }

        @JavascriptInterface
        fun dpList(): String = runCatching { assets.open("dps.json").bufferedReader().readText() }.getOrDefault("[]")

        @JavascriptInterface
        fun savePerson(json: String): String = guarded {
            val o = JSONObject(json)
            val people = store.people().toMutableList()
            val id = o.optString("id").ifEmpty { UUID.randomUUID().toString() }
            val old = people.firstOrNull { it.id == id }
            val p = Person(
                id = id,
                name = o.getString("name").trim(),
                dp = o.getString("dp").trim(),
                username = o.getString("username").trim(),
                password = o.optString("password").ifEmpty { old?.password ?: "" },
                pin = o.optString("pin").ifEmpty { old?.pin ?: "" },
                crn = o.getString("crn").trim(),
                bank = o.optString("bank").trim().ifEmpty { null },
                rightMaxKitta = o.optString("rightMaxKitta").toIntOrNull(),
                enabled = o.optBoolean("enabled", true),
            )
            require(p.password.isNotEmpty()) { "Enter the MeroShare password." }
            require(p.pin.length == 4) { "Enter the 4-digit PIN." }
            if (old == null) people += p else people[people.indexOf(old)] = p
            store.savePeople(people)
            clearLoginFail(id)
            Background.kick(ctx)
            JSONObject().put("ok", true).put("id", id)
        }

        @JavascriptInterface
        fun setPassword(id: String, password: String): String = guarded {
            require(password.isNotEmpty()) { "Enter the new password." }
            val people = store.people().map { if (it.id == id) it.copy(password = password) else it }
            store.savePeople(people)
            clearLoginFail(id)
            Background.kick(ctx)
            JSONObject().put("ok", true)
        }

        @JavascriptInterface
        fun removePerson(id: String): String = guarded {
            store.savePeople(store.people().filter { it.id != id })
            JSONObject().put("ok", true)
        }

        @JavascriptInterface
        fun setMode(mode: String): String = guarded {
            withState { it.mode = Mode.valueOf(mode) }
            Background.kick(ctx)
            JSONObject().put("ok", true)
        }

        /** Log in to everyone and look at open issues without applying. Replies through onNative(id, result). */
        @JavascriptInterface
        fun runCheck(callbackId: String) {
            worker.execute {
                val result = runCatching {
                    if (!Store.lock.tryLock(2, TimeUnit.MINUTES)) error("Another run is in progress. Try again in a minute.")
                    try {
                        val state = store.state()
                        val out = Engine().run(store.people(), state, ZonedDateTime.now(), dryRun = true)
                        store.saveState(state)
                        out.notices.forEach { Background.notify(ctx, it) }
                        JSONObject().put("ok", true).put("record", out.record!!.toJson())
                    } finally { Store.lock.unlock() }
                }.getOrElse { JSONObject().put("ok", false).put("error", it.message) }
                reply(callbackId, result)
            }
        }

        /** Fresh DP list straight from MeroShare (from this phone). */
        @JavascriptInterface
        fun refreshDps(callbackId: String) {
            worker.execute {
                val result = runCatching {
                    val list = MeroShare().capitals()
                    val out = JSONArray()
                    for (i in 0 until list.length()) list.getJSONObject(i).let { out.put(JSONObject().put("code", it.optString("code")).put("name", it.optString("name"))) }
                    JSONObject().put("ok", true).put("dps", out)
                }.getOrElse { JSONObject().put("ok", false).put("error", it.message) }
                reply(callbackId, result)
            }
        }

        @JavascriptInterface
        fun askNotifications() = runOnUiThread {
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            } else {
                startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
            }
        }

        @SuppressLint("BatteryLife")
        @JavascriptInterface
        fun askBattery() = runOnUiThread {
            runCatching {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            }.onFailure { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }

        @JavascriptInterface
        fun openAppSettings() = runOnUiThread {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }

        @JavascriptInterface
        fun testNotification() {
            Background.notify(ctx, np.ipohelper.core.Notice("test:${System.currentTimeMillis()}", "Notifications are on ✓",
                "IPO Helper will tell you here when it applies or needs you."))
        }

        private fun clearLoginFail(id: String) = withState { it.loginFail.remove(id) }

        private fun withState(change: (np.ipohelper.core.AppState) -> Unit) {
            Store.lock.lock()
            try { val s = store.state(); change(s); store.saveState(s) } finally { Store.lock.unlock() }
        }

        private fun guarded(block: () -> JSONObject): String =
            runCatching(block).getOrElse { JSONObject().put("ok", false).put("error", it.message ?: "Something went wrong") }.toString()
    }
}
