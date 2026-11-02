package com.handgestures.mobile

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign

class OneEuro(private val mc: Float, private val beta: Float, private val dc: Float = 1f) {
    private var t = -1L
    private var x = 0f
    private var dx = 0f
    private fun alpha(fc: Float, dt: Float): Float {
        val tau = 1f / (2f * Math.PI.toFloat() * fc)
        return 1f / (1f + tau / dt)
    }
    fun f(v: Float, now: Long): Float {
        if (t < 0) { t = now; x = v; dx = 0f; return v }
        val dt = max(0.001f, (now - t) / 1000f)
        t = now
        dx += alpha(dc, dt) * ((v - x) / dt - dx)
        x += alpha(mc + beta * abs(dx), dt) * (v - x)
        return x
    }
}

private class Pt(var x: Float, var y: Float)
private class Sample(val t: Long, val x: Float, val y: Float, val open: Boolean)
private class Mp(val x: Float, val y: Float, val t: Long, val px: Float, val py: Float)
private class PI(val finger: String?, val r: Float, val margin: Float)

/**
 * عقل التطبيق: بياخد نقاط اليد كل إطار ويقرر ينفّذ إيه.
 * بيتنادى من thread واحد (نتيجة MediaPipe) فمفيش تزامن معقّد هنا.
 */
class GestureController(private val ex: ActionExecutor) {
    private val SPECIAL_HOLD = 700L
    private val DRAG_DELAY = 220L

    private val history = ArrayList<String>()
    private var current = "none"
    private var since = 0L
    private var lastFire = 0L
    private var fired = false

    @Volatile var paused = false
    @Volatile var touchMode = false

    // ---------- أدوات ----------
    private fun clamp01(v: Float) = min(1f, max(0f, v))

    private fun stable(g: String): String {
        history.add(g)
        if (history.size > 6) history.removeAt(0)
        val best = history.groupingBy { it }.eachCount().maxByOrNull { it.value }!!
        return if (best.value >= 4) best.key else current
    }

    private fun resumeLabel(): String? {
        val k = GESTURES.keys.firstOrNull { Prefs.map(it) == "resume_listening" } ?: return null
        val i = GESTURES[k]!!
        return i.emoji + " " + i.name
    }

    private fun live(t: String) { Bus.live = t }

    private fun fire(action: String) {
        val r = ex.perform(action)
        Bus.say(r.second, !r.first)
    }

    // ---------- الحالات ----------
    fun setPaused(p: Boolean) {
        if (p && resumeLabel() == null) {
            Bus.say("مفيش حركة متحدد لها \"استئناف الالتقاط\"، حدد واحدة الأول عشان متتعلقش.", true)
            return
        }
        if (p) { releaseTouch(); ex.pointer(0f, 0f, false, false) }
        paused = p
        Bus.paused = p
        history.clear()
        if (p) {
            live("⏸️ متوقف — اعمل " + resumeLabel() + " للاستئناف")
            Bus.say("الالتقاط متوقف، الكاميرا شغالة بس مش بتنفذ أوامر")
        } else Bus.say("شغّال ✅ — اعمل حركة قدام الكاميرا")
        ex.buzz()
    }

    fun togglePause() = setPaused(!paused)

    fun setTouchMode(on: Boolean) {
        if (on == touchMode) return
        if (on && !ex.hasA11y()) {
            Bus.say("فعّل خدمة إمكانية الوصول الأول (من شاشة التجهيز) عشان وضع اللمس يشتغل.", true)
            return
        }
        if (on && paused) setPaused(false)
        if (!on) releaseTouch()
        touchMode = on
        Bus.touchMode = on
        history.clear(); cur = null; curHist.clear(); fistDown = false; dragOff = null; scrollAnchor = null
        fx = null; fy = null; pinchOn = 0; pinchOff = 0; pinchArmed = false; rPrev = null
        closingUntil = 0; mPrev = null; latched = false; candFinger = null
        if (on) {
            Bus.say("وضع اللمس ✅ — حرّك إيدك للتحريك، 🤏 قرصة بالسبابة = لمس، 🤘 ثبّتها للخروج")
        } else {
            ex.pointer(0f, 0f, false, false)
            live("—")
            Bus.say("شغّال ✅ — اعمل حركة قدام الكاميرا")
        }
        ex.buzz(30)
    }

    fun toggleKeyboard() {
        val r = ex.perform("toggle_keyboard")
        Bus.say(r.second, !r.first)
    }

    fun shutdown() {
        releaseTouch()
        ex.pointer(0f, 0f, false, false)
    }

    // ---------- الدخول الرئيسي ----------
    fun onHand(h: Hand?, now: Long) {
        val raw = if (h != null) classifyGesture(h) else "none"
        val g = stable(raw)
        if (g != current) { current = g; since = now; fired = false; lastFire = 0 }
        val action = Prefs.map(g)
        val held = now - since

        if (paused) {
            live("⏸️ متوقف — اعمل " + resumeLabel() + " للاستئناف")
            if (action == "resume_listening" && held >= SPECIAL_HOLD && !fired) { fired = true; setPaused(false) }
            return
        }
        if (touchMode) { touchFrame(h, raw, g, now); return }

        val sw = detectSwipe(h, raw, now)
        if (sw != null) {
            val a = Prefs.map(sw)
            if (a != "none") { fired = true; runSpecial(a); return }
        }

        val info = GESTURES[g]
        live(if (info != null) info.emoji + " " + info.name else if (h != null) "…" else "مفيش إيد")

        if (action == "scroll") { scrollFrame(h, now); return }
        scrollAnchor = null

        when {
            action == "none" || action == "resume_listening" -> return
            action == "toggle_keyboard" -> { if (held >= SPECIAL_HOLD && !fired) { fired = true; toggleKeyboard() } }
            action == "pause_listening" -> { if (held >= SPECIAL_HOLD && !fired) { fired = true; setPaused(true) } }
            action == "toggle_touch" -> { if (held >= SPECIAL_HOLD && !fired) { fired = true; setTouchMode(true) } }
            REPEAT[action] != null -> {
                val r = REPEAT[action]!!
                if (held >= r.delay && now - lastFire >= r.every) { lastFire = now; fire(action) }
            }
            else -> { if (held >= 600 && !fired) { fired = true; fire(action) } }
        }
    }

    private fun runSpecial(a: String) {
        when (a) {
            "toggle_touch" -> setTouchMode(!touchMode)
            "toggle_keyboard" -> toggleKeyboard()
            "pause_listening" -> setPaused(true)
            "resume_listening" -> setPaused(false)
            else -> fire(a)
        }
    }

    // ---------- سحب الكف يمين / شمال ----------
    private val swipeBuf = ArrayList<Sample>()
    private var lastSwipe = 0L
    private var swipeLocked = false

    private fun detectSwipe(h: Hand?, raw: String, now: Long): String? {
        if (h == null) { swipeBuf.clear(); swipeLocked = false; return null }
        swipeBuf.add(Sample(now, h.x[9], h.y[9], raw == "open_palm"))
        while (swipeBuf.isNotEmpty() && now - swipeBuf[0].t > 500) swipeBuf.removeAt(0)

        if (swipeLocked) {
            val recent = swipeBuf.filter { now - it.t <= 300 }.map { it.x }
            if (now - lastSwipe > 700 && recent.size >= 3 && (recent.max() - recent.min()) < 0.07f) swipeLocked = false
            return null
        }
        if (swipeBuf.size < 4) return null
        val open = swipeBuf.count { it.open }.toFloat() / swipeBuf.size
        if (open < 0.6f) return null
        val a = swipeBuf.first()
        val b = swipeBuf.last()
        val dx = b.x - a.x
        val dy = b.y - a.y
        if (abs(dx) < 0.28f || abs(dy) > abs(dx) * 0.6f) return null
        lastSwipe = now; swipeLocked = true; swipeBuf.clear()
        return if (dx > 0) "swipe_right" else "swipe_left"
    }

    // ---------- التمرير (🤙 + حرّك إيدك لتحت/فوق) ----------
    private var scrollAnchor: Float? = null
    private var lastScrollAt = 0L

    private fun scrollFrame(h: Hand?, now: Long) {
        if (h == null) { scrollAnchor = null; return }
        val y = h.y[9]
        if (scrollAnchor == null) scrollAnchor = y
        val d = y - scrollAnchor!!
        val a = abs(d)
        if (a < 0.05f) { live("📜 تمرير — حرّك إيدك لتحت أو فوق"); return }
        val k = min(1f, (a - 0.05f) / 0.17f)
        val interval = (520 - 340 * k).toLong()
        live(if (d > 0) "📜 ⬇️ نزول" else "📜 ⬆️ طلوع")
        if (now - lastScrollAt >= interval) {
            lastScrollAt = now
            ex.scroll(if (d > 0) 1 else -1)
        }
    }

    // ---------- وضع اللمس ----------
    private var cur: Pt? = null
    private var lastSeen = 0L
    private var fx: OneEuro? = null
    private var fy: OneEuro? = null
    private var fistDown = false
    private var pressAt = 0L
    private var dragOff: Pt? = null
    private var pinchOn = 0
    private var pinchOff = 0
    private var pinchArmed = false
    private var rPrev: Float? = null
    private var closingUntil = 0L
    private var mPrev: Mp? = null
    private var latched = false
    private var candFinger: String? = null
    private var rawSpeedEma = 0f
    private val curHist = ArrayList<Triple<Long, Float, Float>>()

    private fun mkFilters() {
        val mc = 2.0f - 1.5f * Prefs.smooth
        fx = OneEuro(mc, 5f)
        fy = OneEuro(mc, 5f)
    }

    private fun pinchInfo(h: Hand): PI {
        val sc = h.d(0, 9).let { if (it > 0f) it else 1f }
        val all = PINCH_FINGERS.map { (f, info) -> Triple(f, h.d(4, info.tip) / sc, h.d(0, info.tip) / sc) }
        val elig = all.filter { it.third >= 0.85f }.sortedBy { it.second }
        val minAll = all.minOf { it.second }
        if (elig.isEmpty()) return PI(null, minAll, 0f)
        val best = elig[0]
        val others = all.filter { it.first != best.first }.map { it.second }
        return PI(best.first, best.second, others.min() - best.second)
    }

    private fun pressTouch(now: Long) {
        val c = cur ?: return
        fistDown = true; pressAt = now; dragOff = null
        ex.touchDown(c.x, c.y)
    }

    private fun releaseTouch() {
        pinchOn = 0; pinchOff = 0; candFinger = null; latched = false
        if (!fistDown) return
        fistDown = false; dragOff = null
        val c = cur
        if (c != null) ex.touchUp(c.x, c.y) else ex.touchUp(0.5f, 0.5f)
    }

    private fun doubleTap() {
        val c = cur ?: return
        latched = true
        ex.doubleTap(c.x, c.y)
        Bus.say("⚡ دبل تاب")
    }

    private fun touchFrame(h: Hand?, raw: String, g: String, now: Long) {
        val tAction = Prefs.touchMap(g)
        val rawAction = Prefs.touchMap(raw)
        val held = now - since
        val isExit = tAction == "toggle_touch"

        if (h == null) { // اليد اختفت: نفلت اللمس عشان ميفضلش مضغوط
            releaseTouch(); scrollAnchor = null; fx = null; fy = null; pinchArmed = false
            rPrev = null; mPrev = null; latched = false
            ex.pointer(0f, 0f, false, false)
            live("🖐️ وضع اللمس — مفيش إيد")
            return
        }

        val gap = now - lastSeen > 400
        if (fx == null || gap) mkFilters()
        val gain = Prefs.gain
        var cx = 0f; var cy = 0f
        for (i in intArrayOf(0, 5, 9, 13, 17)) { cx += h.x[i]; cy += h.y[i] }
        cx /= 5f; cy /= 5f
        val m = Pt(clamp01(0.5f + (fx!!.f(cx, now) - 0.5f) * gain), clamp01(0.5f + (fy!!.f(cy, now) - 0.5f) * gain))
        if (cur == null) cur = Pt(m.x, m.y)
        val c = cur!!

        val special = tAction != "none" || rawAction != "none"
        if (!fistDown && held >= SPECIAL_HOLD && !fired && tAction != "none" && tAction != "scroll") {
            fired = true
            if (isExit) { setTouchMode(false); return }
            runSpecial(tAction)
        }

        // سرعة اليد: لو بتحرّك بسرعة مفيش ضغط بالغلط
        val dtm = mPrev?.let { max(0.001f, (now - it.t) / 1000f) } ?: 1f
        val speed = mPrev?.let { hypot(m.x - it.x, m.y - it.y) / dtm } ?: 0f
        val rawSpeed = mPrev?.let { hypot((cx - it.px) * gain, (cy - it.py) * gain) / dtm } ?: 0f
        rawSpeedEma = if (mPrev != null) rawSpeedEma * 0.5f + rawSpeed * 0.5f else 0f
        mPrev = Mp(m.x, m.y, now, cx, cy)

        // القرصة
        var want: String? = null
        var release = false
        val pi = pinchInfo(h)
        val r = pi.r
        val prev = rPrev
        if (prev != null && !fistDown && !latched && r < prev - 0.04f && r < 0.75f) closingUntil = now + 250
        rPrev = r
        val near = r < 0.36f || now < closingUntil
        val ps = Prefs.pinchStrict
        val thr = 0.34f - 0.14f * ps
        val need = 2 + Math.round(ps * 2)
        if (!pinchArmed) {
            if (r > 0.42f) pinchArmed = true
        } else if (!fistDown && !latched) {
            val act = if (pi.finger != null) Prefs.pinch(pi.finger) else "none"
            val ok = !special && rawSpeedEma < 1.0f && pi.finger != null && r < thr && pi.margin >= 0.10f && act != "none"
            if (ok && pi.finger == candFinger) pinchOn++ else { pinchOn = if (ok) 1 else 0; candFinger = if (ok) pi.finger else null }
            if (pinchOn >= need) want = act
        } else {
            pinchOff = if (r > 0.42f) pinchOff + 1 else 0
            if (pinchOff >= 2) release = true
        }

        if (want != null && !isExit && !special) {
            when (want) {
                "touch" -> pressTouch(now)
                "double_tap" -> doubleTap()
                else -> { latched = true; fire(want) }
            }
        } else if (release) releaseTouch()

        val scrolling = !fistDown && tAction == "scroll"
        val freeze = !fistDown && (special || near || latched)

        var target: Pt? = null
        if (fistDown) {
            if (now - pressAt >= DRAG_DELAY) {
                if (dragOff == null) dragOff = Pt(c.x - m.x, c.y - m.y)
                target = Pt(clamp01(m.x + dragOff!!.x), clamp01(m.y + dragOff!!.y))
            }
        } else if (!freeze) target = m

        val dead = if (speed < 0.12f && !fistDown) 0.0018f else 0.0006f
        if (target != null && (gap || hypot(target.x - c.x, target.y - c.y) > dead)) {
            c.x = target.x; c.y = target.y
            if (fistDown) ex.touchMove(c.x, c.y)
        }
        lastSeen = now
        curHist.add(Triple(now, c.x, c.y)); if (curHist.size > 20) curHist.removeAt(0)
        ex.pointer(c.x, c.y, true, fistDown)

        if (scrolling) scrollFrame(h, now) else scrollAnchor = null

        // النص على الشاشة
        when {
            latched -> live("⚡ دبل تاب")
            fistDown -> live(if (now - pressAt < DRAG_DELAY) "🤏 لمس" else "🤏 إمساك / سحب")
            !scrolling -> {
                live(
                    when {
                        tAction == "toggle_keyboard" -> "⌨️ كيبورد… ثبّت الحركة"
                        isExit -> "🖐️ خروج من وضع اللمس… ثبّت الحركة"
                        tAction == "back" || tAction == "home" || tAction == "recents" -> "${TOUCH_ACTIONS[tAction]}… ثبّت الحركة"
                        else -> "🖐️ تحريك — 🤏 سبابة=لمس، وسطى=رجوع، بنصر=دبل تاب"
                    }
                )
            }
        }
    }
}
