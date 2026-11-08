package io.github.makiiii_git.teslaspot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * 1 回分の「アクセス ポイントを ON（または OFF）にする」操作。
 *
 *   1. 設定アプリのアクセス ポイント画面を開き、スイッチを探して押す（最大 9 秒）
 *      見つからなければ「アクセス ポイントとテザリング」などの項目を 2 段までたどる
 *   2. だめならクイック設定を開き、タイルを押す（最大 5 秒）
 *   3. 押したあと目的の状態になったことを画面で確かめてから、元の画面に戻る
 *
 * 画面は 400ms ごとに読み直す（イベントだけに頼ると、取りこぼしたときに止まるため）。
 */
class HotspotFlow(
    private val service: AccessibilityService,
    /** true なら ON に、false なら OFF にする。 */
    private val turnOn: Boolean,
    private val onFinished: (Result) -> Unit,
) {
    enum class Result {
        /** スイッチを押して目的の状態にした */
        CHANGED,
        /** はじめから目的の状態だった */
        ALREADY,
        FAILED,
    }

    private val targetWord = if (turnOn) "ON" else "OFF"

    private enum class Phase { SETTINGS, QUICK_SETTINGS }

    private val handler = Handler(Looper.getMainLooper())
    private val prefs = Prefs(service)
    private val tick = Runnable { step() }

    private var phase = Phase.SETTINGS
    private var phaseStartedAt = 0L
    private var depth = 0
    private val navigated = mutableSetOf<String>()
    private var clicks = 0
    private var lastClickAt = 0L
    private var finished = false

    fun start() {
        if (launchHotspotSettings(service)) {
            enter(Phase.SETTINGS)
            schedule(900)
        } else {
            prefs.log("設定画面を開けなかったので、クイック設定を使います")
            enterQuickSettings()
        }
    }

    fun cancel() {
        finished = true
        handler.removeCallbacks(tick)
    }

    private fun enter(p: Phase) {
        phase = p
        phaseStartedAt = SystemClock.uptimeMillis()
        clicks = 0
        lastClickAt = 0L
    }

    private fun schedule(delayMs: Long = POLL_MS) {
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, delayMs)
    }

    private fun elapsed() = SystemClock.uptimeMillis() - phaseStartedAt

    private fun step() {
        if (finished) return
        when (phase) {
            Phase.SETTINGS -> stepSettings()
            Phase.QUICK_SETTINGS -> stepQuickSettings()
        }
    }

    private fun stepSettings() {
        if (elapsed() > SETTINGS_TIMEOUT_MS) {
            prefs.log("設定画面でスイッチが見つかりませんでした。クイック設定を試します")
            leaveSettings()
            handler.postDelayed({ if (!finished) enterQuickSettings() }, (depth + 2) * BACK_INTERVAL_MS)
            return
        }
        val root = rootOf { it.packageName?.toString()?.contains("settings") == true }
        if (root == null) {
            schedule(); return
        }
        when (val m = UiMatcher.scanSettings(root, prefs.customLabel, navigated, allowNavigate = depth < MAX_DEPTH)) {
            is UiMatcher.Match.Switch -> handleSwitch(m) { leaveSettings() }
            is UiMatcher.Match.Navigate -> {
                navigated += m.key
                depth++
                click(m.target)
                schedule(900)
            }
            UiMatcher.Match.None -> schedule()
        }
    }

    private fun enterQuickSettings() {
        enter(Phase.QUICK_SETTINGS)
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)
        schedule(700)
    }

    private fun stepQuickSettings() {
        if (elapsed() > QS_TIMEOUT_MS) {
            prefs.log("クイック設定にアクセス ポイントのタイルが見つかりませんでした")
            closeShade()
            finish(Result.FAILED); return
        }
        val roots = service.windows
            .filter { it.type == AccessibilityWindowInfo.TYPE_SYSTEM }
            .mapNotNull { it.root }
            .filter { it.packageName == "com.android.systemui" }
        for (root in roots) {
            val m = UiMatcher.scanQuickSettings(root, prefs.customLabel)
            if (m is UiMatcher.Match.Switch) {
                if (m.checked == null) {
                    // ON/OFF が読めないまま押すと、ON のものを OFF にしてしまう恐れがある
                    prefs.log("タイルの ON/OFF が読み取れないため、押しませんでした")
                    closeShade()
                    finish(Result.FAILED); return
                }
                handleSwitch(m) { closeShade() }
                return
            }
        }
        schedule()
    }

    /** スイッチの状態を見て、押す・確かめる・諦めるを決める。 */
    private fun handleSwitch(m: UiMatcher.Match.Switch, cleanup: () -> Unit) {
        if (m.checked == turnOn) {
            cleanup()
            finish(if (clicks > 0) Result.CHANGED else Result.ALREADY)
            return
        }
        val sinceClick = SystemClock.uptimeMillis() - lastClickAt
        when {
            clicks == 0 || (clicks < MAX_CLICKS && sinceClick > VERIFY_MS) -> {
                // 押す前に「目的と逆の状態」だと読めているので、押せば目的の状態になる
                click(m.target)
                clicks++
                lastClickAt = SystemClock.uptimeMillis()
                schedule(600)
            }
            sinceClick > VERIFY_MS -> {
                prefs.log("スイッチを押しましたが $targetWord になりませんでした")
                cleanup()
                finish(Result.FAILED)
            }
            else -> schedule()
        }
    }

    private fun finish(result: Result) {
        if (finished) return
        finished = true
        handler.removeCallbacks(tick)
        // 戻る操作が終わってから結果を知らせる
        handler.postDelayed({ onFinished(result) }, (depth + 2) * BACK_INTERVAL_MS)
    }

    /** 設定を「アプリの起動前の画面」まで戻す。 */
    private fun leaveSettings() {
        val backs = depth + 1
        for (i in 0 until backs) {
            handler.postDelayed({
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            }, (i + 1) * BACK_INTERVAL_MS)
        }
    }

    private fun closeShade() {
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
    }

    private fun rootOf(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        service.rootInActiveWindow?.let { if (predicate(it)) return it }
        return service.windows
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .mapNotNull { it.root }
            .firstOrNull(predicate)
    }

    /** まずはふつうにクリックし、効かない部品なら画面上の位置をタップする。 */
    private fun click(node: AccessibilityNodeInfo) {
        val target = UiMatcher.clickableSelfOrAncestor(node) ?: node
        if (target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
        val r = Rect().also { node.getBoundsInScreen(it) }
        if (r.isEmpty) return
        val path = Path().apply { moveTo(r.exactCenterX(), r.exactCenterY()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
            .build()
        service.dispatchGesture(gesture, null, null)
    }

    companion object {
        private const val POLL_MS = 400L
        private const val SETTINGS_TIMEOUT_MS = 9_000L
        private const val QS_TIMEOUT_MS = 5_000L
        private const val VERIFY_MS = 2_500L
        private const val BACK_INTERVAL_MS = 300L
        private const val MAX_CLICKS = 2
        private const val MAX_DEPTH = 2

        /**
         * アクセス ポイントの画面を開く。機種によって入口が違うので、近いものから順に試す。
         * 新しいタスクとして開き直すので、戻る操作でそのまま元のアプリに帰れる。
         */
        fun launchHotspotSettings(context: Context): Boolean {
            val flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or
                Intent.FLAG_ACTIVITY_NO_ANIMATION
            val candidates = listOf(
                Intent("com.android.settings.WIFI_TETHER_SETTINGS"),
                Intent().setComponent(ComponentName(SETTINGS_PKG, "$SETTINGS_PKG.Settings\$WifiTetherSettingsActivity")),
                Intent().setComponent(ComponentName(SETTINGS_PKG, "$SETTINGS_PKG.TetherSettings")),
                Intent().setComponent(ComponentName(SETTINGS_PKG, "$SETTINGS_PKG.Settings\$TetherSettingsActivity")),
                Intent(Settings.ACTION_WIRELESS_SETTINGS),
            )
            for (intent in candidates) {
                try {
                    context.startActivity(intent.addFlags(flags))
                    return true
                } catch (_: ActivityNotFoundException) {
                } catch (_: SecurityException) {
                }
            }
            return false
        }

        private const val SETTINGS_PKG = "com.android.settings"
    }
}
