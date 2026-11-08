package io.github.makiiii_git.teslaspot

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings

/**
 * ユーザー補助を「必要なときだけ」ON にする。
 *
 * PayPay など、ユーザー補助が ON のアプリがあると起動しないアプリがある。
 * そこで普段は OFF にしておき、アクセス ポイントを操作する数秒だけ ON にする。
 *
 * ユーザー補助の ON/OFF を書き換えるには WRITE_SECURE_SETTINGS が要る。
 * これは普通のアプリには許可されないので、PC から 1 回だけ adb で許可してもらう:
 *   adb shell pm grant io.github.makiiii_git.teslaspot android.permission.WRITE_SECURE_SETTINGS
 */
object OnDemandA11y {

    private const val PERMISSION = "android.permission.WRITE_SECURE_SETTINGS"

    /** adb で許可されているか。 */
    fun canControl(context: Context): Boolean =
        context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    /** 「必要なときだけ ON」で動いているか（許可があり、設定でも選ばれている）。 */
    fun isActive(context: Context): Boolean = canControl(context) && Prefs(context).onDemandA11y

    /** 設定上、TeslaSpot のユーザー補助が ON になっているか。 */
    fun isEnabledInSettings(context: Context): Boolean = enabledList(context).contains(me(context))

    /** ON にする。書き換えられなければ false。 */
    fun enable(context: Context): Boolean {
        val list = enabledList(context)
        if (list.contains(me(context))) return true
        return write(context, list + me(context))
    }

    /** OFF にする。ほかのアプリのユーザー補助には触らない。 */
    fun disable(context: Context): Boolean {
        val list = enabledList(context)
        if (!list.contains(me(context))) return true
        return write(context, list - me(context))
    }

    private fun me(context: Context): String =
        ComponentName(context, HotspotAccessibilityService::class.java).flattenToString()

    private fun enabledList(context: Context): List<String> {
        val raw = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return emptyList()
        val mine = ComponentName(context, HotspotAccessibilityService::class.java)
        // 「.HotspotAccessibilityService」のような短い書き方でも同じものとして扱う
        return raw.split(':').filter { it.isNotBlank() }.map { entry ->
            if (ComponentName.unflattenFromString(entry) == mine) mine.flattenToString() else entry
        }.distinct()
    }

    @SuppressLint("MissingPermission")
    private fun write(context: Context, list: List<String>): Boolean {
        if (!canControl(context)) return false
        return try {
            val resolver = context.contentResolver
            Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, list.joinToString(":"))
            Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, if (list.isEmpty()) 0 else 1)
            true
        } catch (e: SecurityException) {
            Prefs(context).log("ユーザー補助を切り替えられませんでした: ${e.message}")
            false
        }
    }
}
