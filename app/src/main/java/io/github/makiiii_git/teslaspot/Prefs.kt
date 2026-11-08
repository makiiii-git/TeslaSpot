package io.github.makiiii_git.teslaspot

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 設定と状態の保存先。プロセスが落ちても「接続中かどうか」を覚えておくために使う。 */
class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("teslaspot", Context.MODE_PRIVATE)

    /** CompanionDeviceManager の関連付け ID。未登録なら -1。 */
    var associationId: Int
        get() = sp.getInt("association_id", -1)
        set(v) = sp.edit().putInt("association_id", v).apply()

    var carAddress: String?
        get() = sp.getString("car_address", null)
        set(v) = sp.edit().putString("car_address", v).apply()

    var carName: String?
        get() = sp.getString("car_name", null)
        set(v) = sp.edit().putString("car_name", v).apply()

    /** 自動 ON を有効にするか（一時的に止めたいとき用）。 */
    var enabled: Boolean
        get() = sp.getBoolean("enabled", true)
        set(v) = sp.edit().putBoolean("enabled", v).apply()

    /** 車から切断したとき、ロック解除中なら OFF にするか（初期値は OFF）。 */
    var turnOffOnDisconnect: Boolean
        get() = sp.getBoolean("turn_off_on_disconnect", false)
        set(v) = sp.edit().putBoolean("turn_off_on_disconnect", v).apply()

    /**
     * ユーザー補助を必要なときだけ ON にするか（adb で許可したときだけ効く）。
     * 許可すればそのまま使えるように、初期値は ON。
     */
    var onDemandA11y: Boolean
        get() = sp.getBoolean("on_demand_a11y", true)
        set(v) = sp.edit().putBoolean("on_demand_a11y", v).apply()

    /** 今、車とつながっているか。 */
    var carPresent: Boolean
        get() = sp.getBoolean("car_present", false)
        set(v) = sp.edit().putBoolean("car_present", v).apply()

    /** 最後に接続を受け取った時刻（重複した知らせを見分けるため）。 */
    var connectedAt: Long
        get() = sp.getLong("connected_at", 0L)
        set(v) = sp.edit().putLong("connected_at", v).apply()

    /** この接続のあいだに、もう ON にしたか（同じ接続中に何度も設定画面を開かないため）。 */
    var doneThisConnection: Boolean
        get() = sp.getBoolean("done_this_connection", false)
        set(v) = sp.edit().putBoolean("done_this_connection", v).apply()

    /**
     * 自動で見つけられない機種のための、スイッチの表示名（例: 「モバイルホットスポット」）。
     * 空なら組み込みの候補で探す。
     */
    var customLabel: String
        get() = sp.getString("custom_label", "") ?: ""
        set(v) = sp.edit().putString("custom_label", v.trim()).apply()

    val log: String
        get() = sp.getString("log", "") ?: ""

    fun log(message: String) {
        val stamp = SimpleDateFormat("MM/dd HH:mm:ss", Locale.JAPAN).format(Date())
        val lines = (listOf("$stamp  $message") + log.lines().filter { it.isNotBlank() }).take(MAX_LOG_LINES)
        sp.edit().putString("log", lines.joinToString("\n")).apply()
    }

    fun clearCar() {
        sp.edit()
            .remove("association_id")
            .remove("car_address")
            .remove("car_name")
            .putBoolean("car_present", false)
            .putBoolean("done_this_connection", false)
            .apply()
    }

    private companion object {
        const val MAX_LOG_LINES = 40
    }
}
