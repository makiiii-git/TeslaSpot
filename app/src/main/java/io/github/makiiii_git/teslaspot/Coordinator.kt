package io.github.makiiii_git.teslaspot

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.PowerManager

/**
 * 「車につながった」「ロックが解除された」の 2 つのきっかけを受け取り、
 * 条件が揃ったときだけユーザー補助サービスにスイッチ操作を頼む。
 *
 *   車と接続中  かつ  この接続ではまだ ON にしていない  かつ  ロック解除済み
 *     → 設定を開いてスイッチを押す
 *
 * ロック中は他のアプリの画面を操作できないので、通知を出して解除を待つ。
 *
 * 「必要なときだけ ON」（OnDemandA11y）のときは、普段ユーザー補助は OFF。
 * 操作する直前に ON にして、サービスが起動したら操作し、終わったら OFF に戻す。
 */
object Coordinator {

    private const val DUPLICATE_WINDOW_MS = 60_000L
    private const val SERVICE_START_TIMEOUT_MS = 8_000L
    private const val UNLOCK_DELAY_MS = 800L
    private const val RELEASE_DELAY_MS = 1_000L

    private val handler = Handler(Looper.getMainLooper())

    /** ユーザー補助の起動を待っている操作。 */
    private data class Pending(val turnOn: Boolean, val manual: Boolean)

    private var pending: Pending? = null
    private var startTimeout: Runnable? = null

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
        UnlockWatcher.stop(context)
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
        when {
            !canOperate(context) -> prefs.log("ユーザー補助が OFF のため、OFF にできません")
            isLocked(context) -> prefs.log("ロック中のため、Android の自動 OFF に任せます")
            else -> request(context, turnOn = false, manual = false)
        }
    }

    /** 条件が揃っていれば実行する。揃っていなければ何もしない（何度呼んでもよい）。 */
    fun tryRun(context: Context, reason: String) {
        val prefs = Prefs(context)
        if (!prefs.enabled || !prefs.carPresent || prefs.doneThisConnection) return

        if (!canOperate(context)) {
            prefs.log("ユーザー補助が OFF のため実行できません（$reason）")
            Notifier.showServiceOff(context)
            return
        }
        if (isLocked(context)) {
            prefs.log("ロック中のため、解除されるのを待ちます")
            Notifier.showWaitingForUnlock(context)
            // ユーザー補助が常駐していれば、サービス側でロック解除を受け取る
            if (HotspotAccessibilityService.instance == null) UnlockWatcher.start(context)
            return
        }
        UnlockWatcher.stop(context)
        request(context, turnOn = true, manual = false)
    }

    /** ユーザー補助が常駐しているか、必要なときに ON にできるか。 */
    fun canOperate(context: Context): Boolean =
        HotspotAccessibilityService.instance != null || OnDemandA11y.isActive(context)

    /**
     * アクセス ポイントの操作を頼む。ユーザー補助が OFF なら先に ON にして、
     * サービスが起動してから操作する。操作できなければ false。
     */
    fun request(context: Context, turnOn: Boolean, manual: Boolean): Boolean {
        val app = context.applicationContext
        HotspotAccessibilityService.instance?.let {
            it.runFlow(manual, turnOn)
            return true
        }
        if (!OnDemandA11y.isActive(app)) return false
        if (pending != null) return true // すでに起動を待っている
        pending = Pending(turnOn, manual)
        if (!OnDemandA11y.enable(app)) {
            pending = null
            onServiceStartFailed(app, turnOn, manual)
            return true
        }
        Prefs(app).log("ユーザー補助を ON にしました")
        val timeout = Runnable {
            val p = pending ?: return@Runnable
            pending = null
            Prefs(app).log("ユーザー補助が起動しませんでした")
            OnDemandA11y.disable(app)
            onServiceStartFailed(app, p.turnOn, p.manual)
        }
        startTimeout = timeout
        handler.postDelayed(timeout, SERVICE_START_TIMEOUT_MS)
        return true
    }

    private fun onServiceStartFailed(context: Context, turnOn: Boolean, manual: Boolean) {
        onFlowFinished(context, HotspotFlow.Result.FAILED, manual, turnOn)
    }

    /**
     * ユーザー補助サービスが起動したときに呼ぶ。待っていた操作があれば始めて true を返す。
     */
    fun onServiceReady(service: HotspotAccessibilityService): Boolean {
        val p = pending ?: return false
        pending = null
        startTimeout?.let(handler::removeCallbacks)
        startTimeout = null
        service.runFlow(p.manual, p.turnOn)
        return true
    }

    /**
     * 「必要なときだけ ON」のとき、操作が終わったユーザー補助を OFF に戻す。
     * 操作中・起動待ちのときは何もしない。
     */
    fun releaseIfIdle(context: Context) {
        val app = context.applicationContext
        if (!OnDemandA11y.isActive(app) || pending != null) return
        if (HotspotAccessibilityService.instance?.isBusy == true) return
        if (OnDemandA11y.isEnabledInSettings(app) && OnDemandA11y.disable(app)) {
            Prefs(app).log("ユーザー補助を OFF に戻しました")
        }
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
        // 元の画面に戻りきってから OFF にする
        val app = context.applicationContext
        handler.postDelayed({ releaseIfIdle(app) }, RELEASE_DELAY_MS)

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

    /**
     * ユーザー補助が OFF のあいだ、ロック解除を代わりに待つ。
     * 車と接続中はシステムが TeslaPresenceService をバインドしているので、
     * アプリのプロセスは生きていて、ここで登録した受け取りが働く。
     */
    private object UnlockWatcher {
        private var registered = false

        private val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                // 解除直後はロック画面のアニメーションが残っているので少し待つ
                val reason = if (intent.action == Intent.ACTION_USER_PRESENT) "ロック解除" else "画面 ON"
                handler.postDelayed({ tryRun(context, reason) }, UNLOCK_DELAY_MS)
            }
        }

        fun start(context: Context) {
            if (registered) return
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_USER_PRESENT)
                // ロック画面が無い設定の端末では USER_PRESENT が来ないことがある
                addAction(Intent.ACTION_SCREEN_ON)
            }
            context.applicationContext.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            registered = true
        }

        fun stop(context: Context) {
            if (!registered) return
            runCatching { context.applicationContext.unregisterReceiver(receiver) }
            registered = false
        }
    }
}
