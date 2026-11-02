package com.handgestures.mobile

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * خدمة في الواجهة الأمامية (Foreground Service) بتشغّل الكاميرا الأمامية وMediaPipe
 * وتفضل شغالة حتى لو انت فتحت تطبيق تاني.
 */
class GestureService : LifecycleService() {
    companion object {
        const val CH = "hg_running"
        const val A_STOP = "com.handgestures.mobile.STOP"
        const val A_PAUSE = "com.handgestures.mobile.PAUSE"
        const val A_TOUCH = "com.handgestures.mobile.TOUCH"
        const val A_KB = "com.handgestures.mobile.KB"
    }

    private lateinit var exec: ExecutorService
    private lateinit var ex: ActionExecutor
    private lateinit var controller: GestureController
    private var landmarker: HandLandmarker? = null
    private var provider: ProcessCameraProvider? = null
    @Volatile private var fw = 480f
    @Volatile private var fh = 640f
    private var lastTs = 0L
    private var frameN = 0
    private var started = false

    override fun onCreate() {
        super.onCreate()
        ex = ActionExecutor(this)
        controller = GestureController(ex)
        exec = Executors.newSingleThreadExecutor()
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CH, "التحكم شغّال", NotificationManager.IMPORTANCE_LOW))
        try {
            ServiceCompat.startForeground(this, 1, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } catch (e: Exception) {
            Bus.say("مقدرتش أشغّل الخدمة: " + (e.message ?: ""), true)
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            A_STOP -> { stopAll(); return START_NOT_STICKY }
            A_PAUSE -> controller.togglePause()
            A_TOUCH -> controller.setTouchMode(!controller.touchMode)
            A_KB -> controller.toggleKeyboard()
            else -> startCamera()
        }
        updateNotification()
        return START_NOT_STICKY
    }

    // ---------- الإشعار ----------
    private fun pi(action: String, code: Int): PendingIntent =
        PendingIntent.getService(
            this, code, Intent(this, GestureService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun buildNotification() = NotificationCompat.Builder(this, CH)
        .setSmallIcon(R.drawable.ic_stat_hand)
        .setContentTitle("Hand Gestures Control")
        .setContentText(if (controller.paused) "⏸️ متوقف مؤقتًا" else if (controller.touchMode) "🖐️ وضع اللمس شغّال" else "🖐️ التحكم بالحركات شغّال")
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setContentIntent(
            PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        )
        .addAction(0, if (controller.paused) "استئناف" else "إيقاف مؤقت", pi(A_PAUSE, 1))
        .addAction(0, "إغلاق", pi(A_STOP, 2))
        .build()

    private fun updateNotification() {
        try { (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(1, buildNotification()) } catch (_: Exception) {}
    }

    // ---------- الكاميرا و MediaPipe ----------
    private fun startCamera() {
        if (started) return
        started = true
        try {
            val opts = HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath("hand_landmarker.task").build())
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setNumHands(1)
                .setMinHandDetectionConfidence(0.6f)
                .setMinHandPresenceConfidence(0.5f)
                .setMinTrackingConfidence(0.5f)
                .setResultListener { res, _ -> onResult(res) }
                .setErrorListener { e -> Bus.say("خطأ في التتبع: " + e.message, true) }
                .build()
            landmarker = HandLandmarker.createFromOptions(this, opts)
        } catch (e: Exception) {
            Bus.say("مقدرتش أحمّل نموذج اليد: " + (e.message ?: ""), true)
            stopAll(); return
        }

        val f = ProcessCameraProvider.getInstance(this)
        f.addListener({
            try {
                val p = f.get()
                provider = p
                @Suppress("DEPRECATION")
                val analysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(480, 640))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                analysis.setAnalyzer(exec) { proxy -> analyze(proxy) }
                p.unbindAll()
                p.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
                Bus.running = true
                controller.setPaused(false)
                Bus.say("شغّال ✅ — اعمل حركة قدام الكاميرا")
            } catch (e: Exception) {
                Bus.say("مقدرتش أفتح الكاميرا: " + (e.message ?: ""), true)
                stopAll()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyze(proxy: ImageProxy) {
        try {
            val raw = proxy.toBitmap()
            val rot = proxy.imageInfo.rotationDegrees
            val m = Matrix().apply { postRotate(rot.toFloat()); postScale(-1f, 1f) } // مراية
            val b = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
            if (b !== raw) raw.recycle()
            fw = b.width.toFloat(); fh = b.height.toFloat()
            frameN++
            if ((Bus.activityVisible || Prefs.showMini) && frameN % 2 == 0) {
                Bus.frame = Bitmap.createScaledBitmap(b, 240, 320, false)
            }
            var ts = SystemClock.uptimeMillis()
            if (ts <= lastTs) ts = lastTs + 1
            lastTs = ts
            landmarker?.detectAsync(BitmapImageBuilder(b).build(), ts)
        } catch (_: Exception) {
        } finally {
            proxy.close()
        }
    }

    private fun onResult(res: HandLandmarkerResult) {
        try {
            val lm = res.landmarks().firstOrNull()
            var hand: Hand? = null
            if (lm != null && lm.size >= 21) {
                val xs = FloatArray(21) { lm[it].x() }
                val ys = FloatArray(21) { lm[it].y() }
                hand = Hand(xs, ys, fw, fh)
                val pts = FloatArray(42)
                for (i in 0 until 21) { pts[i * 2] = xs[i]; pts[i * 2 + 1] = ys[i] }
                Bus.pts = pts
            } else Bus.pts = null
            controller.onHand(hand, SystemClock.uptimeMillis())
            Bus.changed()
        } catch (_: Exception) {}
    }

    // ---------- الإيقاف ----------
    private fun stopAll() {
        try { controller.shutdown() } catch (_: Exception) {}
        try { provider?.unbindAll() } catch (_: Exception) {}
        try { landmarker?.close() } catch (_: Exception) {}
        landmarker = null
        started = false
        Bus.running = false; Bus.touchMode = false; Bus.paused = false; Bus.pts = null; Bus.frame = null
        Bus.pointerVisible = false
        Bus.live = "—"
        Bus.say("الكاميرا واقفة")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        try { provider?.unbindAll() } catch (_: Exception) {}
        try { landmarker?.close() } catch (_: Exception) {}
        exec.shutdown()
        Bus.running = false
        super.onDestroy()
    }
}
