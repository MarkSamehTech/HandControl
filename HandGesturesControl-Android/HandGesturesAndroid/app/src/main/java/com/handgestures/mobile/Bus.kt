package com.handgestures.mobile

import android.graphics.Bitmap
import java.util.concurrent.CopyOnWriteArrayList

/** حالة مشتركة بين الخدمة والواجهة والمعاينة العائمة */
object Bus {
    @Volatile var frame: Bitmap? = null
    @Volatile var pts: FloatArray? = null          // 21 نقطة: x0,y0,x1,y1,… (0..1)
    @Volatile var live = "—"
    @Volatile var status = "الكاميرا واقفة"
    @Volatile var statusErr = false
    @Volatile var running = false
    @Volatile var paused = false
    @Volatile var touchMode = false
    @Volatile var activityVisible = false
    @Volatile var px = 0.5f
    @Volatile var py = 0.5f
    @Volatile var pointerVisible = false
    @Volatile var pointerDown = false

    val listeners = CopyOnWriteArrayList<() -> Unit>()
    fun changed() { for (l in listeners) l() }
    fun say(t: String, err: Boolean = false) { status = t; statusErr = err; changed() }
}
