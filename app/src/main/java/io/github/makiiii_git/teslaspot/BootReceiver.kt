package io.github.makiiii_git.teslaspot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 再起動したら「車と接続中」の記録を消す。
 * 接続中に電源が切れると切断の知らせが来ないので、古い状態が残ってしまうため。
 * 再起動後も車とつながっていれば、システムがあらためて接続を知らせてくる。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = Prefs(context)
        prefs.carPresent = false
        prefs.doneThisConnection = false
    }
}
