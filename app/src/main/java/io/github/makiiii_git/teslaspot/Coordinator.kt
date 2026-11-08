package io.github.makiiii_git.teslaspot

import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager

/**
 * 「車につながった」「ロックが解除された」の 2 つのきっかけを受け取り、
 * 条件が揃ったときだけユーザー補助サービスにスイッチ操作を頼む。
 *
 *   車と接続中  かつ  この接続ではまだ ON にしていない  かつ  ロック解除済み
 *     → 設定を開いてスイッチを押す
 *
 * ロック中は他のアプリの画面を操作できないので、通知を出して解除を待つ。
 */
object Coordinator {

    private const val DUPLICATE_WINDOW_MS = 60_000L

    fun onCarConnected(context: Context, source: String) {
        val prefs = Prefs(context)
        val now = System.currentTimeMillis()
        if (prefs.carPresent && now - prefs.connectedAt < DUPLICATE_WINDOW_MS) {
            // CDM と Bluetooth の両方から、ほぼ同時に届くことがある。2 回目は無視する。
            return
        }
        // 時間が空いていれば、切断の知らせを取りこぼしただけとみなして新しい接続として扱う
        prefs.connectedAt = now
        prefs.carPresent = true
        prefs.doneThisConnection = false
        prefs.log("車に接続しました（$source）")
        tryRun(context, "接続")
    }

    fun onCarDisconnected(context: Context, source: String) {
        val prefs = Prefs(context)
        if (!prefs.carPresent) return
        prefs.carPresent = false
        prefs.doneThisConnection = false
        prefs.log("車から切断しました（$source）")
        Notifier.cancel(context)
        if (prefs.enabled && prefs.turnOffOnDisconnect) turnOffAfterDisconnect(context)
    }

    /**
     * 切断したときに OFF にする（設定で ON のときだけ）。
     * ロック中はスイッチを押せないので何もしない。その場合は、つながっている機器が
     * 無くなったときの Android の自動 OFF に任せる。
     */
    private fun turnOffAfterDisconnect(context: Context) {
        val prefs = Prefs(context)
        val service = HotspotAccessibilityService.instance
        when {
            service == null -> prefs.log("ユーザー補助が OFF のため、OFF にできません")
            isLocked(context) -> prefs.log("ロック中のため、Android の自動 OFF に任せます")
            else -> service.runFlow(manual = false, turnOn = false)
        }
    }

    /** 条件が揃っていれば実行する。揃っていなければ何もしない（何度呼んでもよい）。 */
    fun tryRun(context: Context, reason: String) {
        val prefs = Prefs(context)
        if (!prefs.enabled || !prefs.carPresent || prefs.doneThisConnection) return

        val service = HotspotAccessibilityService.instance
        if (service == null) {
            prefs.log("ユーザー補助が OFF のため実行できません（$reason）")
            Notifier.showServiceOff(context)
            return
        }
        if (isLocked(context)) {
            prefs.log("ロック中のため、解除されるのを待ちます")
            Notifier.showWaitingForUnlock(context)
            return
        }
        service.runFlow(manual = false)
    }

    fun isLocked(context: Context): Boolean {
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        val power = context.getSystemService(PowerManager::class.java)
        return keyguard.isKeyguardLocked || !power.isInteractive
    }

    /** 自動・手動どちらの実行でも、結果はここに集める。 */
    fun onFlowFinished(context: Context, result: HotspotFlow.Result, manual: Boolean, turnOn: Boolean) {
        val prefs = Prefs(context)
        val word = if (turnOn) "ON" else "OFF"
        when (result) {
            HotspotFlow.Result.CHANGED -> prefs.log("アクセス ポイントを $word にしました")
            HotspotFlow.Result.ALREADY -> prefs.log("アクセス ポイントはすでに $word でした")
            HotspotFlow.Result.FAILED -> prefs.log("$word にできませんでした")
        }
        if (!turnOn) {
            // OFF にできなくても Android の自動 OFF があるので、通知でわずらわせない
            return
        }
        if (result == HotspotFlow.Result.FAILED) {
            Notifier.showFailed(context)
        } else {
            Notifier.cancel(context)
        }
        // 自動実行は、成功しても失敗しても、この接続のあいだは繰り返さない。
        // 失敗したときに、ロック解除のたびに設定画面が開くのを防ぐため。
        if (!manual && prefs.carPresent) prefs.doneThisConnection = true
    }
}
