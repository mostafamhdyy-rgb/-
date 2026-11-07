package com.example.repostcleaner

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
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
        @Volatile var startedAt = 0L
        @Volatile var batch = 50
        @Volatile var speed = 1 // 1 = normal, 2 = fast, 3 = fastest
        @Volatile var endedAt = 0L
        private val log = ArrayList<String>()
        fun recent(): String = synchronized(log) { log.takeLast(40).reversed().joinToString("\n") }
        fun addLog(line: String) = synchronized(log) { log.add(line) }
        fun clearLog() = synchronized(log) { log.clear() }

        // words matched (case-insensitive, "contains") against node text and contentDescription
        val TAB_WORDS = listOf("reposts", "repost", "إعادات النشر", "إعادة النشر", "المعاد نشرها")
        val SHARE_WORDS = listOf("share", "مشاركة")
        val REMOVE_WORDS = listOf(
            "حذف إعادة النشر", "حذف إعادة", "delete repost",
            "remove repost", "remove from reposts", "undo repost",
            "إزالة إعادة النشر", "إلغاء إعادة النشر", "إزالة المشاركة"
        )

        // where the first video of the grid sits, as a fraction of the screen
        // fallback only, used when no grid cell is found in the accessibility tree
        const val GRID_X = 0.5f
        const val GRID_Y = 0.58f

        const val MAX_FAILURES = 8
        const val REST_MS = 45_000L
    }

    private enum class Step { TAB, OPEN, SHARE, REMOVE }

    private val handler = Handler(Looper.getMainLooper())
    private val tick = Runnable { doStep() }
    private var step = Step.TAB
    private var tries = 0
    private var failures = 0
    private var limit = 500
    private var gridDetected = false
    private var gridMisses = 0
    private var skip = 0
    private var wakeLock: PowerManager.WakeLock? = null

    @Suppress("DEPRECATION")
    private fun holdScreen() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            releaseScreen()
            wakeLock = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE,
                "repostcleaner:run"
            ).also { it.acquire(90 * 60 * 1000L) }
        } catch (e: Exception) {
        }
    }

    private fun releaseScreen() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {
        }
    }

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
        gridDetected = false
        gridMisses = 0
        skip = 0
        failures = 0
        tries = 0
        step = Step.TAB
        startedAt = System.currentTimeMillis()
        endedAt = 0L
        holdScreen()
        running = true
        status = "شغّال… افتح تيك توك على بروفايلك"
        schedule()
    }

    fun stopWork(message: String) {
        if (running) endedAt = System.currentTimeMillis()
        releaseScreen()
        running = false
        handler.removeCallbacks(tick)
        status = message
    }

    /** Delay multiplier for the chosen speed: smaller = shorter waits between steps. */
    private fun factor(): Double = when (speed) {
        3 -> 0.35
        2 -> 0.55
        else -> 1.0
    }

    /** Polling attempts scale up as delays shrink, so the total wait before giving up stays the same. */
    private fun maxTries(n: Int): Int = (n / factor()).toInt()

    private fun schedule(minMs: Long = 250, maxMs: Long = 450, scale: Boolean = true) {
        if (!running) return
        val k = if (scale) factor() else 1.0
        val lo = (minMs * k).toLong().coerceAtLeast(80L)
        val hi = (maxMs * k).toLong().coerceAtLeast(lo + 40L)
        handler.postDelayed(tick, Random.nextLong(lo, hi))
    }

    private fun doStep() {
        if (!running) return
        val root = rootInActiveWindow
        val pkg = root?.packageName?.toString()
        if (root == null || pkg == null || !isTikTok(pkg)) {
            if (gridDetected && removed > 0) {
                stopWork("خلصنا: تمت إزالة $removed")
                return
            }
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
                val m = resources.displayMetrics
                val cells = gridCells(root)
                val cell = cells.getOrNull(skip)
                if (cells.isNotEmpty()) {
                    gridDetected = true
                    gridMisses = 0
                    if (cell == null) {
                        stopWork("وقفت: الفيديوهات الظاهرة كلها مش بتفتح ($removed اتشالت)")
                        return
                    }
                } else if (gridDetected) {
                    // not on the grid: wait for it, go back if it still does not show, give up after a while
                    gridMisses++
                    if (gridMisses > 5) {
                        stopWork("خلصنا: تمت إزالة $removed (مفيش فيديوهات تانية ظاهرة)")
                        return
                    }
                    if (gridMisses % 2 == 0) {
                        status = "رجوع للشبكة"
                        goBack()
                    } else {
                        status = "بستنى الشبكة"
                    }
                    schedule(500, 800)
                    return
                }
                status = if (skip > 0) "تخطي فيديو مش بيفتح" else "فتح أول فيديو"
                val x = cell?.exactCenterX() ?: (m.widthPixels * GRID_X)
                val y = cell?.exactCenterY() ?: (m.heightPixels * GRID_Y)
                step = Step.SHARE
                tries = 0
                tap(x, y) { schedule(700, 1000) }
            }
            Step.SHARE -> {
                val share = find(root, SHARE_WORDS)
                if (share != null) {
                    click(share)
                    status = "فتح قائمة المشاركة"
                    step = Step.REMOVE
                    tries = 0
                    schedule(550, 800)
                    return
                } else if (++tries >= maxTries(8)) {
                    fail("مش لاقي زرار المشاركة")
                    return
                }
                schedule()
            }
            Step.REMOVE -> {
                val remove = find(root, REMOVE_WORDS) ?: if (tries >= 2) findGenericRepost(root) else null
                if (remove != null) {
                    val title = captureTitle(root)
                    click(remove)
                    removed++
                    addLog("$removed. $title")
                    failures = 0
                    skip = 0
                    status = "تمت إزالة $removed"
                    if (removed >= limit) {
                        goBack()
                        stopWork("خلصنا: تمت إزالة $removed")
                        return
                    }
                    goBack()
                    step = Step.OPEN
                    if (batch > 0 && removed % batch == 0) {
                        status = "خلصت دفعة ($removed). راحة ${REST_MS / 1000} ثانية وبكمّل"
                        schedule(REST_MS, REST_MS + 3000, scale = false)
                    } else {
                        schedule(600, 850)
                    }
                    return
                } else if (++tries >= maxTries(7)) {
                    fail("مش لاقي زرار إزالة إعادة النشر")
                    return
                } else {
                    scrollSheet(root, tries)
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

    private fun findGenericRepost(root: AccessibilityNodeInfo): AccessibilityNodeInfo? =
        find(root, listOf("repost", "ريبوست", "إعادة النشر", "إعادة نشر"))

    /** The share sheet's action row scrolls sideways; nudge it so hidden buttons appear. */
    private fun scrollSheet(root: AccessibilityNodeInfo, n: Int) {
        val h = resources.displayMetrics.heightPixels
        fun walk(x: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (x == null) return null
            val r = Rect()
            x.getBoundsInScreen(r)
            if (x.isScrollable && r.top > h * 0.4f) return x
            for (i in 0 until x.childCount) {
                val f = walk(x.getChild(i))
                if (f != null) return f
            }
            return null
        }
        val s = walk(root) ?: return
        s.performAction(
            if (n % 2 == 0) AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        )
    }

    private fun isTikTok(pkg: String): Boolean {
        val p = pkg.lowercase()
        return p.contains("musically") || p.contains("trill") || p.contains("tiktok")
    }

    /** Portrait tiles about a third of the screen wide, in reading order: the videos of the grid. */
    private fun gridCells(root: AccessibilityNodeInfo): List<Rect> {
        val m = resources.displayMetrics
        val minW = (m.widthPixels * 0.25f).toInt()
        val maxW = (m.widthPixels * 0.40f).toInt()
        val found = ArrayList<Rect>()
        fun walk(n: AccessibilityNodeInfo?) {
            if (n == null) return
            val r = Rect()
            n.getBoundsInScreen(r)
            if (r.width() in minW..maxW && r.height() > r.width() &&
                r.top > m.heightPixels * 0.30f && (n.isClickable || n.isLongClickable)
            ) {
                found.add(r)
            }
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        walk(root)
        return found.sortedWith(compareBy({ it.top }, { it.left }))
    }

    private fun fail(reason: String) {
        lastFailDump = "FAILED: $reason (step=$step)\n" + dumpScreen()
        failures++
        skip++
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
            if (t.isNotEmpty() || d.isNotEmpty() || n.isClickable || n.isScrollable) {
                sb.append("  ".repeat(depth))
                    .append(n.className).append(" | id=").append(id)
                    .append(" | text=").append(t).append(" | desc=").append(d)
                    .append(" | clickable=").append(n.isClickable)
                    .append(" | scrollable=").append(n.isScrollable)
                    .append(" | bounds=").append(r.toShortString()).append('\n')
            }
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        walk(root, 0)
        return sb.toString()
    }
}
