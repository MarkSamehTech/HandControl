package com.handgestures.mobile

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/** معاينة الكاميرا (مراية) مع هيكل اليد، بنسبة 3:4 */
class CameraView(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    private val d = resources.displayMetrics.density
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF0A0B18.toInt() }
    private val bmp = Paint(Paint.FILTER_BITMAP_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 3 * d; strokeCap = Paint.Cap.ROUND }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF9CA3AF.toInt(); textSize = 14 * d; textAlign = Paint.Align.CENTER }
    private val clip = Path()

    override fun onMeasure(w: Int, h: Int) {
        val width = MeasureSpec.getSize(w)
        setMeasuredDimension(width, width * 4 / 3)
    }

    override fun onDraw(c: Canvas) {
        val r = RectF(0f, 0f, width.toFloat(), height.toFloat())
        clip.reset(); clip.addRoundRect(r, 16 * d, 16 * d, Path.Direction.CW)
        c.save()
        c.clipPath(clip)
        c.drawRect(r, bg)
        val f = Bus.frame
        if (Bus.running && f != null) {
            c.drawBitmap(f, null, r, bmp)
            val p = Bus.pts
            if (p != null && p.size >= 42) {
                line.color = if (Bus.paused) 0xFF888888.toInt() else if (Bus.touchMode) 0xFF22C55E.toInt() else 0xFF6366F1.toInt()
                val w = width.toFloat(); val h = height.toFloat()
                for (cn in CONNECTIONS) c.drawLine(p[cn[0] * 2] * w, p[cn[0] * 2 + 1] * h, p[cn[1] * 2] * w, p[cn[1] * 2 + 1] * h, line)
                for (i in 0 until 21) c.drawCircle(p[i * 2] * w, p[i * 2 + 1] * h, 4 * d, dot)
            }
        } else {
            c.drawText(if (Bus.running) "جاري تشغيل الكاميرا…" else "الكاميرا واقفة", width / 2f, height / 2f, txt)
        }
        c.restore()
    }
}
