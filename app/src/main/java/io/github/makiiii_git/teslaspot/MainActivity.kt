package io.github.makiiii_git.teslaspot

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
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
        private const val REQ_BT_FOR_REGISTER = 12
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
    private lateinit var enabledSwitch: Switch
    private lateinit var labelEdit: EditText
    private lateinit var logView: TextView
    private lateinit var updateText: TextView
    private lateinit var updateButton: Button
    private var updateState: Updater.State? = null

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
        if (updateState == null && Updater.shouldAutoCheck(this)) {
            Updater.check(this, ::onUpdateState)
        }
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

        carButton = button("") { if (prefs.carAddress != null) unregisterCar() else registerCar() }
        a11yButton = button("2. ユーザー補助を ON にする") { openAccessibilitySettings() }
        notifyButton = button("3. 通知を許可する") {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_PERMS)
        }
        listOf(carButton, a11yButton, notifyButton).forEach(column::addView)
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
            text = "アップデート"
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(16), 0, dp(4))
        })
        updateText = TextView(this).apply { text = "現在のバージョン: v${Updater.currentVersion}" }
        column.addView(updateText)
        updateButton = button("アップデートを確認") { onUpdateButton() }
        column.addView(updateButton)

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
        val registered = prefs.carAddress != null
        val a11y = isAccessibilityEnabled()
        val notify = granted(Manifest.permission.POST_NOTIFICATIONS)
        val bt = granted(Manifest.permission.BLUETOOTH_CONNECT)

        status.text = buildString {
            appendLine("${mark(registered)} 車: ${if (registered) car ?: "登録済み" else "未登録"}")
            appendLine("${mark(a11y)} ユーザー補助: ${if (a11y) "ON" else "OFF"}")
            appendLine("${mark(notify)} 通知: ${if (notify) "許可済み" else "未許可"}")
            if (registered) {
                val how = when {
                    prefs.associationId >= 0 && bt -> "システム通知 + Bluetooth"
                    prefs.associationId >= 0 -> "システム通知のみ"
                    bt -> "Bluetooth のみ"
                    else -> "なし（「付近のデバイス」を許可してください）"
                }
                appendLine("${mark(prefs.associationId >= 0 || bt)} 接続の検知: $how")
            }
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
        enabledSwitch.isChecked = prefs.enabled
        if (!labelEdit.hasFocus()) labelEdit.setText(prefs.customLabel)
        logView.text = prefs.log.ifEmpty { "（まだありません）" }
    }

    // ---- 車の登録 -------------------------------------------------------------
    //
    // システムの機器選択画面は「近くで見つかった機器」を探すので、ペアリング済みでも
    // 検出可能モードでない車（Tesla など）は出てこないことがある。
    // そこで、ペアリング済みの機器を自前の一覧で選んでもらい、選んだ機器の
    // アドレスを指定して CompanionDeviceManager に登録する（確認ダイアログだけ出る）。
    // システムへの登録がうまくいかない機種でも、Bluetooth の接続イベントで検知できる。

    private var pendingDevice: BluetoothDevice? = null

    private fun registerCar() {
        if (!granted(Manifest.permission.BLUETOOTH_CONNECT)) {
            toast("ペアリング済みの機器を表示するため、「付近のデバイス」を許可してください")
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), REQ_BT_FOR_REGISTER)
            return
        }
        showPairedDevices()
    }

    @SuppressLint("MissingPermission") // registerCar() で許可を確認済み
    private fun showPairedDevices() {
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            toast("Bluetooth を ON にしてください")
            return
        }
        val devices = adapter.bondedDevices.orEmpty().sortedWith(
            compareBy<BluetoothDevice>({ deviceRank(it) }, { it.name ?: it.address }),
        )
        if (devices.isEmpty()) {
            toast("ペアリング済みの機器がありません。先に車と Bluetooth でペアリングしてください")
            return
        }
        val labels = devices.map { d ->
            val icon = when (deviceRank(d)) {
                0 -> "🚗"
                1 -> "🔊"
                else -> "・"
            }
            "$icon ${d.name ?: "（名前なし）"}\n    ${d.address}"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("車を選んでください（ペアリング済み）")
            .setItems(labels) { _, which -> associate(devices[which]) }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    /** 並び順: 0 = カーオーディオ、1 = その他のオーディオ、2 = それ以外。 */
    @SuppressLint("MissingPermission")
    private fun deviceRank(d: BluetoothDevice): Int {
        val cls = d.bluetoothClass ?: return 2
        return when {
            cls.deviceClass == BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO -> 0
            cls.majorDeviceClass == BluetoothClass.Device.Major.AUDIO_VIDEO -> 1
            else -> 2
        }
    }

    private fun associate(device: BluetoothDevice) {
        pendingDevice = device
        val request = AssociationRequest.Builder()
            .addDeviceFilter(BluetoothDeviceFilter.Builder().setAddress(device.address).build())
            .setSingleDevice(true)
            .build()
        cdm.associate(request, mainExecutor, object : CompanionDeviceManager.Callback() {
            override fun onAssociationPending(intentSender: IntentSender) {
                startIntentSenderForResult(intentSender, REQ_ASSOCIATE, null, 0, 0, 0)
            }

            override fun onAssociationCreated(associationInfo: AssociationInfo) {
                onAssociated(associationInfo)
            }

            override fun onFailure(error: CharSequence?) {
                prefs.log("システムへの登録に失敗: ${error ?: "不明"}")
                offerBluetoothOnly(device)
            }
        })
    }

    private fun offerBluetoothOnly(device: BluetoothDevice) {
        if (isFinishing || prefs.carAddress != null) return
        AlertDialog.Builder(this)
            .setTitle("システムへの登録ができませんでした")
            .setMessage("Bluetooth の接続イベントだけで車を検知する方法で登録しますか？\n（ユーザー補助が ON なら、ふつうはこれで動きます）")
            .setPositiveButton("登録する") { _, _ -> registerBluetoothOnly(device) }
            .setNegativeButton("やめる", null)
            .show()
    }

    @SuppressLint("MissingPermission")
    private fun registerBluetoothOnly(device: BluetoothDevice) {
        prefs.associationId = -1
        prefs.carAddress = device.address.uppercase()
        prefs.carName = device.name
        prefs.log("車を登録しました（Bluetooth 検知のみ）: ${device.name ?: device.address}")
        HotspotAccessibilityService.instance?.refreshReceivers()
        toast("登録しました")
        refresh()
    }

    @Deprecated("Activity#onActivityResult")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_ASSOCIATE || resultCode != RESULT_OK) return
        // 通常は onAssociationCreated が先に呼ばれるが、念のためこちらでも受け取る
        data?.getParcelableExtra(CompanionDeviceManager.EXTRA_ASSOCIATION, AssociationInfo::class.java)
            ?.let(::onAssociated)
    }

    @SuppressLint("MissingPermission")
    private fun onAssociated(info: AssociationInfo) {
        if (prefs.associationId == info.id) return
        val address = info.deviceMacAddress?.toString()?.uppercase() ?: pendingDevice?.address?.uppercase()
        prefs.associationId = info.id
        prefs.carAddress = address
        prefs.carName = info.displayName?.toString() ?: pendingDevice?.name
        HotspotAccessibilityService.instance?.refreshReceivers()
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
            prefs.log("システムの接続監視を始められませんでした（Bluetooth 検知で動きます）: ${e.message}")
            toast("登録しました（Bluetooth 検知で動きます）")
        }
        refresh()
    }

    private fun unregisterCar() {
        val id = prefs.associationId
        if (id >= 0) try {
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
        if (id >= 0) runCatching { cdm.disassociate(id) }
        prefs.clearCar()
        prefs.log("車の登録を解除しました")
        Notifier.cancel(this)
        refresh()
    }

    // ---- アップデート -----------------------------------------------------------

    private fun onUpdateButton() {
        when (val s = updateState) {
            is Updater.State.Available -> {
                // 初回だけ「この提供元のアプリを許可」が要る
                if (!packageManager.canRequestPackageInstalls()) {
                    toast("「この提供元のアプリを許可」を ON にして、戻ってからもう一度押してください")
                    startActivity(
                        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.fromParts("package", packageName, null)),
                    )
                    return
                }
                Updater.downloadAndInstall(this, s.release, ::onUpdateState)
            }
            is Updater.State.Downloading, Updater.State.Checking -> Unit
            else -> Updater.check(this, ::onUpdateState)
        }
    }

    private fun onUpdateState(state: Updater.State) {
        updateState = state
        if (isDestroyed) return
        val current = "現在のバージョン: v${Updater.currentVersion}"
        updateButton.isEnabled = state !is Updater.State.Checking && state !is Updater.State.Downloading
        when (state) {
            Updater.State.Checking -> {
                updateText.text = "$current\n確認しています…"
                updateButton.text = "確認中…"
            }
            Updater.State.UpToDate -> {
                updateText.text = "$current\n最新です"
                updateButton.text = "アップデートを確認"
            }
            is Updater.State.Available -> {
                val notes = state.release.notes.trim().take(300)
                updateText.text = "$current\n新しいバージョン v${state.release.version} があります" +
                    if (notes.isNotEmpty()) "\n\n$notes" else ""
                updateButton.text = "v${state.release.version} にアップデート"
            }
            is Updater.State.Downloading -> {
                updateText.text = "$current\nダウンロード中… ${state.percent}%"
                updateButton.text = "ダウンロード中 ${state.percent}%"
            }
            Updater.State.WaitingForUser -> {
                updateText.text = "$current\n確認画面で「更新」を押してください。更新が終わるとアプリが閉じます"
                updateButton.text = "もう一度確認"
            }
            is Updater.State.Failed -> {
                updateText.text = "$current\n${state.message}"
                updateButton.text = "もう一度確認"
            }
        }
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
        if (granted(Manifest.permission.BLUETOOTH_CONNECT)) {
            HotspotAccessibilityService.instance?.refreshReceivers()
            if (requestCode == REQ_BT_FOR_REGISTER) showPairedDevices()
        } else if (requestCode == REQ_BT_FOR_REGISTER) {
            toast("「付近のデバイス」の許可がないと、ペアリング済みの機器を表示できません")
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
