package com.example.repostcleaner

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var counter: TextView
    private lateinit var status: TextView
    private lateinit var dump: TextView
    private lateinit var user: EditText
    private lateinit var logView: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            counter.text = CleanerService.removed.toString()
            logView.text = CleanerService.recent()
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
        user = EditText(this).apply {
            hint = "اسم حسابك في تيك توك (من غير @)"
            setSingleLine()
            setText(getSharedPreferences("p", MODE_PRIVATE).getString("user", ""))
        }
        logView = TextView(this).apply {
            textSize = 13f
            setPadding(0, pad / 2, 0, pad / 2)
        }
        val start = button("٢. ابدأ التنظيف") {
            val svc = CleanerService.instance
            val name = user.text.toString().trim().removePrefix("@")
            if (svc == null) {
                Toast.makeText(this, "الخدمة مش متفعلة", Toast.LENGTH_SHORT).show()
            } else {
                getSharedPreferences("p", MODE_PRIVATE).edit().putString("user", name).apply()
                svc.begin(500)
                openTikTok(name)
            }
        }
        val stop = button("إيقاف") { CleanerService.instance?.stopWork("اتوقف يدوياً") }
        val diag = button("نسخ شاشة التشخيص (بعد الضغط افتح تيك توك، 10 ثواني)") {
            val svc = CleanerService.instance
            if (svc == null) {
                Toast.makeText(this, "الخدمة مش متفعلة", Toast.LENGTH_SHORT).show()
            } else {
                // dump what is on screen after a short delay so TikTok is in front
                Toast.makeText(this, "افتح تيك توك دلوقتي وخليه قدامك، هيتنسخ بعد 10 ثواني", Toast.LENGTH_LONG).show()
                handler.postDelayed({
                    val text = svc.dumpScreen()
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("dump", text))
                    dump.text = text.take(3000)
                }, 10000)
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

        listOf(counter, label, status, enable, user, start, stop, logView, diag, note, dump).forEach { col.addView(it) }
        setContentView(ScrollView(this).apply { addView(col) })
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
