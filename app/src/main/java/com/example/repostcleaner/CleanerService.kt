package com.example.repostcleaner

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.random.Random

/**
 * Walks through the user's own reposts in TikTok and removes them one by one.
 *
 * Flow per repost: open reposts tab -> open first video -> tap Share -> tap "Remove repost" -> back.
 * The word lists and the tap position below are best guesses. Use the diagnostic button in the
 * app to dump the screen and adjust them to your TikTok version and language.
 */
class CleanerService : AccessibilityService() {

    companion object {
        @Volatile var instance: CleanerService? = null
        @Volatile var running = false
        @Volatile var removed = 0
        @Volatile var status = "جاهز"
        @Volatile var lastFailDump = ""
        private val log = ArrayList<String>()
        fun recent(): String = synchronized(log) { log.takeLast(40).reversed().joinToString("\n") }
        fun addLog(line: String) = synchronized(log) { log.add(line) }
        fun clearLog() = synchronized(log) { log.clear() }

        // words matched (case-insensitive, "contains") against node text and contentDescription
        val TAB_WORDS = listOf("reposts", "repost", "إعادات النشر", "إعادة النشر", "المعاد نشرها")
        val SHARE_WORDS = listOf("share", "مشاركة")
        val REMOVE_WORDS = listOf(
            "remove repost", "remove from reposts", "undo repost",
            "إزالة إعادة النشر", "إلغاء إعادة النشر", "إزالة المشاركة"
        )

        // where the first video of the grid sits, as a fraction of the screen
        // fallback only, used when no grid cell is found in the accessibility tree
        const val GRID_X = 0.5f
        const val GRID_Y = 0.58f

        const val MAX_FAILURES = 4
    }

    private enum class Step { TAB, OPEN, SHARE, REMOVE }

    private val handler = Handler(Looper.getMainLooper())
    private val tick = Runnable { doStep() }
    private var step = Step.TAB
    private var tries = 0
    private var failures = 0
    private var limit = 500

    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {
        stopWork("تم إيقاف الخدمة")
    }

    override fun onDestroy() {
        stopWork("الخدمة اتقفلت")
        instance = null
        super.onDestroy()
    }

    fun begin(maxCount: Int) {
        handler.removeCallbacks(tick)
        limit = maxCount
        removed = 0
        clearLog()
        failures = 0
        tries = 0
        step = Step.TAB
        running = true
        status = "شغّال… افتح تيك توك على بروفايلك"
        schedule()
    }

    fun stopWork(message: String) {
        running = false
        handler.removeCallbacks(tick)
        status = message
    }

    private fun schedule() {
        if (running) handler.postDelayed(tick, Random.nextLong(1400, 2600))
    }

    private fun doStep() {
        if (!running) return
        val root = rootInActiveWindow
        val pkg = root?.packageName?.toString()
        if (root == null || pkg == null || !isTikTok(pkg)) {
            status = "افتح تيك توك على بروفايلك (الشاشة الحالية: ${pkg ?: "؟"})"
            schedule()
            return
        }
        when (step) {
            Step.TAB -> {
                val tab = find(root, TAB_WORDS)
                if (tab != null) click(tab)
                status = "فتح تبويب الريبوستات"
                step = Step.OPEN
                schedule()
            }
            Step.OPEN -> {
                status = "فتح أول فيديو"
                val m = resources.displayMetrics
                val cell = firstGridCell(root)
                val x = cell?.exactCenterX() ?: (m.widthPixels * GRID_X)
                val y = cell?.exactCenterY() ?: (m.heightPixels * GRID_Y)
                step = Step.SHARE
                tries = 0
                tap(x, y) { schedule() }
            }
            Step.SHARE -> {
                val share = find(root, SHARE_WORDS)
                if (share != null) {
                    click(share)
                    status = "فتح قائمة المشاركة"
                    step = Step.REMOVE
                    tries = 0
                } else if (++tries >= 3) {
                    fail("مش لاقي زرار المشاركة")
                    return
                }
                schedule()
            }
            Step.REMOVE -> {
                val remove = find(root, REMOVE_WORDS)
                if (remove != null) {
                    val title = captureTitle(root)
                    click(remove)
                    removed++
                    addLog("$removed. $title")
                    failures = 0
                    status = "تمت إزالة $removed"
                    if (removed >= limit) {
                        goBack()
                        stopWork("خلصنا: تمت إزالة $removed")
                        return
                    }
                    goBack()
                    step = Step.OPEN
                } else if (++tries >= 3) {
                    fail("مش لاقي زرار إزالة إعادة النشر")
                    return
                }
                schedule()
            }
        }
    }

    /** First couple of readable texts on the video screen, used as a label in the log. */
    private fun captureTitle(root: AccessibilityNodeInfo): String {
        val skip = SHARE_WORDS + REMOVE_WORDS + TAB_WORDS
        val found = ArrayList<String>()
        fun walk(n: AccessibilityNodeInfo?) {
            if (n == null || found.size >= 2) return
            val t = n.text?.toString()?.trim().orEmpty()
            if (t.length in 4..120 && skip.none { t.lowercase().contains(it.lowercase()) }) found.add(t)
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        walk(root)
        return found.joinToString(" · ").take(90).ifEmpty { "فيديو" }
    }

    private fun isTikTok(pkg: String): Boolean {
        val p = pkg.lowercase()
        return p.contains("musically") || p.contains("trill") || p.contains("tiktok")
    }

    /** Top-most, left-most portrait tile about a third of the screen wide: the first video of the grid. */
    private fun firstGridCell(root: AccessibilityNodeInfo): Rect? {
        val m = resources.displayMetrics
        val minW = (m.widthPixels * 0.25f).toInt()
        val maxW = (m.widthPixels * 0.40f).toInt()
        var best: Rect? = null
        fun walk(n: AccessibilityNodeInfo?) {
            if (n == null) return
            val r = Rect()
            n.getBoundsInScreen(r)
            if (r.width() in minW..maxW && r.height() > r.width() &&
                r.top > m.heightPixels * 0.30f && (n.isClickable || n.isLongClickable)
            ) {
                val b = best
                if (b == null || r.top < b.top || (r.top == b.top && r.left < b.left)) best = r
            }
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        walk(root)
        return best
    }

    private fun fail(reason: String) {
        lastFailDump = "FAILED: $reason (step=$step)\n" + dumpScreen()
        failures++
        goBack()
        goBack()
        if (failures >= MAX_FAILURES) {
            stopWork("وقفت: $reason. ممكن الريبوستات خلصت أو الواجهة مختلفة. جرّب زرار التشخيص")
            return
        }
        status = "$reason، بحاول تاني"
        step = Step.OPEN
        tries = 0
        schedule()
    }

    private fun goBack() {
        performGlobalAction(GLOBAL_ACTION_BACK)
    }

    private fun tap(x: Float, y: Float, done: () -> Unit) {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
            .build()
        val ok = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) = done()
            override fun onCancelled(g: GestureDescription?) = done()
        }, null)
        if (!ok) done()
    }

    private fun find(node: AccessibilityNodeInfo?, words: List<String>): AccessibilityNodeInfo? {
        if (node == null) return null
        val label = listOfNotNull(node.text, node.contentDescription)
            .joinToString(" ") { it.toString() }
            .lowercase()
        if (label.isNotEmpty() && words.any { label.contains(it.lowercase()) }) return node
        for (i in 0 until node.childCount) {
            val hit = find(node.getChild(i), words)
            if (hit != null) return hit
        }
        return null
    }

    private fun click(node: AccessibilityNodeInfo) {
        var n: AccessibilityNodeInfo? = node
        while (n != null && !n.isClickable) n = n.parent
        (n ?: node).performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    /** Text dump of the current screen, used to calibrate the word lists. */
    fun dumpScreen(): String {
        val root = rootInActiveWindow ?: return "مفيش شاشة متاحة. افتح تيك توك الأول."
        val sb = StringBuilder("package=${root.packageName}\n")
        windows.forEach { sb.append("window: pkg=${it.root?.packageName} type=${it.type} title=${it.title}\n") }
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null) return
            val t = n.text?.toString().orEmpty()
            val d = n.contentDescription?.toString().orEmpty()
            val id = n.viewIdResourceName.orEmpty()
            val r = Rect()
            n.getBoundsInScreen(r)
            if (t.isNotEmpty() || d.isNotEmpty() || n.isClickable) {
                sb.append("  ".repeat(depth))
                    .append(n.className).append(" | id=").append(id)
                    .append(" | text=").append(t).append(" | desc=").append(d)
                    .append(" | clickable=").append(n.isClickable)
                    .append(" | bounds=").append(r.toShortString()).append('\n')
            }
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        walk(root, 0)
        return sb.toString()
    }
}
