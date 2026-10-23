package com.oussama.island

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.*

class MainActivity : Activity() {

    private val prefs by lazy { getSharedPreferences("island", MODE_PRIVATE) }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        val sv = ScrollView(this).apply { addView(col) }
        setContentView(sv)

        col.addView(text("Dynamic Island", 26f, true))
        col.addView(text("المطور: oussama", 14f, false))

        col.addView(text("الخطوات", 18f, true, 20))
        col.addView(button("1) السماح بالظهور فوق التطبيقات") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        })
        col.addView(button("2) السماح بقراءة الإشعارات") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        })
        col.addView(button("3) تشغيل الجزيرة") {
            if (Settings.canDrawOverlays(this)) {
                startForegroundService(Intent(this, IslandService::class.java))
                Toast.makeText(this, "تم التشغيل", Toast.LENGTH_SHORT).show()
            } else Toast.makeText(this, "فعّل إذن الظهور فوق التطبيقات أولاً", Toast.LENGTH_LONG).show()
        })
        col.addView(button("إيقاف الجزيرة") { stopService(Intent(this, IslandService::class.java)) })
        col.addView(button("تجربة إشعار") {
            IslandService.instance?.showNotification("Dynamic Island", "تجربة الإشعار ✅ - oussama", null, null)
                ?: Toast.makeText(this, "شغّل الجزيرة أولاً", Toast.LENGTH_SHORT).show()
        })

        col.addView(text("المؤقت (بالدقائق)", 18f, true, 20))
        val min = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER; hint = "مثال: 5" }
        col.addView(min)
        col.addView(button("ابدأ المؤقت") {
            val m = min.text.toString().toIntOrNull() ?: 0
            if (m > 0 && Settings.canDrawOverlays(this)) {
                startForegroundService(Intent(this, IslandService::class.java)
                    .setAction(IslandService.ACTION_TIMER).putExtra(IslandService.EXTRA_SECONDS, m * 60))
            } else Toast.makeText(this, "أدخل رقماً وفعّل الإذن", Toast.LENGTH_SHORT).show()
        })

        col.addView(text("التخصيص", 18f, true, 20))
        col.addView(seek("العرض", "width", 100, 300, 160))
        col.addView(seek("الارتفاع", "height", 32, 80, 44))
        col.addView(seek("المسافة من الأعلى", "top", 0, 60, 12))

        val colors = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(Color.BLACK, 0xFF1C2A5A.toInt(), 0xFF4B1E78.toInt(), 0xFF7A1212.toInt(), 0xFF145A32.toInt()).forEach { c ->
            colors.addView(View(this).apply {
                setBackgroundColor(c)
                setOnClickListener { prefs.edit().putInt("color", c).apply(); IslandService.instance?.applyStyle() }
            }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(10); topMargin = dp(8) })
        }
        col.addView(text("اللون", 14f, false, 8))
        col.addView(colors)

        col.addView(CheckBox(this).apply {
            text = "إظهار حالة الشحن والبطارية"
            isChecked = prefs.getBoolean("battery", true)
            setOnCheckedChangeListener { _, v -> prefs.edit().putBoolean("battery", v).apply() }
        })

        col.addView(text("ملاحظة: بعد منح إذن الإشعارات أعد تشغيل الجزيرة لتعمل أزرار الموسيقى.", 12f, false, 16))
        col.addView(text("© oussama", 12f, false, 8))
    }

    private fun text(t: String, size: Float, bold: Boolean, top: Int = 0) = TextView(this).apply {
        text = t; textSize = size
        setTextColor(Color.BLACK)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, dp(top), 0, dp(4))
    }

    private fun button(t: String, f: () -> Unit) = Button(this).apply {
        text = t; setOnClickListener { f() }
    }

    private fun seek(label: String, key: String, min: Int, max: Int, def: Int): LinearLayout {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(text(label, 14f, false, 8))
        box.addView(SeekBar(this).apply {
            this.max = max - min
            progress = prefs.getInt(key, def) - min
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) {
                    prefs.edit().putInt(key, p + min).apply()
                    IslandService.instance?.applyStyle()
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        })
        return box
    }
}
