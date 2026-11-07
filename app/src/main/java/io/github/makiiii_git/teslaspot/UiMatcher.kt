package io.github.makiiii_git.teslaspot

import android.view.accessibility.AccessibilityNodeInfo

/**
 * 画面の部品ツリーから「アクセス ポイントのスイッチ」を探す。
 *
 * 誤操作を避けるため、スイッチを押してよいのは **表示名が候補と完全に一致する行** だけ。
 * Wi-Fi アクセス ポイントの画面には「自動的にアクセス ポイントを OFF にする」のような
 * 別のスイッチもあるので、「含む」で探すと別のスイッチを押してしまう。
 * 「含む」で探すのは、スイッチの無い「アクセス ポイントとテザリング」のような
 * 画面移動用の項目だけ。
 */
object UiMatcher {

    /** 比較用に正規化する: 小文字化し、空白・記号（Wi-Fi のハイフンなど）を除く。 */
    fun normalize(s: CharSequence?): String =
        s?.toString()?.lowercase()?.filter { it.isLetterOrDigit() } ?: ""

    /** スイッチを押してよい表示名（完全一致）。Pixel / AOSP / Galaxy / 英語表示の主なもの。 */
    private val TOGGLE_EXACT = listOf(
        "Wi-Fi アクセス ポイントを使用",
        "アクセス ポイントを使用",
        "Wi-Fi アクセス ポイント",
        "アクセス ポイント",
        "Wi-Fi テザリング",
        "テザリング",
        "モバイルホットスポット",
        "ホットスポット",
        "Use Wi-Fi hotspot",
        "Use hotspot",
        "Wi-Fi hotspot",
        "Hotspot",
        "Mobile Hotspot",
        "Portable Wi-Fi hotspot",
    ).map(::normalize).toSet()

    /** 画面移動の候補（含む）。 */
    private val NAV_CONTAINS = listOf(
        "アクセス ポイント", "テザリング", "ホットスポット", "Hotspot", "Tethering",
    ).map(::normalize)

    /** これを含む項目は、画面移動にも使わない。 */
    private val EXCLUDE = listOf(
        "USB", "Bluetooth", "イーサネット", "Ethernet",
        "自動", "Auto", "名", "Name", "パスワード", "Password", "セキュリティ", "Security",
        "互換", "Compat", "帯域", "Band", "速度", "Speed", "APN", "データ使用", "Data usage",
        "インスタント", "Instant",
    ).map(::normalize)

    /** クイック設定タイルの表示名（含む）。 */
    private val QS_CONTAINS = listOf(
        "アクセス ポイント", "テザリング", "ホットスポット", "Hotspot",
    ).map(::normalize)

    sealed interface Match {
        /** スイッチが見つかった。checked が null のときは ON/OFF が読み取れない。 */
        data class Switch(val target: AccessibilityNodeInfo, val checked: Boolean?) : Match
        /** スイッチは無いが、押すとアクセス ポイントの画面に進めそうな項目。 */
        data class Navigate(val target: AccessibilityNodeInfo, val key: String) : Match
        data object None : Match
    }

    fun labelOf(node: AccessibilityNodeInfo): String {
        val text = normalize(node.text)
        return text.ifEmpty { normalize(node.contentDescription) }
    }

    private fun isToggleLabel(label: String, custom: String): Boolean {
        if (label.isEmpty()) return false
        if (custom.isNotEmpty()) return label == custom
        return label in TOGGLE_EXACT
    }

    private fun isNavLabel(label: String, custom: String): Boolean {
        if (label.isEmpty()) return false
        if (EXCLUDE.any { label.contains(it) }) return false
        return NAV_CONTAINS.any { label.contains(it) } || (custom.isNotEmpty() && label.contains(custom))
    }

    /** 設定アプリの画面を調べる。 */
    fun scanSettings(
        root: AccessibilityNodeInfo,
        customLabel: String,
        alreadyNavigated: Set<String>,
        allowNavigate: Boolean,
    ): Match {
        val custom = normalize(customLabel)
        val nodes = flatten(root)

        for (node in nodes) {
            if (!isToggleLabel(labelOf(node), custom)) continue
            val sw = switchFor(node) ?: continue
            return Match.Switch(sw, sw.isChecked)
        }

        if (allowNavigate) {
            for (node in nodes) {
                val label = labelOf(node)
                if (label in alreadyNavigated || !isNavLabel(label, custom)) continue
                val target = clickableSelfOrAncestor(node) ?: continue
                return Match.Navigate(target, label)
            }
        }
        return Match.None
    }

    /** クイック設定（システム UI）の画面を調べる。 */
    fun scanQuickSettings(root: AccessibilityNodeInfo, customLabel: String): Match {
        val custom = normalize(customLabel)
        for (node in flatten(root)) {
            val label = labelOf(node)
            if (label.isEmpty()) continue
            val hit = if (custom.isNotEmpty()) label.contains(custom) else QS_CONTAINS.any { label.contains(it) }
            if (!hit || EXCLUDE.any { label.contains(it) }) continue

            // タイルは通常、タイル全体が「スイッチ」として ON/OFF を持っている。
            var cur: AccessibilityNodeInfo? = node
            repeat(4) {
                val n = cur ?: return@repeat
                if (n.isCheckable) return Match.Switch(n, n.isChecked)
                cur = n.parent
            }
            val target = clickableSelfOrAncestor(node) ?: continue
            return Match.Switch(target, stateFromDescription(target) ?: stateFromDescription(node))
        }
        return Match.None
    }

    private fun stateFromDescription(node: AccessibilityNodeInfo): Boolean? {
        val s = normalize(node.stateDescription)
        return when {
            s.isEmpty() -> null
            s == "オン" || s == "on" || s.startsWith("オン") -> true
            s == "オフ" || s == "off" || s.startsWith("オフ") -> false
            else -> null
        }
    }

    /**
     * 表示名の部品に対応するスイッチを探す。自分自身から親へ順にたどり、
     * その範囲にスイッチがちょうど 1 つだけある最初の段で決める。
     * 2 つ以上ある段まで広がったら、隣の行のスイッチかもしれないので諦める。
     */
    private fun switchFor(labelNode: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var cur: AccessibilityNodeInfo? = labelNode
        repeat(4) {
            val n = cur ?: return null
            val switches = flatten(n).filter { it.isCheckable }
            when {
                switches.size == 1 -> return switches[0]
                switches.size > 1 -> return null
            }
            cur = n.parent
        }
        return null
    }

    fun clickableSelfOrAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var cur: AccessibilityNodeInfo? = node
        repeat(5) {
            val n = cur ?: return null
            if (n.isClickable && n.isEnabled) return n
            cur = n.parent
        }
        return null
    }

    fun flatten(root: AccessibilityNodeInfo, limit: Int = 1500): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty() && out.size < limit) {
            val n = queue.removeFirst()
            if (!n.isVisibleToUser) continue
            out.add(n)
            for (i in 0 until n.childCount) n.getChild(i)?.let(queue::add)
        }
        return out
    }
}
