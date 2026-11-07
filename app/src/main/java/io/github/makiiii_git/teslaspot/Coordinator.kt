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
    fun onFlowFinished(context: Context, result: HotspotFlow.Result, manual: Boolean) {
        val prefs = Prefs(context)
        when (result) {
            HotspotFlow.Result.TURNED_ON -> prefs.log("アクセス ポイントを ON にしました")
            HotspotFlow.Result.ALREADY_ON -> prefs.log("アクセス ポイントはすでに ON でした")
            HotspotFlow.Result.FAILED -> prefs.log("スイッチが見つからず、ON にできませんでした")
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
