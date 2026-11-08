package io.github.makiiii_git.teslaspot

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * GitHub Releases に新しい版があるかを調べるだけ。
 *
 * ダウンロードとインストールはアプリ内では行わず、ブラウザで Releases のページを開く。
 * 「ユーザー補助」と「APK を取ってきて自分で入れる機能」が同じアプリにあると、
 * 他のアプリを送り込むマルウェアと似た形になり、Play プロテクトに有害と判定されるため
 * （v0.3.0 で実際に判定された）。
 *
 * 通信するのは、アプリの画面を開いたとき（12 時間に 1 回まで）と、ボタンを押したときだけ。
 */
object Updater {

    private const val API_LATEST = "https://api.github.com/repos/makiiii-git/TeslaSpot/releases/latest"
    const val RELEASES_PAGE = "https://github.com/makiiii-git/TeslaSpot/releases/latest"
    private const val AUTO_CHECK_INTERVAL_MS = 12 * 60 * 60 * 1000L

    data class Release(
        val version: String,
        val notes: String,
        /** ブラウザで開くリリースのページ。 */
        val pageUrl: String,
    )

    sealed interface State {
        data object Checking : State
        data object UpToDate : State
        data class Available(val release: Release) : State
        data class Failed(val message: String) : State
    }

    private val running = AtomicBoolean(false)
    private val main = Handler(Looper.getMainLooper())

    val currentVersion: String get() = BuildConfig.VERSION_NAME

    fun shouldAutoCheck(context: Context): Boolean {
        val last = context.getSharedPreferences("updater", Context.MODE_PRIVATE).getLong("last_check", 0L)
        return System.currentTimeMillis() - last > AUTO_CHECK_INTERVAL_MS
    }

    fun check(context: Context, onState: (State) -> Unit) {
        if (!running.compareAndSet(false, true)) return
        val app = context.applicationContext
        onState(State.Checking)
        Thread {
            val state = try {
                val release = fetchLatest()
                app.getSharedPreferences("updater", Context.MODE_PRIVATE).edit()
                    .putLong("last_check", System.currentTimeMillis()).apply()
                when {
                    release == null -> State.Failed("リリースが見つかりませんでした")
                    compareVersions(release.version, currentVersion) > 0 -> State.Available(release)
                    else -> State.UpToDate
                }
            } catch (e: Exception) {
                State.Failed("確認できませんでした（${e.javaClass.simpleName}）")
            } finally {
                running.set(false)
            }
            main.post { onState(state) }
        }.start()
    }

    private fun fetchLatest(): Release? {
        val conn = (URL(API_LATEST).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", "TeslaSpot-Updater")
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        return parseRelease(body)
    }

    fun parseRelease(body: String): Release? {
        val json = JSONObject(body)
        if (json.optBoolean("draft") || json.optBoolean("prerelease")) return null
        val tag = json.optString("tag_name").takeIf { it.isNotEmpty() } ?: return null
        return Release(
            version = tag.removePrefix("v"),
            notes = json.optString("body"),
            pageUrl = json.optString("html_url").ifEmpty { RELEASES_PAGE },
        )
    }

    /** "1.2.10" と "1.2.9" のような版を数値で比べる。 */
    fun compareVersions(a: String, b: String): Int {
        val pa = a.split('.', '-').map { it.toIntOrNull() ?: 0 }
        val pb = b.split('.', '-').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val c = (pa.getOrElse(i) { 0 }).compareTo(pb.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }
}
