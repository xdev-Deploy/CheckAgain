package dev.hjun.checkagain

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import kotlin.math.sin

internal fun mix(a: Int, b: Int, t: Float): Int {
    fun ch(x: Int, shift: Int) = (x shr shift) and 255
    val r = (ch(a, 16) * (1 - t) + ch(b, 16) * t).toInt()
    val g = (ch(a, 8) * (1 - t) + ch(b, 8) * t).toInt()
    val bl = (ch(a, 0) * (1 - t) + ch(b, 0) * t).toInt()
    return Color.rgb(r, g, bl)
}

/**
 * 차량을 위에서 본 그림. 위쪽이 차 앞이고 좌석 다섯 곳과 트렁크가 구분된다.
 * 운전석은 등록 대상이 아니므로 앉았는지 여부만 색으로 보여 준다.
 */
class CarView(context: Context) : View(context) {

    private var bySeat: Map<String, Person> = emptyMap()
    private var occupied: Map<String, Boolean> = emptyMap()

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val rect = RectF()
    private val path = Path()

    // 눌러 볼 수 있는 좌석 영역. 운전석과 트렁크를 포함하며 매 프레임 다시 채운다.
    private val hits = LinkedHashMap<String, RectF>()
    var onSeatTap: ((String) -> Unit)? = null

    private val cSurface = context.getColor(R.color.c_surface)
    private val cBody = context.getColor(R.color.c_surface2)
    private val cLine = context.getColor(R.color.c_line)
    private val cText = context.getColor(R.color.c_text)
    private val cText2 = context.getColor(R.color.c_text2)
    private val cBrand = context.getColor(R.color.c_brand)
    private val cSoft = context.getColor(R.color.c_brand_soft)
    private val cOnBrand = context.getColor(R.color.c_on_brand)
    private val cCabin = mix(cBody, cSurface, 0.55f)

    fun setData(people: List<Person>, seats: Map<String, Boolean>) {
        bySeat = people.filter { it.seat != null }.associateBy { it.seat!! }
        occupied = seats
        invalidate()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val hit = hits.entries.firstOrNull { (_, r) ->
            RectF(r).apply { inset(-12f, -12f) }.contains(e.x, e.y)
        }?.key
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> return hit != null
            MotionEvent.ACTION_UP -> if (hit != null) {
                performClick()
                onSeatTap?.invoke(hit)
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    override fun performClick(): Boolean = super.performClick()

    override fun onDraw(canvas: Canvas) {
        hits.clear()
        val ratio = 0.56f
        var h = height * 0.84f
        var w = h * ratio
        if (w > width * 0.82f) {
            w = width * 0.82f
            h = w / ratio
        }
        val left = (width - w) / 2f
        val top = (height - h) / 2f
        line.strokeWidth = (w * 0.014f).coerceAtLeast(2f)

        // 바퀴
        fill.color = mix(cBody, cText2, 0.55f)
        val ww = w * 0.075f
        val wh = h * 0.15f
        for (y in floatArrayOf(0.13f, 0.72f)) {
            rect.set(left - ww * 0.4f, top + h * y, left + ww * 0.6f, top + h * y + wh)
            canvas.drawRoundRect(rect, ww / 2, ww / 2, fill)
            rect.set(left + w - ww * 0.6f, top + h * y, left + w + ww * 0.4f, top + h * y + wh)
            canvas.drawRoundRect(rect, ww / 2, ww / 2, fill)
        }

        // 사이드미러
        fill.color = mix(cBody, cText2, 0.35f)
        for (side in intArrayOf(-1, 1)) {
            val mx = if (side < 0) left - w * 0.05f else left + w + w * 0.05f
            rect.set(mx - w * 0.04f, top + h * 0.215f, mx + w * 0.04f, top + h * 0.255f)
            canvas.drawRoundRect(rect, 8f, 8f, fill)
        }

        // 차체
        rect.set(left, top, left + w, top + h)
        fill.color = cBody
        canvas.drawRoundRect(rect, w * 0.3f, w * 0.3f, fill)
        line.color = cLine
        canvas.drawRoundRect(rect, w * 0.3f, w * 0.3f, line)

        // 실내 바닥
        rect.set(left + w * 0.06f, top + h * 0.25f, left + w * 0.94f, top + h * 0.725f)
        fill.color = cCabin
        canvas.drawRoundRect(rect, w * 0.1f, w * 0.1f, fill)

        // 앞유리와 뒷유리
        fill.color = mix(cLine, cText2, 0.25f)
        path.reset()
        path.moveTo(left + w * 0.15f, top + h * 0.235f)
        path.lineTo(left + w * 0.85f, top + h * 0.235f)
        path.lineTo(left + w * 0.78f, top + h * 0.15f)
        path.lineTo(left + w * 0.22f, top + h * 0.15f)
        path.close()
        canvas.drawPath(path, fill)
        rect.set(left + w * 0.16f, top + h * 0.735f, left + w * 0.84f, top + h * 0.755f)
        canvas.drawRoundRect(rect, 8f, 8f, fill)

        // 프렁크(앞 보닛, DoorValue.HOOD). 트렁크처럼 문 기준으로 등록하고 좌석 점유 상태는 없다.
        val fw = w * 0.6f
        val fh = h * 0.085f
        rect.set(left + (w - fw) / 2f, top + h * 0.04f, left + (w + fw) / 2f, top + h * 0.04f + fh)
        hits["HOOD"] = RectF(rect)
        val frunkBag = bySeat["HOOD"]
        val frunkState = if (frunkBag != null) 2 else 0
        fill.color = base(frunkState)
        canvas.drawRoundRect(rect, w * 0.05f, w * 0.05f, fill)
        line.color = if (frunkState == 2) cBrand else cLine
        canvas.drawRoundRect(rect, w * 0.05f, w * 0.05f, line)
        caption(canvas, frunkBag?.label ?: "프렁크", rect.centerX(), rect.centerY(), fw * 0.9f, w * 0.085f, textColor(frunkState))

        // 좌석
        val sw = w * 0.25f
        val sh = h * 0.17f
        val xs = floatArrayOf(left + w * 0.1f, left + w * 0.375f, left + w * 0.65f)
        val row1 = top + h * 0.275f
        val row2 = top + h * 0.51f
        seat(canvas, "ROW_1_LEFT", xs[0], row1, sw, sh, "운전석", driver = true)
        seat(canvas, "ROW_1_RIGHT", xs[2], row1, sw, sh, "조수석")
        seat(canvas, "ROW_2_LEFT", xs[0], row2, sw, sh, "왼쪽")
        seat(canvas, "ROW_2_CENTER", xs[1], row2, sw, sh, "가운데")
        seat(canvas, "ROW_2_RIGHT", xs[2], row2, sw, sh, "오른쪽")

        // 트렁크
        val tl = left + w * 0.14f
        val tt = top + h * 0.785f
        val tw = w * 0.72f
        val th = h * 0.14f
        rect.set(tl, tt, tl + tw, tt + th)
        val bag = bySeat["REAR"]
        val state = if (bag != null) 2 else if (occupied["REAR"] == true) 1 else 0
        hits["REAR"] = RectF(rect)
        fill.color = base(state)
        canvas.drawRoundRect(rect, w * 0.07f, w * 0.07f, fill)
        line.color = if (state == 2) cBrand else cLine
        canvas.drawRoundRect(rect, w * 0.07f, w * 0.07f, line)
        caption(canvas, bag?.label ?: "트렁크", rect.centerX(), rect.centerY(), tw * 0.9f, w * 0.1f, textColor(state))
    }

    // 0 비어 있음, 1 앉았지만 등록 안 됨, 2 등록됨
    private fun base(state: Int): Int = when (state) {
        2 -> cBrand
        1 -> cSoft
        else -> cSurface
    }

    private fun textColor(state: Int): Int = when (state) {
        2 -> cOnBrand
        1 -> cBrand
        else -> cText2
    }

    private fun seat(c: Canvas, key: String, x: Float, y: Float, sw: Float, sh: Float, empty: String, driver: Boolean = false) {
        hits[key] = RectF(x, y, x + sw, y + sh)
        val person = bySeat[key]
        val seated = occupied[key] == true
        // 운전석은 앉았는지만 색으로 보여 주고, 나머지는 등록 여부까지 구분한다.
        val state = when {
            driver -> if (seated) 2 else 0
            person != null -> 2
            seated -> 1
            else -> 0
        }
        val color = base(state)
        val edge = if (state == 2) cBrand else cLine
        val r = sw * 0.22f

        // 등받이(차 뒤쪽)와 앉는 부분
        val back = RectF(x + sw * 0.07f, y + sh * 0.78f, x + sw * 0.93f, y + sh)
        val cushion = RectF(x, y, x + sw, y + sh * 0.72f)
        fill.color = mix(color, Color.BLACK, 0.14f)
        c.drawRoundRect(back, r * 0.6f, r * 0.6f, fill)
        fill.color = color
        c.drawRoundRect(cushion, r, r, fill)
        line.color = edge
        c.drawRoundRect(cushion, r, r, line)

        val label = person?.label ?: empty
        caption(c, label, x + sw / 2, y + sh * 0.36f, sw * 0.88f, sw * 0.3f, textColor(state))
    }

    private fun caption(c: Canvas, s: String, cx: Float, cy: Float, maxW: Float, size: Float, color: Int) {
        text.color = color
        text.textSize = size
        var shown = s
        while (text.measureText(shown) > maxW && text.textSize > size * 0.65f) text.textSize -= 1f
        while (text.measureText(shown) > maxW && shown.length > 1) shown = shown.dropLast(1)
        if (shown != s) shown = shown.dropLast(1) + "…"
        c.drawText(shown, cx, cy - (text.ascent() + text.descent()) / 2, text)
    }
}

/**
 * 하단에 뜨는 카드의 공통 틀. 알림 토스트와 음성 등록 카드가 같이 쓴다.
 * 폭은 사용할 수 있는 폭과 maxWidthPx 중 작은 값으로 고정해서 내용이 달라도 두 카드의 폭이 같고 잘리지 않는다.
 */
class ToastCard(context: Context, private val maxWidthPx: Int) : android.widget.LinearLayout(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val available = MeasureSpec.getSize(widthMeasureSpec)
        val target = if (available > 0) minOf(available, maxWidthPx) else maxWidthPx
        super.onMeasure(MeasureSpec.makeMeasureSpec(target, MeasureSpec.EXACTLY), heightMeasureSpec)
    }
}

object Glyph {
    const val BELL = 0
    const val BAG = 1
    const val CHECK = 2
    const val EXIT = 3
    const val INFO = 4
    const val WARN = 5
    const val MIC = 6
}

/** 알림 종, 물건 가방, 상태 아이콘. 드로어블 파일 없이 직접 그린다. */
class GlyphView(context: Context, private var glyph: Int, private var tint: Int) : View(context) {

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()
    private val rect = RectF()

    fun set(glyph: Int, tint: Int) {
        this.glyph = glyph
        this.tint = tint
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        fill.color = tint
        stroke.color = tint
        val cx = width / 2f
        val cy = height / 2f
        val u = minOf(width, height) * 0.36f
        when (glyph) {
            Glyph.BELL -> {
                val s = u * 1.1f
                val by = cy - s * 0.05f
                path.reset()
                path.moveTo(cx - s * 0.62f, by + s * 0.34f)
                path.cubicTo(cx - s * 0.5f, by + s * 0.24f, cx - s * 0.4f, by + s * 0.1f, cx - s * 0.4f, by - s * 0.2f)
                path.cubicTo(cx - s * 0.4f, by - s * 0.62f, cx + s * 0.4f, by - s * 0.62f, cx + s * 0.4f, by - s * 0.2f)
                path.cubicTo(cx + s * 0.4f, by + s * 0.1f, cx + s * 0.5f, by + s * 0.24f, cx + s * 0.62f, by + s * 0.34f)
                path.close()
                canvas.drawPath(path, fill)
                canvas.drawCircle(cx, by - s * 0.62f, s * 0.09f, fill)
                canvas.drawCircle(cx, by + s * 0.56f, s * 0.15f, fill)
            }
            Glyph.BAG -> {
                // 자물쇠처럼 보이지 않도록 둥근 고리 대신 납작한 손잡이를 단 가방 모양으로 그린다.
                rect.set(cx - u * 0.72f, cy - u * 0.3f, cx + u * 0.72f, cy + u * 0.68f)
                canvas.drawRoundRect(rect, u * 0.2f, u * 0.2f, fill)
                stroke.strokeWidth = u * 0.14f
                rect.set(cx - u * 0.32f, cy - u * 0.7f, cx + u * 0.32f, cy - u * 0.2f)
                canvas.drawRoundRect(rect, u * 0.12f, u * 0.12f, stroke)
            }
            Glyph.CHECK -> {
                stroke.strokeWidth = u * 0.24f
                path.reset()
                path.moveTo(cx - u * 0.55f, cy + u * 0.02f)
                path.lineTo(cx - u * 0.15f, cy + u * 0.42f)
                path.lineTo(cx + u * 0.58f, cy - u * 0.42f)
                canvas.drawPath(path, stroke)
            }
            Glyph.EXIT -> {
                stroke.strokeWidth = u * 0.17f
                path.reset()
                path.moveTo(cx - u * 0.05f, cy - u * 0.62f)
                path.lineTo(cx - u * 0.62f, cy - u * 0.62f)
                path.lineTo(cx - u * 0.62f, cy + u * 0.62f)
                path.lineTo(cx - u * 0.05f, cy + u * 0.62f)
                canvas.drawPath(path, stroke)
                path.reset()
                path.moveTo(cx - u * 0.2f, cy)
                path.lineTo(cx + u * 0.62f, cy)
                path.moveTo(cx + u * 0.26f, cy - u * 0.36f)
                path.lineTo(cx + u * 0.62f, cy)
                path.lineTo(cx + u * 0.26f, cy + u * 0.36f)
                canvas.drawPath(path, stroke)
            }
            Glyph.INFO -> {
                stroke.strokeWidth = u * 0.22f
                canvas.drawCircle(cx, cy - u * 0.5f, u * 0.13f, fill)
                canvas.drawLine(cx, cy - u * 0.12f, cx, cy + u * 0.58f, stroke)
            }
            Glyph.MIC -> {
                rect.set(cx - u * 0.26f, cy - u * 0.8f, cx + u * 0.26f, cy + u * 0.18f)
                canvas.drawRoundRect(rect, u * 0.26f, u * 0.26f, fill)
                stroke.strokeWidth = u * 0.13f
                rect.set(cx - u * 0.5f, cy - u * 0.38f, cx + u * 0.5f, cy + u * 0.46f)
                canvas.drawArc(rect, 0f, 180f, false, stroke)
                canvas.drawLine(cx, cy + u * 0.46f, cx, cy + u * 0.78f, stroke)
                canvas.drawLine(cx - u * 0.3f, cy + u * 0.78f, cx + u * 0.3f, cy + u * 0.78f, stroke)
            }
            else -> {
                stroke.strokeWidth = u * 0.22f
                canvas.drawLine(cx, cy - u * 0.62f, cx, cy + u * 0.14f, stroke)
                canvas.drawCircle(cx, cy + u * 0.56f, u * 0.14f, fill)
            }
        }
    }
}

/**
 * 마이크 입력 크기를 막대로 보여 준다. 막대는 제자리에서 오르내리고 옆으로 흐르지 않는다.
 * 말하는 소리가 들어올 때만 움직이고, 소리가 없으면 낮은 점 줄로 멈춘다.
 */
class WaveView(context: Context) : View(context) {

    private val count = 28
    private val heights = FloatArray(count)
    private val phase = FloatArray(count) { i -> (i * 1.7f) % 6.28f }
    private val speed = FloatArray(count) { i -> 7f + (i * 37 % 11) }

    // 가운데 막대가 가장 높고 가장자리로 갈수록 낮은 모양
    private val shape = FloatArray(count) { i ->
        val d = (i - (count - 1) / 2f) / (count / 2f)
        0.3f + 0.7f * (1f - d * d)
    }

    private var target = 0f
    private var envelope = 0f
    private var running = false
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.c_brand)
        strokeCap = Paint.Cap.ROUND
    }

    private val frame = object : Runnable {
        override fun run() {
            val t = SystemClock.uptimeMillis() / 1000f
            envelope += (target - envelope) * (if (target > envelope) 0.5f else 0.2f)
            // 새 입력이 끊기면 빠르게 가라앉는다.
            target *= 0.88f
            if (target < 0.02f) target = 0f
            if (envelope < 0.02f && target == 0f) {
                envelope = 0f
                heights.fill(0f)
                running = false
                invalidate()
                return
            }
            for (i in 0 until count) {
                heights[i] = envelope * shape[i] * (0.55f + 0.45f * sin(t * speed[i] + phase[i]))
            }
            invalidate()
            postDelayed(this, 33)
        }
    }

    fun push(level: Float) {
        if (level <= 0f) {
            target = 0f
            return
        }
        target = level
        if (!running) {
            running = true
            post(frame)
        }
    }

    fun clear() {
        target = 0f
        envelope = 0f
        heights.fill(0f)
        invalidate()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(frame)
        running = false
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val gap = width.toFloat() / count
        val barW = gap * 0.55f
        val mid = height / 2f
        paint.strokeWidth = barW
        for (i in 0 until count) {
            val h = barW + heights[i] * (height - barW)
            val x = gap * (i + 0.5f)
            paint.alpha = if (heights[i] > 0f) 255 else 90
            canvas.drawLine(x, mid - h / 2 + barW / 2, x, mid + h / 2 - barW / 2, paint)
        }
    }
}
