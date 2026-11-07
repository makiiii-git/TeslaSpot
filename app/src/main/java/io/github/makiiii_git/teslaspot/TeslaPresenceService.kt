package io.github.makiiii_git.teslaspot

import android.annotation.TargetApi
import android.companion.AssociationInfo
import android.companion.CompanionDeviceService
import android.companion.DevicePresenceEvent
import android.os.Build

/**
 * 登録した車の Bluetooth 接続・切断をシステムから受け取る。
 *
 * アプリが起動していなくても、システムがこのサービスをバインドして呼んでくれる。
 * Android 16 以降は onDevicePresenceEvent、それより前は onDeviceAppeared / Disappeared。
 * 両方から同じ接続が届いても Coordinator 側で 1 回にまとめる。
 */
class TeslaPresenceService : CompanionDeviceService() {

    // Android 16（API 36）以降でだけシステムから呼ばれる
    @TargetApi(Build.VERSION_CODES.BAKLAVA)
    override fun onDevicePresenceEvent(event: DevicePresenceEvent) {
        if (event.associationId != Prefs(this).associationId) return
        when (event.event) {
            DevicePresenceEvent.EVENT_BT_CONNECTED,
            DevicePresenceEvent.EVENT_BLE_APPEARED ->
                Coordinator.onCarConnected(this, "システム通知")

            DevicePresenceEvent.EVENT_BT_DISCONNECTED,
            DevicePresenceEvent.EVENT_BLE_DISAPPEARED ->
                Coordinator.onCarDisconnected(this, "システム通知")
        }
    }

    @Deprecated("Android 16 以降は onDevicePresenceEvent が呼ばれる")
    override fun onDeviceAppeared(associationInfo: AssociationInfo) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) return
        if (associationInfo.id != Prefs(this).associationId) return
        Coordinator.onCarConnected(this, "システム通知")
    }

    @Deprecated("Android 16 以降は onDevicePresenceEvent が呼ばれる")
    override fun onDeviceDisappeared(associationInfo: AssociationInfo) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) return
        if (associationInfo.id != Prefs(this).associationId) return
        Coordinator.onCarDisconnected(this, "システム通知")
    }
}
