package io.github.makiiii_git.teslaspot

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/** 通知は 1 枚だけを使い回す（状態が変わるたびに上書きする）。 */
object Notifier {
    private const val CHANNEL_ID = "status"
    private const val NOTIFICATION_ID = 1

    fun showWaitingForUnlock(context: Context) = post(
        context,
        title = "車に接続しました",
        text = "ロックを解除すると、アクセス ポイントを ON にします",
        intent = MainActivity.intent(context),
        ongoing = true,
    )

    fun showFailed(context: Context) = post(
        context,
        title = "アクセス ポイントを ON にできませんでした",
        text = "タップすると設定を開きます。手動で ON にしてください",
        intent = MainActivity.intent(context, openHotspotSettings = true),
        ongoing = false,
    )

    fun showServiceOff(context: Context) = post(
        context,
        title = "TeslaSpot のユーザー補助が OFF です",
        text = "タップして ON にしてください",
        intent = MainActivity.intent(context),
        ongoing = false,
    )

    fun cancel(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }

    private fun post(context: Context, title: String, text: String, intent: Intent, ongoing: Boolean) {
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "状態のお知らせ", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "ロック解除待ち・失敗のお知らせ"
                setSound(null, null)
                enableVibration(false)
            },
        )
        val pending = PendingIntent.getActivity(
            context,
            if (ongoing) 0 else 1,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(pending)
            .setAutoCancel(!ongoing)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }
}
