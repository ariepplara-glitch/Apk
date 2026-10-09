package com.jhon.floating

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Tangan otomatis untuk aplikasi Facebook asli (bukan WebView).
 * Hanya bertindak saat AutoRunner dijalankan dari panel melayang.
 */
class FbAutomationService : AccessibilityService() {

    companion object {
        @Volatile var instance: FbAutomationService? = null
        val FB_PACKAGES = listOf("com.facebook.katana", "com.facebook.lite")
    }

    override fun onServiceConnected() { instance = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onUnbind(intent: Intent?): Boolean { instance = null; return super.onUnbind(intent) }
    override fun onDestroy() { instance = null; super.onDestroy() }

    /** Semua window aktif kecuali window app ini sendiri (panel melayang kita). */
    private fun roots(): List<AccessibilityNodeInfo> {
        val list = mutableListOf<AccessibilityNodeInfo>()
        try {
            for (w in windows) {
                val r = w.root ?: continue
                if (r.packageName == packageName) continue
                list.add(r)
            }
        } catch (e: Exception) {}
        if (list.isEmpty()) rootInActiveWindow?.let { if (it.packageName != packageName) list.add(it) }
        return list
    }

    fun isFacebookForeground(): Boolean = roots().any { it.packageName in FB_PACKAGES }

    private fun walk(n: AccessibilityNodeInfo?, out: MutableList<AccessibilityNodeInfo>) {
        if (n == null) return
        out.add(n)
        for (i in 0 until n.childCount) walk(n.getChild(i), out)
    }

    fun allNodes(): List<AccessibilityNodeInfo> {
        val out = mutableListOf<AccessibilityNodeInfo>()
        roots().forEach { walk(it, out) }
        return out
    }

    private fun label(n: AccessibilityNodeInfo) =
        listOfNotNull(n.text?.toString(), n.contentDescription?.toString()).joinToString(" | ")

    /** Cari node yang teks/deskripsinya mengandung salah satu label (tanpa peduli huruf besar/kecil). */
    fun find(labels: List<String>, exact: Boolean = false): AccessibilityNodeInfo? {
        val nodes = allNodes()
        for (l in labels) {
            nodes.firstOrNull { n ->
                val t = n.text?.toString(); val d = n.contentDescription?.toString()
                if (exact) t.equals(l, true) || d.equals(l, true)
                else (t?.contains(l, true) == true) || (d?.contains(l, true) == true)
            }?.let { return it }
        }
        return null
    }

    /** Ketuk node; naik ke induk yang clickable, fallback ke tap koordinat. */
    fun click(node: AccessibilityNodeInfo): Boolean {
        var cur: AccessibilityNodeInfo? = node
        var hops = 0
        while (cur != null && hops < 6) {
            if (cur.isClickable && cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            cur = cur.parent; hops++
        }
        val r = Rect(); node.getBoundsInScreen(r)
        return if (r.width() > 0 && r.height() > 0) tap(r.centerX().toFloat(), r.centerY().toFloat()) else false
    }

    fun clickLabel(labels: List<String>, exact: Boolean = false): Boolean =
        find(labels, exact)?.let { click(it) } ?: false

    fun tap(x: Float, y: Float): Boolean {
        val p = Path().apply { moveTo(x, y) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, 80)).build()
        return dispatchGesture(g, null, null)
    }

    /** Tile video di galeri: node clickable yang deskripsi/teksnya menyebut "video" atau durasi (0:15). */
    fun videoTiles(): List<AccessibilityNodeInfo> {
        val rx = Regex("(?i)video|\\b\\d{1,2}:\\d{2}\\b")
        return allNodes().filter { n ->
            val l = label(n)
            l.isNotEmpty() && rx.containsMatchIn(l) && n.isVisibleToUser &&
                (n.isClickable || n.parent?.isClickable == true)
        }.sortedWith(compareBy({ b(it).top }, { b(it).left }))
    }

    private fun b(n: AccessibilityNodeInfo) = Rect().also { n.getBoundsInScreen(it) }

    fun scrollForward(): Boolean =
        allNodes().firstOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) ?: false

    /** Untuk debugging: daftar teks/deskripsi yang terlihat di layar sekarang. */
    fun dumpScreen(): String {
        val sb = StringBuilder()
        allNodes().forEach { n ->
            val l = label(n)
            if (l.isNotEmpty()) sb.append(if (n.isClickable) "[tap] " else "      ")
                .append(n.packageName).append(": ").append(l.take(60)).append('\n')
        }
        return sb.toString().ifEmpty { "(kosong - aksesibilitas aktif? FB terbuka?)" }
    }
}
