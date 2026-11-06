package com.example.repostcleaner

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
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

        // words matched (case-insensitive, "contains") against node text and contentDescription
        val TAB_WORDS = listOf("reposts", "repost", "إعادات النشر", "إعادة النشر", "المعاد نشرها")
        val SHARE_WORDS = listOf("share", "مشاركة")
        val REMOVE_WORDS = listOf(
            "remove repost", "remove from reposts", "undo repost",
            "إزالة إعادة النشر", "إلغاء إعادة النشر", "إزالة المشاركة"
        )

        // where the first video of the grid sits, as a fraction of the screen
        const val GRID_X = 0.18f
        const val GRID_Y = 0.45f

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
        if (root == null) {
            status = "افتح تيك توك وروح لبروفايلك"
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
                step = Step.SHARE
                tries = 0
                tap(m.widthPixels * GRID_X, m.heightPixels * GRID_Y) { schedule() }
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
                    click(remove)
                    removed++
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

    private fun fail(reason: String) {
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
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null) return
            val t = n.text?.toString().orEmpty()
            val d = n.contentDescription?.toString().orEmpty()
            val id = n.viewIdResourceName.orEmpty()
            if (t.isNotEmpty() || d.isNotEmpty() || n.isClickable) {
                sb.append("  ".repeat(depth))
                    .append(n.className).append(" | id=").append(id)
                    .append(" | text=").append(t).append(" | desc=").append(d)
                    .append(" | clickable=").append(n.isClickable).append('\n')
            }
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        walk(root, 0)
        return sb.toString()
    }
}
