package com.handgestures.mobile

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.AdapterView
import androidx.core.content.ContextCompat

class MainActivity : Activity() {
    private lateinit var cameraView: CameraView
    private lateinit var live: TextView
    private lateinit var status: TextView
    private lateinit var btnToggle: Button
    private lateinit var btnTouch: Button
    private val ui = Handler(Looper.getMainLooper())
    private var pending = false
    private val listener: () -> Unit = {
        if (!pending) { pending = true; ui.post { pending = false; refresh() } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        cameraView = findViewById(R.id.cameraView)
        live = findViewById(R.id.live)
        status = findViewById(R.id.status)
        btnToggle = findViewById(R.id.btnToggle)
        btnTouch = findViewById(R.id.btnTouch)

        // التجهيز
        findViewById<Button>(R.id.btnCam).setOnClickListener { requestPermissions(arrayOf(Manifest.permission.CAMERA), 1) }
        findViewById<Button>(R.id.btnA11y).setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        findViewById<Button>(R.id.btnNotif).setOnClickListener {
            if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
        }
        findViewById<Button>(R.id.btnAppInfo).setOnClickListener {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }

        // الأزرار
        btnToggle.setOnClickListener {
            if (Bus.running) startService(Intent(this, GestureService::class.java).setAction(GestureService.A_STOP))
            else startControl()
        }
        btnTouch.setOnClickListener {
            if (!Bus.running) { Bus.say("شغّل التحكم الأول.", true); return@setOnClickListener }
            startService(Intent(this, GestureService::class.java).setAction(GestureService.A_TOUCH))
        }
        findViewById<Button>(R.id.btnKb).setOnClickListener {
            if (!Bus.running) { Bus.say("شغّل التحكم الأول.", true); return@setOnClickListener }
            startService(Intent(this, GestureService::class.java).setAction(GestureService.A_KB))
        }
        findViewById<Button>(R.id.btnReset).setOnClickListener {
            Prefs.reset(); bindSettings(); buildMapping(); Bus.say("اتسترجعت الإعدادات الافتراضية.")
        }

        bindSettings()
        buildMapping()
    }

    override fun onStart() {
        super.onStart()
        Bus.activityVisible = true
        Bus.listeners.add(listener)
        refreshSetup(); refresh()
    }

    override fun onResume() { super.onResume(); refreshSetup() }

    override fun onStop() {
        Bus.activityVisible = false
        Bus.listeners.remove(listener)
        super.onStop()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshSetup()
        if (requestCode == 3 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startControl()
    }

    // ---------- التشغيل ----------
    private fun startControl() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 3); return
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
        }
        Bus.say("جاري تشغيل الكاميرا…")
        ContextCompat.startForegroundService(this, Intent(this, GestureService::class.java))
    }

    // ---------- التحديث ----------
    private fun a11yEnabled(): Boolean {
        if (HandAccessibilityService.instance != null) return true
        val s = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return s.contains("$packageName/")
    }

    private fun refreshSetup() {
        val cam = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val a11y = a11yEnabled()
        val notif = Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        findViewById<TextView>(R.id.txtCam).text = (if (cam) "✅ " else "⬜ ") + "الكاميرا"
        findViewById<TextView>(R.id.txtA11y).text = (if (a11y) "✅ " else "⬜ ") + "خدمة إمكانية الوصول (عشان التطبيق يلمس الشاشة بدالك)"
        findViewById<TextView>(R.id.txtNotif).text = (if (notif) "✅ " else "⬜ ") + "الإشعارات (علشان زرار الإيقاف السريع)"
        findViewById<Button>(R.id.btnCam).visibility = if (cam) View.GONE else View.VISIBLE
        findViewById<Button>(R.id.btnA11y).text = if (a11y) "الإعدادات" else "تفعيل"
        findViewById<Button>(R.id.btnNotif).visibility = if (notif) View.GONE else View.VISIBLE
        val hide = if (a11y) View.GONE else View.VISIBLE
        findViewById<TextView>(R.id.txtRestricted).visibility = hide
        findViewById<Button>(R.id.btnAppInfo).visibility = hide
    }

    private fun refresh() {
        live.text = Bus.live
        status.text = Bus.status
        status.setTextColor(if (Bus.statusErr) 0xFFF87171.toInt() else 0xFF9CA3AF.toInt())
        btnToggle.text = if (Bus.running) "إيقاف التحكم" else "تشغيل التحكم"
        btnTouch.text = if (Bus.touchMode) "🖐️ إيقاف وضع اللمس" else "🖐️ تشغيل وضع اللمس"
        cameraView.invalidate()
    }

    // ---------- الإعدادات ----------
    private fun bindSettings() {
        val lblGain = findViewById<TextView>(R.id.lblGain)
        val lblSmooth = findViewById<TextView>(R.id.lblSmooth)
        val lblPinch = findViewById<TextView>(R.id.lblPinch)
        val sGain = findViewById<SeekBar>(R.id.seekGain)
        val sSmooth = findViewById<SeekBar>(R.id.seekSmooth)
        val sPinch = findViewById<SeekBar>(R.id.seekPinch)

        fun gainTxt() { lblGain.text = "حساسية الحركة: " + String.format("%.1f", Prefs.gain) + "×" }
        fun smoothTxt() { lblSmooth.text = "التنعيم (أعلى = أثبت، أقل = أسرع استجابة): " + Math.round(Prefs.smooth * 100) + "%" }
        fun pinchTxt() { lblPinch.text = "صرامة القرصة (أعلى = ضغطات بالغلط أقل): " + Math.round(Prefs.pinchStrict * 100) + "%" }

        sGain.setOnSeekBarChangeListener(null); sSmooth.setOnSeekBarChangeListener(null); sPinch.setOnSeekBarChangeListener(null)
        sGain.progress = Math.round((Prefs.gain - 0.8f) * 100f)
        sSmooth.progress = Math.round(Prefs.smooth * 100f)
        sPinch.progress = Math.round(Prefs.pinchStrict * 100f)
        gainTxt(); smoothTxt(); pinchTxt()

        sGain.setOnSeekBarChangeListener(seek { Prefs.gain = 0.8f + it / 100f; gainTxt() })
        sSmooth.setOnSeekBarChangeListener(seek { Prefs.smooth = it / 100f; smoothTxt() })
        sPinch.setOnSeekBarChangeListener(seek { Prefs.pinchStrict = it / 100f; pinchTxt() })

        val cp = findViewById<CheckBox>(R.id.chkPointer)
        val cm = findViewById<CheckBox>(R.id.chkMini)
        val ch = findViewById<CheckBox>(R.id.chkHaptics)
        cp.setOnCheckedChangeListener(null); cm.setOnCheckedChangeListener(null); ch.setOnCheckedChangeListener(null)
        cp.isChecked = Prefs.showPointer; cm.isChecked = Prefs.showMini; ch.isChecked = Prefs.haptics
        cp.setOnCheckedChangeListener { _, v -> Prefs.showPointer = v }
        cm.setOnCheckedChangeListener { _, v -> Prefs.showMini = v }
        ch.setOnCheckedChangeListener { _, v -> Prefs.haptics = v }
    }

    private fun seek(on: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { if (fromUser) on(p) }
        override fun onStartTrackingTouch(s: SeekBar?) {}
        override fun onStopTrackingTouch(s: SeekBar?) {}
    }

    private fun buildMapping() {
        val m = findViewById<LinearLayout>(R.id.mapping); m.removeAllViews()
        for ((g, info) in GESTURES) {
            addRow(m, info.emoji + "  " + info.name, ACTIONS, Prefs.map(g)) { Prefs.setMap(g, it) }
        }
        val p = findViewById<LinearLayout>(R.id.pinchMapping); p.removeAllViews()
        for ((f, info) in PINCH_FINGERS) {
            addRow(p, "🤏  الإبهام + " + info.name, PINCH_ACTIONS, Prefs.pinch(f)) { Prefs.setPinch(f, it) }
        }
        val t = findViewById<LinearLayout>(R.id.touchMapping); t.removeAllViews()
        for (g in TOUCH_GESTURES) {
            val info = GESTURES[g]!!
            addRow(t, info.emoji + "  " + info.name, TOUCH_ACTIONS, Prefs.touchMap(g)) { Prefs.setTouchMap(g, it) }
        }
    }

    private fun addRow(box: LinearLayout, label: String, options: Map<String, String>, current: String, onPick: (String) -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }
        val tv = TextView(this).apply { text = label; setTextColor(0xFFE5E7EB.toInt()); textSize = 13f }
        val sp = Spinner(this)
        val keys = options.keys.toList()
        sp.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, options.values.toList())
        sp.setSelection(keys.indexOf(current).coerceAtLeast(0), false)
        sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(a: AdapterView<*>?, v: View?, pos: Int, id: Long) { onPick(keys[pos]) }
            override fun onNothingSelected(a: AdapterView<*>?) {}
        }
        row.addView(tv)
        row.addView(sp)
        box.addView(row)
    }

    private fun dp(v: Int) = Math.round(v * resources.displayMetrics.density)
}
