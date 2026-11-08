package io.github.makiiii_git.teslaspot

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * GitHub Releases からの自己更新。
 *
 *   1. releases/latest を読み、自分より新しい版があるか調べる
 *   2. APK をダウンロードしながら PackageInstaller のセッションに書き込む
 *   3. SHA-256 が GitHub の値（asset の digest、無ければ SHA256SUMS.txt）と一致したら確定
 *      一致しなければセッションを破棄する（途中で壊れた・すり替えられた APK は入れない）
 *   4. 確認画面が出たら「更新」を押してもらう
 *
 * 署名が違う APK は Android 自体が受け付けないので、別の鍵で作られた APK に
 * 置き換わることはない。
 *
 * 通信するのは、アプリの画面を開いたとき（12 時間に 1 回まで）と、ボタンを押したときだけ。
 */
object Updater {

    private const val API_LATEST = "https://api.github.com/repos/makiiii-git/TeslaSpot/releases/latest"
    private const val AUTO_CHECK_INTERVAL_MS = 12 * 60 * 60 * 1000L
    private const val ACTION_INSTALL_RESULT = "io.github.makiiii_git.teslaspot.INSTALL_RESULT"

    data class Release(
        val version: String,
        val notes: String,
        val apkUrl: String,
        val apkSize: Long,
        /** 16 進の SHA-256。分からなければ空。 */
        val sha256: String,
        /** 同じリリースの SHA256SUMS.txt。無ければ空。 */
        val sumsUrl: String,
    )

    sealed interface State {
        data object Checking : State
        data object UpToDate : State
        data class Available(val release: Release) : State
        data class Downloading(val percent: Int) : State
        data object WaitingForUser : State
        data class Failed(val message: String) : State
    }

    private val running = AtomicBoolean(false)
    private val main = Handler(Looper.getMainLooper())

    val currentVersion: String get() = BuildConfig.VERSION_NAME

    // ---- 確認 -------------------------------------------------------------------

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

    // ---- ダウンロードとインストール ---------------------------------------------------

    fun downloadAndInstall(context: Context, release: Release, onState: (State) -> Unit) {
        if (!running.compareAndSet(false, true)) return
        val app = context.applicationContext
        onState(State.Downloading(0))
        Thread {
            val installer = app.packageManager.packageInstaller
            var sessionId = -1
            try {
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                    setAppPackageName(app.packageName)
                    // 2 回目以降（このアプリ自身が入れた版の更新）は確認なしで入ることがある
                    setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                    if (release.apkSize > 0) setSize(release.apkSize)
                }
                sessionId = installer.createSession(params)
                val expected = release.sha256.ifEmpty { fetchShaFromSums(release) }
                if (expected.isEmpty()) error("チェックサムが取得できません")

                val digest = MessageDigest.getInstance("SHA-256")
                installer.openSession(sessionId).use { session ->
                    val conn = open(release.apkUrl)
                    val total = conn.contentLengthLong.takeIf { it > 0 } ?: release.apkSize
                    conn.inputStream.use { input ->
                        session.openWrite("base.apk", 0, total.coerceAtLeast(-1)).use { out ->
                            val buf = ByteArray(64 * 1024)
                            var done = 0L
                            var lastPercent = -1
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                digest.update(buf, 0, n)
                                done += n
                                if (total > 0) {
                                    val p = (done * 100 / total).toInt()
                                    if (p != lastPercent) {
                                        lastPercent = p
                                        main.post { onState(State.Downloading(p)) }
                                    }
                                }
                            }
                            session.fsync(out)
                        }
                    }
                    val actual = digest.digest().joinToString("") { "%02x".format(it) }
                    if (!actual.equals(expected, ignoreCase = true)) {
                        session.abandon()
                        error("ダウンロードした APK が壊れています（チェックサム不一致）")
                    }
                    val callback = PendingIntent.getBroadcast(
                        app,
                        sessionId,
                        Intent(ACTION_INSTALL_RESULT).setPackage(app.packageName),
                        PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    )
                    session.commit(callback.intentSender)
                }
                Prefs(app).log("v${release.version} をダウンロードしました")
                main.post { onState(State.WaitingForUser) }
            } catch (e: Exception) {
                if (sessionId >= 0) runCatching { installer.abandonSession(sessionId) }
                val msg = e.message ?: e.javaClass.simpleName
                Prefs(app).log("アップデートに失敗: $msg")
                main.post { onState(State.Failed(msg)) }
            } finally {
                running.set(false)
            }
        }.start()
    }

    /** インストールの結果を受け取る。確認が必要ならシステムの確認画面を出す。 */
    class InstallResultReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
            when (status) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    val confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java) ?: return
                    context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                PackageInstaller.STATUS_SUCCESS -> Unit // 新しい版が起動し直す
                else -> {
                    val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "不明"
                    val hint = if (status == PackageInstaller.STATUS_FAILURE_CONFLICT ||
                        status == PackageInstaller.STATUS_FAILURE_INCOMPATIBLE
                    ) {
                        "（署名が違う可能性があります）"
                    } else {
                        ""
                    }
                    Prefs(context).log("インストールできませんでした$hint: $message")
                }
            }
        }
    }

    // ---- GitHub API ----------------------------------------------------------------

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "TeslaSpot-Updater")
            setRequestProperty("Accept", "application/vnd.github+json")
            if (responseCode !in 200..299) error("HTTP $responseCode")
        }

    private fun fetchLatest(): Release? {
        val body = open(API_LATEST).inputStream.bufferedReader().use { it.readText() }
        return parseRelease(body)
    }

    fun parseRelease(body: String): Release? {
        val json = JSONObject(body)
        if (json.optBoolean("draft") || json.optBoolean("prerelease")) return null
        val assets = json.optJSONArray("assets") ?: return null
        var apk: JSONObject? = null
        var sumsUrl = ""
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            val name = a.optString("name")
            when {
                name.endsWith(".apk") -> apk = a
                name == "SHA256SUMS.txt" -> sumsUrl = a.optString("browser_download_url")
            }
        }
        apk ?: return null
        val digest = apk.optString("digest")
        return Release(
            version = json.optString("tag_name").removePrefix("v"),
            notes = json.optString("body"),
            apkUrl = apk.optString("browser_download_url"),
            apkSize = apk.optLong("size", -1L),
            sha256 = if (digest.startsWith("sha256:")) digest.removePrefix("sha256:") else "",
            sumsUrl = sumsUrl,
        )
    }

    /** asset に digest が無いときは、同じリリースの SHA256SUMS.txt から読む。 */
    private fun fetchShaFromSums(release: Release): String {
        if (release.sumsUrl.isEmpty()) return ""
        val apkName = release.apkUrl.substringAfterLast('/')
        val text = open(release.sumsUrl).inputStream.bufferedReader().use { it.readText() }
        return text.lines()
            .map { it.trim().split(Regex("\\s+")) }
            .firstOrNull { it.size >= 2 && it[1].trimStart('*') == apkName }
            ?.get(0) ?: ""
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
