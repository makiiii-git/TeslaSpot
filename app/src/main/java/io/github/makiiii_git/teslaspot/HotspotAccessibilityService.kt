package io.github.makiiii_git.teslaspot

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent

/**
 * スイッチを押す係。ユーザー補助として ON になっているあいだは常駐しているので、
 * ロック解除（USER_PRESENT）の受け取りもここで行う。
 *
 * BLUETOOTH_CONNECT が許可されていれば、Bluetooth の接続イベントも予備として見る
 * （CompanionDeviceManager の通知が届かない機種への保険）。
 */
class HotspotAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: HotspotAccessibilityService? = null
            private set
    }

    private val handler = Handler(Looper.getMainLooper())
    private var flow: HotspotFlow? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                // 解除直後はロック画面のアニメーションが残っているので少し待つ
                Intent.ACTION_USER_PRESENT ->
                    handler.postDelayed({ Coordinator.tryRun(context, "ロック解除") }, 800)

                // ロック画面が無い設定の端末では USER_PRESENT が来ないことがある
                Intent.ACTION_SCREEN_ON ->
                    handler.postDelayed({ Coordinator.tryRun(context, "画面 ON") }, 800)

                BluetoothDevice.ACTION_ACL_CONNECTED ->
                    if (isOurCar(intent)) Coordinator.onCarConnected(context, "Bluetooth")

                BluetoothDevice.ACTION_ACL_DISCONNECTED ->
                    if (isOurCar(intent)) Coordinator.onCarDisconnected(context, "Bluetooth")
            }
        }
    }

    private var receiverRegistered = false

    override fun onServiceConnected() {
        instance = this
        refreshReceivers()
        val prefs = Prefs(this)
        if (!OnDemandA11y.isActive(this)) prefs.log("ユーザー補助サービスが起動しました")
        // 「必要なときだけ ON」で起動を待っていた操作があれば、それを始める。
        // なければ、車につながったまま再起動・再有効化された場合に備えて条件を確かめる。
        if (!Coordinator.onServiceReady(this)) {
            Coordinator.tryRun(this, "サービス起動")
            // 何も始まらなかったら、必要なときだけ ON のときは OFF に戻す
            handler.postDelayed({ Coordinator.releaseIfIdle(this) }, 1_500)
        }
    }

    /**
     * 受け取るブロードキャストを登録し直す。
     * Bluetooth の接続イベントは「付近のデバイス」の許可があるときだけ受け取れるので、
     * 許可が出たあとや車を登録し直したあとに呼ぶ。
     */
    fun refreshReceivers() {
        if (receiverRegistered) runCatching { unregisterReceiver(receiver) }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_SCREEN_ON)
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            }
        }
        registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        receiverRegistered = true
    }

    override fun onDestroy() {
        instance = null
        flow?.cancel()
        flow = null
        if (receiverRegistered) runCatching { unregisterReceiver(receiver) }
        receiverRegistered = false
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 画面の読み取りは HotspotFlow が一定間隔で行うので、ここでは何もしない
    }

    override fun onInterrupt() {}

    /** スイッチを操作している最中か。 */
    val isBusy: Boolean get() = flow != null

    /** 操作を始める。すでに動いていれば何もしない。 */
    fun runFlow(manual: Boolean, turnOn: Boolean = true) {
        handler.post {
            if (flow != null) return@post
            Prefs(this).log(
                when {
                    manual -> "テスト実行を開始"
                    turnOn -> "アクセス ポイントを ON にします"
                    else -> "アクセス ポイントを OFF にします"
                },
            )
            flow = HotspotFlow(this, turnOn) { result ->
                flow = null
                Coordinator.onFlowFinished(this, result, manual, turnOn)
            }.also { it.start() }
        }
    }

    private fun isOurCar(intent: Intent): Boolean {
        val device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            ?: return false
        val saved = Prefs(this).carAddress ?: return false
        return device.address.equals(saved, ignoreCase = true)
    }
}
