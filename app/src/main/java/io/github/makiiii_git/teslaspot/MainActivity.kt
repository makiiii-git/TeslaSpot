package io.github.makiiii_git.teslaspot

import android.Manifest
import android.app.Activity
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.CompanionDeviceManager
import android.companion.ObservingDevicePresenceRequest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/**
 * 設定画面。やることは 4 つだけ:
 *   1. 車（Bluetooth）を登録する
 *   2. ユーザー補助を ON にする（サイドロードのアプリは先に「制限付き設定を許可」が要る）
 *   3. 通知を許可する
 *   4. テスト実行で、スイッチが押せるか確かめる
 */
class MainActivity : Activity() {

    companion object {
        private const val REQ_ASSOCIATE = 10
        private const val REQ_PERMS = 11
        private const val EXTRA_OPEN_HOTSPOT = "open_hotspot"

        fun intent(context: Context, openHotspotSettings: Boolean = false): Intent =
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_OPEN_HOTSPOT, openHotspotSettings)
    }

    private val prefs by lazy { Prefs(this) }
    private val cdm by lazy { getSystemService(CompanionDeviceManager::class.java) }

    private lateinit var status: TextView
    private lateinit var carButton: Button
    private lateinit var a11yButton: Button
    private lateinit var notifyButton: Button
    private lateinit var btButton: Button
    private lateinit var enabledSwitch: Switch
    private lateinit var labelEdit: EditText
    private lateinit var logView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_HOTSPOT, false) == true) {
            intent.removeExtra(EXTRA_OPEN_HOTSPOT)
            HotspotFlow.launchHotspotSettings(this)
        }
    }

    // ---- 画面 ---------------------------------------------------------------

    private fun buildUi(): View {
        val pad = dp(16)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        column.addView(TextView(this).apply {
            text = "TeslaSpot"
            textSize = 26f
            setTypeface(typeface, Typeface.BOLD)
        })
        column.addView(TextView(this).apply {
            text = "車と Bluetooth でつながり、ロックが解除されたら Wi-Fi アクセス ポイントを自動で ON にします。"
            setPadding(0, dp(4), 0, dp(12))
        })

        status = TextView(this).apply {
            textSize = 15f
            setLineSpacing(0f, 1.25f)
            setPadding(0, 0, 0, dp(12))
        }
        column.addView(status)

        carButton = button("") { if (prefs.associationId >= 0) unregisterCar() else registerCar() }
        a11yButton = button("2. ユーザー補助を ON にする") { openAccessibilitySettings() }
        notifyButton = button("3. 通知を許可する") {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_PERMS)
        }
        btButton = button("（任意）接続の検知を強化する") {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), REQ_PERMS)
        }
        listOf(carButton, a11yButton, notifyButton, btButton).forEach(column::addView)
        column.addView(button("4. 今すぐテスト（アクセス ポイントを ON）") { runTest() })
        column.addView(button("アプリ情報を開く（制限付き設定の許可）") { openAppInfo() })

        enabledSwitch = Switch(this).apply {
            text = "自動で ON にする"
            textSize = 16f
            setPadding(0, dp(12), 0, dp(12))
            setOnCheckedChangeListener { _, checked ->
                prefs.enabled = checked
                refresh()
            }
        }
        column.addView(enabledSwitch)

        column.addView(TextView(this).apply {
            text = "スイッチの名前（自動で見つからない機種だけ。例: モバイルホットスポット）"
            setPadding(0, dp(8), 0, 0)
        })
        labelEdit = EditText(this).apply {
            hint = "空欄で自動"
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine()
        }
        column.addView(labelEdit)
        column.addView(button("名前を保存") {
            prefs.customLabel = labelEdit.text.toString()
            toast(if (prefs.customLabel.isEmpty()) "自動で探します" else "「${prefs.customLabel}」で探します")
        })

        column.addView(TextView(this).apply {
            text = "ログ"
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(16), 0, dp(4))
        })
        logView = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setTextIsSelectable(true)
        }
        column.addView(logView)

        return ScrollView(this).apply {
            addView(column)
            // targetSdk 35 以降は全画面表示になるので、システムバーの分だけ内側に寄せる
            setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        }
    }

    private fun refresh() {
        val car = prefs.carName ?: prefs.carAddress
        val registered = prefs.associationId >= 0
        val a11y = isAccessibilityEnabled()
        val notify = granted(Manifest.permission.POST_NOTIFICATIONS)
        val bt = granted(Manifest.permission.BLUETOOTH_CONNECT)

        status.text = buildString {
            appendLine("${mark(registered)} 車: ${if (registered) car ?: "登録済み" else "未登録"}")
            appendLine("${mark(a11y)} ユーザー補助: ${if (a11y) "ON" else "OFF"}")
            appendLine("${mark(notify)} 通知: ${if (notify) "許可済み" else "未許可"}")
            appendLine("${if (bt) "✅" else "➖"} 予備の接続検知: ${if (bt) "ON" else "OFF（任意）"}")
            append("いまの状態: ")
            append(
                when {
                    !prefs.enabled -> "停止中"
                    !prefs.carPresent -> "車とは未接続"
                    prefs.doneThisConnection -> "接続中（この接続では実行済み）"
                    else -> "接続中（ロック解除を待っています）"
                },
            )
        }
        carButton.text = if (registered) "車の登録を解除する" else "1. 車を登録する（Bluetooth）"
        a11yButton.visibility = if (a11y) View.GONE else View.VISIBLE
        notifyButton.visibility = if (notify) View.GONE else View.VISIBLE
        btButton.visibility = if (bt) View.GONE else View.VISIBLE
        enabledSwitch.isChecked = prefs.enabled
        if (!labelEdit.hasFocus()) labelEdit.setText(prefs.customLabel)
        logView.text = prefs.log.ifEmpty { "（まだありません）" }
    }

    // ---- 車の登録（CompanionDeviceManager）---------------------------------

    private fun registerCar() {
        // 名前で絞らない（車の Bluetooth 名はオーナーが自由に付けられるため）。
        // ペアリング済みの機器も一覧に出るので、そこから車を選ぶ。
        val request = AssociationRequest.Builder()
            .addDeviceFilter(BluetoothDeviceFilter.Builder().build())
            .setSingleDevice(false)
            .build()
        cdm.associate(request, mainExecutor, object : CompanionDeviceManager.Callback() {
            override fun onAssociationPending(intentSender: IntentSender) {
                startIntentSenderForResult(intentSender, REQ_ASSOCIATE, null, 0, 0, 0)
            }

            override fun onAssociationCreated(associationInfo: AssociationInfo) {
                onAssociated(associationInfo)
            }

            override fun onFailure(error: CharSequence?) {
                toast("登録できませんでした: ${error ?: "不明なエラー"}")
            }
        })
    }

    @Deprecated("Activity#onActivityResult")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_ASSOCIATE || resultCode != RESULT_OK) return
        // 通常は onAssociationCreated が先に呼ばれるが、念のためこちらでも受け取る
        data?.getParcelableExtra(CompanionDeviceManager.EXTRA_ASSOCIATION, AssociationInfo::class.java)
            ?.let(::onAssociated)
    }

    private fun onAssociated(info: AssociationInfo) {
        if (prefs.associationId == info.id) return
        val address = info.deviceMacAddress?.toString()?.uppercase()
        prefs.associationId = info.id
        prefs.carAddress = address
        prefs.carName = info.displayName?.toString()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
                cdm.startObservingDevicePresence(
                    ObservingDevicePresenceRequest.Builder().setAssociationId(info.id).build(),
                )
            } else {
                @Suppress("DEPRECATION")
                cdm.startObservingDevicePresence(address ?: error("MAC アドレスがありません"))
            }
            prefs.log("車を登録しました: ${prefs.carName ?: address}")
            toast("登録しました")
        } catch (e: Exception) {
            prefs.log("接続の監視を始められませんでした: ${e.message}")
            toast("接続の監視を始められませんでした")
        }
        refresh()
    }

    private fun unregisterCar() {
        val id = prefs.associationId
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
                cdm.stopObservingDevicePresence(
                    ObservingDevicePresenceRequest.Builder().setAssociationId(id).build(),
                )
            } else {
                @Suppress("DEPRECATION")
                prefs.carAddress?.let { cdm.stopObservingDevicePresence(it) }
            }
        } catch (_: Exception) {
        }
        runCatching { cdm.disassociate(id) }
        prefs.clearCar()
        prefs.log("車の登録を解除しました")
        Notifier.cancel(this)
        refresh()
    }

    // ---- その他の操作 ---------------------------------------------------------

    private fun runTest() {
        val service = HotspotAccessibilityService.instance
        if (service == null) {
            toast("先にユーザー補助を ON にしてください")
            return
        }
        service.runFlow(manual = true)
    }

    private fun openAccessibilitySettings() {
        toast("「ダウンロードしたアプリ」→ TeslaSpot を ON にしてください")
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun openAppInfo() {
        toast("右上の ︙ →「制限付き設定を許可」")
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)),
        )
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (permissions.contains(Manifest.permission.BLUETOOTH_CONNECT) && granted(Manifest.permission.BLUETOOTH_CONNECT)) {
            toast("ユーザー補助を一度 OFF→ON すると、予備の検知が有効になります")
        }
        refresh()
    }

    private fun isAccessibilityEnabled(): Boolean {
        if (HotspotAccessibilityService.instance != null) return true
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val me = ComponentName(this, HotspotAccessibilityService::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    private fun granted(permission: String) = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun mark(ok: Boolean) = if (ok) "✅" else "⚠️"

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
