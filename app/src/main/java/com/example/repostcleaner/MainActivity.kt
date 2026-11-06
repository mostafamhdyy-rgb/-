package com.example.repostcleaner

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var counter: TextView
    private lateinit var status: TextView
    private lateinit var dump: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            counter.text = CleanerService.removed.toString()
            val ready = CleanerService.instance != null
            status.text = if (ready) CleanerService.status else "فعّل الخدمة من إعدادات إمكانية الوصول الأول"
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (20 * resources.displayMetrics.density).toInt()

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }

        counter = TextView(this).apply {
            text = "0"
            textSize = 84f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        val label = TextView(this).apply {
            text = "إعادات نشر اتشالت"
            textSize = 16f
            gravity = Gravity.CENTER
        }
        status = TextView(this).apply {
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(0, pad, 0, pad)
        }

        fun button(text: String, onClick: () -> Unit) = Button(this).apply {
            this.text = text
            setOnClickListener { onClick() }
        }

        val enable = button("١. فعّل الخدمة من إمكانية الوصول") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        val start = button("٢. ابدأ التنظيف (افتح بروفايلك في تيك توك الأول)") {
            val svc = CleanerService.instance
            if (svc == null) {
                Toast.makeText(this, "الخدمة مش متفعلة", Toast.LENGTH_SHORT).show()
            } else {
                svc.begin(500)
                val launch = packageManager.getLaunchIntentForPackage("com.zhiliaoapp.musically")
                    ?: packageManager.getLaunchIntentForPackage("com.ss.android.ugc.trill")
                if (launch != null) startActivity(launch)
            }
        }
        val stop = button("إيقاف") { CleanerService.instance?.stopWork("اتوقف يدوياً") }
        val diag = button("نسخ شاشة التشخيص (افتح الشاشة في تيك توك قبلها)") {
            val svc = CleanerService.instance
            if (svc == null) {
                Toast.makeText(this, "الخدمة مش متفعلة", Toast.LENGTH_SHORT).show()
            } else {
                // dump what is on screen after a short delay so TikTok is in front
                Toast.makeText(this, "افتح تيك توك، هيتنسخ بعد 5 ثواني", Toast.LENGTH_LONG).show()
                handler.postDelayed({
                    val text = svc.dumpScreen()
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("dump", text))
                    dump.text = text.take(3000)
                }, 5000)
            }
        }

        val note = TextView(this).apply {
            text = "يعمل على حسابك أنت فقط، وبيضغط على الشاشة زي ما تعمل بإيدك. استخدامه ممكن يخالف شروط تيك توك، فاستخدمه على مسؤوليتك."
            textSize = 12f
            setPadding(0, pad, 0, pad)
        }
        dump = TextView(this).apply {
            textSize = 10f
            typeface = Typeface.MONOSPACE
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }

        listOf(counter, label, status, enable, start, stop, diag, note, dump).forEach { col.addView(it) }
        setContentView(ScrollView(this).apply { addView(col) })
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
