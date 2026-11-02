package com.handgestures.mobile

import android.content.Context
import android.media.AudioManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.KeyEvent

/** بينفّذ الأوامر: صوت وميديا مباشرة، واللمس وأوامر النظام عن طريق خدمة إمكانية الوصول */
class ActionExecutor(private val ctx: Context) {
    private val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    @Suppress("DEPRECATION")
    private val vib = ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator

    private fun svc() = HandAccessibilityService.instance
    fun hasA11y() = svc() != null

    fun buzz(ms: Long = 18) {
        if (!Prefs.haptics) return
        try { vib.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE)) } catch (_: Exception) {}
    }

    private fun media(code: Int) {
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    private val needA11y = Pair(false, "فعّل خدمة إمكانية الوصول الأول (من شاشة التجهيز).")

    private fun a11y(block: (HandAccessibilityService) -> Pair<Boolean, String>): Pair<Boolean, String> {
        val s = svc() ?: return needA11y
        return block(s)
    }

    /** بيرجّع (نجح؟، النص اللي يظهر للمستخدم) */
    fun perform(name: String): Pair<Boolean, String> {
        val r: Pair<Boolean, String> = when (name) {
            "volume_up" -> { am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI); Pair(true, "🔊 رفع الصوت") }
            "volume_down" -> { am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI); Pair(true, "🔉 خفض الصوت") }
            "toggle_mute" -> { am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, AudioManager.FLAG_SHOW_UI); Pair(true, "🔇 كتم / رجوع الصوت") }
            "play_pause" -> { media(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE); Pair(true, "⏯️ تشغيل / إيقاف") }
            "media_next" -> { media(KeyEvent.KEYCODE_MEDIA_NEXT); Pair(true, "⏭️ التالي") }
            "media_prev" -> { media(KeyEvent.KEYCODE_MEDIA_PREVIOUS); Pair(true, "⏮️ السابق") }
            "seek_forward" -> { media(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD); Pair(true, "⏩ تقديم") }
            "seek_back" -> { media(KeyEvent.KEYCODE_MEDIA_REWIND); Pair(true, "⏪ رجوع") }
            "next_video" -> a11y { it.swipe(0.5f, 0.78f, 0.5f, 0.22f, 130); Pair(true, "⏬ الفيديو التالي") }
            "prev_video" -> a11y { it.swipe(0.5f, 0.22f, 0.5f, 0.78f, 130); Pair(true, "⏫ الفيديو السابق") }
            "scroll_down" -> a11y { it.swipe(0.5f, 0.68f, 0.5f, 0.32f, 220); Pair(true, "⬇️ نزول") }
            "scroll_up" -> a11y { it.swipe(0.5f, 0.32f, 0.5f, 0.68f, 220); Pair(true, "⬆️ طلوع") }
            "swipe_left_screen" -> a11y { it.swipe(0.8f, 0.5f, 0.2f, 0.5f, 160); Pair(true, "◀️ سحب لليسار") }
            "swipe_right_screen" -> a11y { it.swipe(0.2f, 0.5f, 0.8f, 0.5f, 160); Pair(true, "▶️ سحب لليمين") }
            "double_tap_center" -> a11y { it.doubleTap(0.5f, 0.5f); Pair(true, "❤️ دبل تاب") }
            "back" -> a11y { it.globalAct("back"); Pair(true, "↩️ رجوع") }
            "home" -> a11y { it.globalAct("home"); Pair(true, "🏠 الرئيسية") }
            "recents" -> a11y { it.globalAct("recents"); Pair(true, "🗂️ التطبيقات الأخيرة") }
            "notifications" -> a11y { it.globalAct("notifications"); Pair(true, "🔔 الإشعارات") }
            "quick_settings" -> a11y { it.globalAct("quick_settings"); Pair(true, "⚙️ الإعدادات السريعة") }
            "screenshot" -> a11y { if (it.globalAct("screenshot")) Pair(true, "📸 لقطة شاشة") else Pair(false, "لقطة الشاشة محتاجة أندرويد 9+") }
            "lock_screen" -> a11y { if (it.globalAct("lock_screen")) Pair(true, "🔒 قفل الشاشة") else Pair(false, "قفل الشاشة محتاج أندرويد 9+") }
            "toggle_keyboard" -> a11y { it.toggleKeyboard() }
            else -> Pair(false, "أمر غير معروف")
        }
        if (r.first) buzz()
        return r
    }

    // ---------- اللمس ----------
    fun touchDown(x: Float, y: Float) { svc()?.touchDown(x, y); buzz(12) }
    fun touchMove(x: Float, y: Float) { svc()?.touchMove(x, y) }
    fun touchUp(x: Float, y: Float) { svc()?.touchUp(x, y) }
    fun doubleTap(x: Float, y: Float) { svc()?.doubleTap(x, y); buzz(20) }
    fun scroll(dir: Int) { perform(if (dir > 0) "scroll_down" else "scroll_up") }

    fun pointer(x: Float, y: Float, visible: Boolean, down: Boolean) {
        Bus.px = x; Bus.py = y; Bus.pointerVisible = visible; Bus.pointerDown = down
    }
}
