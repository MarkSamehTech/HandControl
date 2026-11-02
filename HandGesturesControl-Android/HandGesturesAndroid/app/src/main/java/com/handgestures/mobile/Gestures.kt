package com.handgestures.mobile

import kotlin.math.hypot

/** 21 نقطة لليد (MediaPipe) بنسب 0..1 + أبعاد الصورة بالبكسل (الصورة معكوسة زي المراية) */
class Hand(val x: FloatArray, val y: FloatArray, val w: Float, val h: Float) {
    fun d(a: Int, b: Int): Float = hypot((x[a] - x[b]) * w, (y[a] - y[b]) * h)
    fun px(i: Int): Float = x[i] * w
    fun py(i: Int): Float = y[i] * h
}

data class GInfo(val emoji: String, val name: String)
data class PF(val tip: Int, val name: String)

val GESTURES = linkedMapOf(
    "thumb_up" to GInfo("👍", "لايك"),
    "thumb_down" to GInfo("👎", "ديسلايك"),
    "open_palm" to GInfo("✋", "كف مفتوح"),
    "fist" to GInfo("✊", "قبضة"),
    "victory" to GInfo("✌️", "علامة النصر"),
    "point" to GInfo("☝️", "صباع السبابة"),
    "rock" to GInfo("🤘", "روك (السبابة والخنصر)"),
    "three" to GInfo("🖖", "تلات أصابع"),
    "ok" to GInfo("👌", "أوكي (الإبهام والسبابة ملتصقين)"),
    "pinky" to GInfo("🤙", "الخنصر لوحده"),
    "swipe_right" to GInfo("👉", "سحب الكف المفتوح لليمين"),
    "swipe_left" to GInfo("👈", "سحب الكف المفتوح لليسار")
)

val ACTIONS = linkedMapOf(
    "none" to "— بدون —",
    "volume_up" to "🔊 رفع الصوت",
    "volume_down" to "🔉 خفض الصوت",
    "toggle_mute" to "🔇 كتم / إلغاء الكتم",
    "play_pause" to "⏯️ تشغيل / إيقاف الميديا",
    "media_next" to "⏭️ المقطع التالي",
    "media_prev" to "⏮️ المقطع السابق",
    "seek_forward" to "⏩ تقديم",
    "seek_back" to "⏪ رجوع (ميديا)",
    "next_video" to "⏬ الفيديو التالي (ريلز / تيك توك)",
    "prev_video" to "⏫ الفيديو السابق",
    "scroll" to "📜 تمرير (حرّك إيدك لتحت/فوق)",
    "scroll_down" to "⬇️ نزّل الصفحة",
    "scroll_up" to "⬆️ طلّع الصفحة",
    "swipe_left_screen" to "◀️ اسحب الشاشة لليسار",
    "swipe_right_screen" to "▶️ اسحب الشاشة لليمين",
    "double_tap_center" to "❤️ دبل تاب في نص الشاشة (لايك)",
    "back" to "↩️ رجوع",
    "home" to "🏠 الرئيسية",
    "recents" to "🗂️ التطبيقات الأخيرة",
    "notifications" to "🔔 الإشعارات",
    "quick_settings" to "⚙️ الإعدادات السريعة",
    "screenshot" to "📸 لقطة شاشة",
    "lock_screen" to "🔒 قفل الشاشة",
    "toggle_touch" to "🖐️ وضع اللمس (تشغيل / إيقاف)",
    "toggle_keyboard" to "⌨️ كيبورد الموبايل (إظهار / إخفاء)",
    "pause_listening" to "⏸️ إيقاف الالتقاط (الكاميرا تفضل شغالة)",
    "resume_listening" to "▶️ استئناف الالتقاط"
)

class Rep(val delay: Long, val every: Long)
val REPEAT = mapOf(
    "volume_up" to Rep(350, 160),
    "volume_down" to Rep(350, 160),
    "seek_forward" to Rep(500, 700),
    "seek_back" to Rep(500, 700),
    "scroll_down" to Rep(400, 450),
    "scroll_up" to Rep(400, 450)
)

val TOUCH_GESTURES = listOf("victory", "point", "rock", "three", "pinky")
val TOUCH_ACTIONS = linkedMapOf(
    "none" to ACTIONS["none"]!!,
    "scroll" to ACTIONS["scroll"]!!,
    "toggle_keyboard" to ACTIONS["toggle_keyboard"]!!,
    "back" to ACTIONS["back"]!!,
    "home" to ACTIONS["home"]!!,
    "recents" to ACTIONS["recents"]!!,
    "toggle_touch" to "🖐️ الخروج من وضع اللمس"
)

val PINCH_FINGERS = linkedMapOf(
    "index" to PF(8, "السبابة"),
    "middle" to PF(12, "الوسطى"),
    "ring" to PF(16, "البنصر"),
    "pinky" to PF(20, "الخنصر")
)
val PINCH_ACTIONS = linkedMapOf(
    "touch" to "👆 لمس (ضغطة / إمساك / سحب)",
    "double_tap" to "⚡ دبل تاب",
    "back" to "↩️ رجوع",
    "home" to "🏠 الرئيسية",
    "recents" to "🗂️ التطبيقات الأخيرة",
    "none" to "— مفيش —"
)

val DEFAULT_MAP = mapOf(
    "thumb_up" to "volume_up", "thumb_down" to "volume_down", "open_palm" to "play_pause",
    "fist" to "toggle_mute", "victory" to "next_video", "point" to "prev_video",
    "rock" to "pause_listening", "three" to "resume_listening", "ok" to "toggle_touch",
    "pinky" to "scroll", "swipe_right" to "back", "swipe_left" to "recents"
)
val DEFAULT_TOUCH = mapOf(
    "victory" to "toggle_keyboard", "pinky" to "scroll", "rock" to "toggle_touch",
    "point" to "back", "three" to "home"
)
val DEFAULT_PINCH = mapOf("index" to "touch", "middle" to "back", "ring" to "double_tap", "pinky" to "none")

/** تصنيف حركة اليد من النقاط (نفس منطق نسخة الكمبيوتر) */
fun classifyGesture(h: Hand): String {
    val scale = h.d(0, 9).let { if (it > 0f) it else 1f }
    fun ext(tip: Int, pip: Int) = h.d(tip, 0) > h.d(pip, 0) * 1.1f
    val index = ext(8, 6)
    val middle = ext(12, 10)
    val ring = ext(16, 14)
    val pinky = ext(20, 18)
    val thumbExt = h.d(4, 17) > h.d(3, 17) && h.d(4, 5) > 0.55f * scale

    if (middle && ring && pinky && h.d(4, 8) < 0.35f * scale) return "ok"
    if (index && middle && ring && pinky) return "open_palm"
    if (index && middle && !ring && !pinky) return "victory"
    if (index && !middle && !ring && !pinky) return "point"
    if (index && !middle && !ring && pinky) return "rock"
    if (index && middle && ring && !pinky) return "three"
    if (!index && !middle && !ring && pinky) return "pinky"
    if (!index && !middle && !ring && !pinky) {
        val tipY = h.py(4)
        val knY = h.py(5)
        if (thumbExt && tipY < knY - 0.3f * scale) return "thumb_up"
        if (thumbExt && tipY > knY + 0.3f * scale) return "thumb_down"
        return "fist"
    }
    return "none"
}
