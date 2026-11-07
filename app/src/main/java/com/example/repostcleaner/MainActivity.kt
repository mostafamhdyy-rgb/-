package com.example.repostcleaner

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private val bg = Color.parseColor("#0B0B10")
    private val card = Color.parseColor("#16161E")
    private val accent = Color.parseColor("#8B7CFF")
    private val danger = Color.parseColor("#E5484D")
    private val muted = Color.parseColor("#9A9AAE")

    private lateinit var counter: TextView
    private lateinit var status: TextView
    private lateinit var stats: TextView
    private lateinit var bar: ProgressBar
    private lateinit var user: EditText
    private lateinit var primary: Button
    private lateinit var enable: Button
    private lateinit var logView: TextView
    private lateinit var dump: TextView
    private var batch = 50
    private var speed = 4
    private var chips: List<Pair<Int, TextView>> = emptyList()
    private var speedChips: List<Pair<Int, TextView>> = emptyList()

    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            val svc = CleanerService.instance
            val running = CleanerService.running
            counter.text = CleanerService.removed.toString()
            status.text = if (svc == null) "فعّل الخدمة الأول من إمكانية الوصول" else CleanerService.status
            enable.visibility = if (svc == null) View.VISIBLE else View.GONE
            primary.text = if (running) "إيقاف" else "إزالة كل إعادات النشر"
            primary.background = round(if (running) danger else accent, 28)
            bar.visibility = if (running) View.VISIBLE else View.INVISIBLE
            chips.forEach { (n, tv) ->
                tv.background = round(if (n == batch) accent else card, 20)
                tv.setTextColor(if (n == batch) Color.WHITE else muted)
            }
            speedChips.forEach { (n, tv) ->
                tv.background = round(if (n == speed) accent else card, 20)
                tv.setTextColor(if (n == speed) Color.WHITE else muted)
            }
            logView.text = CleanerService.recent().ifEmpty { "هنا هتظهر الفيديوهات اللي اتشالت" }

            val start = CleanerService.startedAt
            if (start == 0L) {
                stats.text = ""
            } else {
                val end = if (running) System.currentTimeMillis()
                else CleanerService.endedAt.takeIf { it > 0 } ?: System.currentTimeMillis()
                val sec = ((end - start) / 1000).toInt()
                val perMin = if (sec >= 10) CleanerService.removed * 60 / sec else 0
                stats.text = "الوقت %02d:%02d  ·  %d فيديو في الدقيقة".format(sec / 60, sec % 60, perMin)
            }
            handler.postDelayed(this, 500)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun round(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    private fun label(t: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = t
        textSize = size
        setTextColor(color)
        gravity = Gravity.CENTER
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun bigButton(t: String, fill: Int, onClick: () -> Unit) = Button(this).apply {
        text = t
        isAllCaps = false
        textSize = 16f
        setTextColor(Color.WHITE)
        background = round(fill, 28)
        stateListAnimator = null
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)).apply {
            topMargin = dp(12)
        }
    }

    private fun linkButton(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t
        isAllCaps = false
        textSize = 13f
        setTextColor(muted)
        background = null
        stateListAnimator = null
        setOnClickListener { onClick() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = dp(20)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, dp(48), pad, pad)
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setBackgroundColor(bg)
        }

        val title = label("منظّف الريبوستات", 22f, Color.WHITE, true)
        counter = label("0", 88f, Color.WHITE, true)
        val sub = label("إعادة نشر اتشالت", 15f, muted)
        stats = label("", 13f, muted)
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(accent)
            visibility = View.INVISIBLE
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6)).apply {
                topMargin = dp(12)
                bottomMargin = dp(12)
            }
        }
        status = label("", 15f, Color.WHITE)

        user = EditText(this).apply {
            hint = "اسم حسابك في تيك توك (من غير @)"
            setHintTextColor(muted)
            setTextColor(Color.WHITE)
            setSingleLine()
            background = round(card, 14)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setText(getSharedPreferences("p", MODE_PRIVATE).getString("user", ""))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(16)
            }
        }

        batch = getSharedPreferences("p", MODE_PRIVATE).getInt("batch", 50)
        val batchLabel = label("حجم الدفعة (بعد كل دفعة راحة 45 ثانية)", 12f, muted)
        batchLabel.setPadding(0, dp(16), 0, dp(6))
        val chipsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        chips = listOf(25, 50, 100).map { n ->
            val tv = label("$n", 15f, muted, true).apply {
                setPadding(dp(24), dp(8), dp(24), dp(8))
                setOnClickListener {
                    batch = n
                    getSharedPreferences("p", MODE_PRIVATE).edit().putInt("batch", n).apply()
                }
            }
            chipsRow.addView(tv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(6)
                marginEnd = dp(6)
            })
            n to tv
        }

        speed = getSharedPreferences("p", MODE_PRIVATE).getInt("speed", 4)
        val speedLabel = label("السرعة (الأسرع ممكن يفوّت فيديوهات على موبايل بطيء)", 12f, muted)
        speedLabel.setPadding(0, dp(12), 0, dp(6))
        val speedRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        speedChips = listOf(1 to "عادي", 2 to "سريع", 3 to "أسرع", 4 to "أقصى").map { (n, name) ->
            val tv = label(name, 15f, muted, true).apply {
                setPadding(dp(14), dp(8), dp(14), dp(8))
                setOnClickListener {
                    speed = n
                    getSharedPreferences("p", MODE_PRIVATE).edit().putInt("speed", n).apply()
                }
            }
            speedRow.addView(tv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(6)
                marginEnd = dp(6)
            })
            n to tv
        }

        primary = bigButton("إزالة كل إعادات النشر", accent) {
            val svc = CleanerService.instance
            if (CleanerService.running) {
                svc?.stopWork("اتوقف يدوياً")
            } else if (svc == null) {
                Toast.makeText(this, "الخدمة مش متفعلة", Toast.LENGTH_SHORT).show()
            } else {
                val name = user.text.toString().trim().removePrefix("@")
                getSharedPreferences("p", MODE_PRIVATE).edit().putString("user", name).apply()
                CleanerService.batch = batch
                CleanerService.speed = speed
                svc.begin(5000)
                openTikTok(name)
            }
        }
        enable = bigButton("تفعيل الخدمة من إمكانية الوصول", card) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        logView = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.parseColor("#D8D8E6"))
            background = round(card, 14)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(16)
            }
        }

        val lastFail = linkButton("نسخ تقرير آخر فشل") {
            val text = CleanerService.lastFailDump.ifEmpty { "مفيش فشل متسجل لسه" }
            copy(text)
        }
        val diag = linkButton("نسخ شاشة التشخيص (10 ثواني)") {
            val svc = CleanerService.instance
            if (svc == null) {
                Toast.makeText(this, "الخدمة مش متفعلة", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "افتح تيك توك دلوقتي، هيتنسخ بعد 10 ثواني", Toast.LENGTH_LONG).show()
                handler.postDelayed({ copy(svc.dumpScreen()) }, 10000)
            }
        }
        val note = label(
            "يعمل على حسابك أنت فقط، وبيضغط على الشاشة زي ما تعمل بإيدك. تيك توك بيحدّ عدد الحذف أحياناً، فلو وقف كمّل بعدين. استخدامه ممكن يخالف شروط تيك توك، فاستخدمه على مسؤوليتك.",
            11f, muted
        )
        note.setPadding(0, dp(12), 0, dp(4))
        dump = TextView(this).apply {
            textSize = 10f
            typeface = Typeface.MONOSPACE
            setTextColor(muted)
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }

        listOf(title, counter, sub, stats, bar, status, user, batchLabel, chipsRow, speedLabel, speedRow, primary, enable, logView, lastFail, diag, note, dump)
            .forEach { col.addView(it) }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(bg)
            isFillViewport = true
            addView(col)
        })
    }

    private fun copy(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("report", text))
        dump.text = text.take(3000)
        Toast.makeText(this, "اتنسخ، الصقه في المحادثة", Toast.LENGTH_SHORT).show()
    }

    private fun openTikTok(name: String) {
        val pkgs = listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill")
        val installed = pkgs.firstOrNull { packageManager.getLaunchIntentForPackage(it) != null }
        val plain = installed?.let { packageManager.getLaunchIntentForPackage(it) }
        val direct = if (name.isNotEmpty()) {
            Intent(Intent.ACTION_VIEW, Uri.parse("https://www.tiktok.com/@$name")).apply {
                if (installed != null) setPackage(installed)
            }
        } else null
        val intent = direct ?: plain
        if (intent == null) {
            Toast.makeText(this, "تيك توك مش متثبت", Toast.LENGTH_LONG).show()
            return
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            if (plain != null) startActivity(plain)
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresh)
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
    }
}
