package com.handgestures.mobile

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * الخدمة اللي بتحقن اللمسات وأوامر النظام بدل إيدك.
 * أندرويد مبيسمحش لتطبيق يلمس الشاشة غير عن طريق خدمة إمكانية الوصول.
 */
class HandAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile var instance: HandAccessibilityService? = null
    }

    private val ui = Handler(Looper.getMainLooper())
    private var overlay: OverlayView? = null
    private val busListener: () -> Unit = { overlay?.postInvalidateOnAnimation() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        addOverlay()
        Bus.listeners.add(busListener)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        Bus.listeners.remove(busListener)
        removeOverlay()
        instance = null
        super.onDestroy()
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        Bus.listeners.remove(busListener)
        removeOverlay()
        instance = null
        return super.onUnbind(intent)
    }

    // ---------- الطبقة العائمة (نقطة التتبع + المعاينة الصغيرة) ----------
    private fun addOverlay() {
        try {
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            val v = OverlayView(this)
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            )
            wm.addView(v, lp)
            overlay = v
        } catch (_: Exception) {}
    }

    private fun removeOverlay() {
        try {
            overlay?.let { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) }
        } catch (_: Exception) {}
        overlay = null
    }

    // ---------- أبعاد الشاشة ----------
    private fun screen(): DisplayMetrics {
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(dm)
        return dm
    }
    private fun px(nx: Float): Float = nx.coerceIn(0f, 1f) * (screen().widthPixels - 1)
    private fun py(ny: Float): Float = ny.coerceIn(0f, 1f) * (screen().heightPixels - 1)

    // ---------- لمسة واحدة / دبل تاب / سحبة ----------
    fun doubleTap(nx: Float, ny: Float) {
        val x = px(nx); val y = py(ny)
        val a = Path().apply { moveTo(x, y) }
        val b = Path().apply { moveTo(x, y) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(a, 0, 40))
            .addStroke(GestureDescription.StrokeDescription(b, 130, 40))
            .build()
        dispatchGesture(g, null, null)
    }

    fun swipe(nx1: Float, ny1: Float, nx2: Float, ny2: Float, durationMs: Long) {
        val p = Path().apply { moveTo(px(nx1), py(ny1)); lineTo(px(nx2), py(ny2)) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, durationMs))
            .build()
        dispatchGesture(g, null, null)
    }

    // ---------- لمس مستمر (ضغطة / إمساك / سحب) ----------
    // بنبعت اللمسة على قطع صغيرة متصلة (continueStroke) عشان الإصبع الوهمي يتبع إيدك لحظة بلحظة
    private var stroke: GestureDescription.StrokeDescription? = null
    private var lastX = 0f
    private var lastY = 0f
    private var tx = 0f
    private var ty = 0f
    private var touching = false
    private var busy = false
    private var endRequested = false
    private var finalSent = false

    fun touchDown(nx: Float, ny: Float) {
        ui.post {
            tx = px(nx); ty = py(ny); lastX = tx; lastY = ty
            stroke = null; touching = true; busy = false; endRequested = false; finalSent = false
            sendSegment(true)
        }
    }

    fun touchMove(nx: Float, ny: Float) {
        ui.post {
            tx = px(nx); ty = py(ny)
            if (touching && !busy && !endRequested) sendSegment(false)
        }
    }

    fun touchUp(nx: Float, ny: Float) {
        ui.post {
            if (!touching) return@post
            tx = px(nx); ty = py(ny)
            endRequested = true
            if (!busy) sendSegment(false)
        }
    }

    private fun sendSegment(first: Boolean) {
        val path = Path().apply {
            moveTo(lastX, lastY)
            if (tx != lastX || ty != lastY) lineTo(tx, ty)
        }
        val willContinue = !endRequested
        if (endRequested) finalSent = true
        val dur = if (first) 60L else 40L
        val prev = stroke
        val s = if (first || prev == null) GestureDescription.StrokeDescription(path, 0, dur, willContinue)
        else prev.continueStroke(path, 0, dur, willContinue)
        stroke = s
        lastX = tx; lastY = ty
        busy = true
        val ok = dispatchGesture(
            GestureDescription.Builder().addStroke(s).build(),
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    busy = false
                    if (!touching) return
                    if (finalSent) { touching = false; stroke = null; finalSent = false; endRequested = false; return }
                    sendSegment(false)
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    busy = false; touching = false; stroke = null; finalSent = false; endRequested = false
                }
            }, null
        )
        if (!ok) { busy = false; touching = false; stroke = null; finalSent = false; endRequested = false }
    }

    // ---------- أوامر النظام ----------
    fun globalAct(name: String): Boolean {
        val code = when (name) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> GLOBAL_ACTION_QUICK_SETTINGS
            "lock_screen" -> if (Build.VERSION.SDK_INT >= 28) GLOBAL_ACTION_LOCK_SCREEN else return false
            "screenshot" -> if (Build.VERSION.SDK_INT >= 28) GLOBAL_ACTION_TAKE_SCREENSHOT else return false
            else -> return false
        }
        return performGlobalAction(code)
    }

    // ---------- كيبورد الموبايل نفسه ----------
    private fun findEditable(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (n == null) return null
        if (n.isEditable && n.isVisibleToUser) return n
        for (i in 0 until n.childCount) {
            val r = findEditable(n.getChild(i))
            if (r != null) return r
        }
        return null
    }

    fun toggleKeyboard(): Pair<Boolean, String> {
        val shown = windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        if (shown) {
            val c = softKeyboardController
            c.setShowMode(AccessibilityService.SHOW_MODE_HIDDEN)
            ui.postDelayed({ c.setShowMode(AccessibilityService.SHOW_MODE_AUTO) }, 700)
            return Pair(true, "⌨️ الكيبورد اتقفل")
        }
        val n = findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: findEditable(rootInActiveWindow)
        if (n != null) {
            n.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            return Pair(true, "⌨️ الكيبورد اتفتح")
        }
        return Pair(false, "مفيش خانة كتابة ظاهرة. اضغط على خانة بالقرصة وهيظهر الكيبورد لوحده.")
    }
}

/** بيرسم نقطة التتبع (وضع اللمس) ومعاينة الكاميرا الصغيرة فوق كل التطبيقات */
class OverlayView(ctx: android.content.Context) : View(ctx) {
    private val d = resources.displayMetrics.density
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3 * d; color = 0xFF22C55E.toInt() }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = 0x88F59E0B.toInt() }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.WHITE }
    private val bmp = Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = 225 }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 2 * d; color = 0xFF6366F1.toInt() }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2 * d; color = 0xFFFFFFFF.toInt() }

    override fun onDraw(c: Canvas) {
        if (Bus.touchMode && Prefs.showPointer && Bus.pointerVisible) {
            val x = Bus.px * width
            val y = Bus.py * height
            if (Bus.pointerDown) c.drawCircle(x, y, 22 * d, fill)
            c.drawCircle(x, y, 16 * d, ring)
            c.drawCircle(x, y, 3 * d, dot)
        }
        val f = Bus.frame
        if (Prefs.showMini && Bus.running && f != null) {
            val w = 100 * d
            val h = w * 4f / 3f
            val l = 10 * d
            val t = 60 * d
            val r = RectF(l, t, l + w, t + h)
            val save = c.save()
            c.clipRect(r)
            c.drawBitmap(f, null, r, bmp)
            val p = Bus.pts
            if (p != null && p.size >= 42) {
                line.color = if (Bus.paused) 0xFF888888.toInt() else if (Bus.touchMode) 0xFF22C55E.toInt() else 0xFF6366F1.toInt()
                for (cn in CONNECTIONS) {
                    c.drawLine(l + p[cn[0] * 2] * w, t + p[cn[0] * 2 + 1] * h, l + p[cn[1] * 2] * w, t + p[cn[1] * 2 + 1] * h, line)
                }
            }
            c.restoreToCount(save)
            c.drawRect(r, border)
        }
    }
}

val CONNECTIONS = arrayOf(
    intArrayOf(0, 1), intArrayOf(1, 2), intArrayOf(2, 3), intArrayOf(3, 4), intArrayOf(0, 5), intArrayOf(5, 6),
    intArrayOf(6, 7), intArrayOf(7, 8), intArrayOf(5, 9), intArrayOf(9, 10), intArrayOf(10, 11), intArrayOf(11, 12),
    intArrayOf(9, 13), intArrayOf(13, 14), intArrayOf(14, 15), intArrayOf(15, 16), intArrayOf(13, 17),
    intArrayOf(17, 18), intArrayOf(18, 19), intArrayOf(19, 20), intArrayOf(0, 17)
)
