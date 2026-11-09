package dev.hjun.checkagain

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var events: EventStore
    private lateinit var store: PassengerStore

    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var llmDot: View
    private lateinit var llmText: TextView
    private lateinit var badge: TextView
    private lateinit var car: CarView
    private lateinit var peopleBox: LinearLayout
    private lateinit var countText: TextView
    private lateinit var clearButton: TextView
    private lateinit var voiceCard: LinearLayout
    private lateinit var voicePrompt: TextView
    private lateinit var voiceHint: TextView
    private lateinit var wave: WaveView
    private lateinit var voiceIcon: FrameLayout
    private lateinit var voiceGlyph: GlyphView
    private lateinit var confirmRow: LinearLayout
    private lateinit var yesButton: TextView
    private lateinit var retryButton: TextView
    private lateinit var cancelButton: TextView
    private lateinit var center: LinearLayout
    private lateinit var centerList: LinearLayout
    private lateinit var eventToast: LinearLayout
    private lateinit var toastHolder: FrameLayout
    private lateinit var toastGlyph: GlyphView
    private lateinit var toastTitle: TextView
    private lateinit var toastText: TextView

    private var peopleSignature: String? = null
    private var centerOpen = false
    private var highlighted = emptySet<Long>()
    private var clearArmed = false
    private var lastToastId = 0L
    private var toastSeat: String? = null
    private lateinit var toastActions: LinearLayout

    private val backCallback = OnBackInvokedCallback { hideCenter() }

    private val tick = object : Runnable {
        override fun run() {
            refresh()
            ui.postDelayed(this, 1000)
        }
    }

    private val hideToast = Runnable {
        eventToast.animate().alpha(0f).translationY(dp(30).toFloat()).setDuration(160)
            .withEndAction { if (eventToast.alpha <= 0.01f) eventToast.visibility = View.GONE }
            .start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        events = EventStore(this)
        store = PassengerStore(this)
        setContentView(build())
        ensurePermissions()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        lastToastId = events.all().firstOrNull()?.id ?: 0L
        EventStore.onChange = { onEventsChanged() }
        Live.onState = { refreshVoice() }
        Live.onLevel = { wave.push(it) }
        if (!AutoService.running && audioGranted() && AutoService.shouldRun(this)) start(false)
        refreshVoice()
        ui.post(tick)
    }

    override fun onStop() {
        ui.removeCallbacks(tick)
        ui.removeCallbacks(hideToast)
        // 화면이 가려진 동안 숨김 타이머가 지워져 토스트가 남아 있지 않도록 바로 닫는다.
        eventToast.animate().cancel()
        eventToast.visibility = View.GONE
        EventStore.onChange = null
        Live.onState = null
        Live.onLevel = null
        super.onStop()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (audioGranted() && !AutoService.running && AutoService.shouldRun(this)) start(false)
    }

    // ---------- 화면 구성 ----------

    private fun build(): View {
        val wide = resources.displayMetrics.widthPixels >= resources.displayMetrics.heightPixels

        statusDot = View(this)
        statusText = label("", 15f, R.color.c_text2, bold = true)
        val chip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = box(color(R.color.c_surface), dp(20), color(R.color.c_line))
            setPadding(dp(14), dp(8), dp(16), dp(8))
            addView(statusDot, LinearLayout.LayoutParams(dp(10), dp(10)).apply { rightMargin = dp(8) })
            addView(statusText)
            setOnClickListener { if (AutoService.running) stop() else start(false) }
        }

        // 오프라인 모드 버튼. 누를 때마다 LLM 사용을 끄고 켠다. 꺼 두면 기기 안의 규칙만 쓴다.
        llmDot = View(this)
        llmText = label("", 15f, R.color.c_text2, bold = true)
        val llmChip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = box(color(R.color.c_surface), dp(20), color(R.color.c_line))
            setPadding(dp(14), dp(8), dp(16), dp(8))
            addView(llmDot, LinearLayout.LayoutParams(dp(10), dp(10)).apply { rightMargin = dp(8) })
            addView(llmText)
            setOnClickListener { toggleLlm() }
        }
        refreshLlm()

        badge = TextView(this).apply {
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            includeFontPadding = false
            minWidth = dp(34)
            minHeight = dp(34)
            setPadding(dp(8), 0, dp(8), 0)
            background = box(color(R.color.c_badge), dp(17))
            visibility = View.GONE
        }
        val bell = FrameLayout(this).apply {
            contentDescription = "알림"
            isClickable = true
            setOnClickListener { showCenter() }
            addView(
                FrameLayout(context).apply {
                    background = box(color(R.color.c_brand), dp(31))
                    addView(GlyphView(context, Glyph.BELL, color(R.color.c_on_brand)), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                },
                FrameLayout.LayoutParams(dp(62), dp(62), Gravity.BOTTOM or Gravity.START)
            )
            addView(badge, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END))
        }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(label("챙겼니", 40f, R.color.c_text, bold = true))
            addView(chip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(16) })
            addView(llmChip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(10) })
            addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
            addView(bell, LinearLayout.LayoutParams(dp(76), dp(76)))
        }

        car = CarView(this)
        car.onSeatTap = { seat -> onSeatTapped(seat) }
        val carCard = FrameLayout(this).apply {
            background = box(color(R.color.c_surface), dp(24), color(R.color.c_line))
            addView(car, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }

        countText = label("", 22f, R.color.c_brand, bold = true).apply {
            background = box(color(R.color.c_brand_soft), dp(22))
            setPadding(dp(18), dp(7), dp(18), dp(7))
            visibility = View.GONE
        }
        peopleBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        clearButton = pillButton("목록 비우기", filled = false) { onClear() }.apply {
            textSize = 24f
            setPadding(dp(28), dp(16), dp(28), dp(16))
        }
        val listHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(circleIcon(color(R.color.c_brand_soft), Glyph.BAG, color(R.color.c_brand), 72), LinearLayout.LayoutParams(dp(72), dp(72)).apply { rightMargin = dp(16) })
            addView(label("등록된 물건", 36f, R.color.c_text, bold = true))
            addView(countText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(12) })
            addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
            addView(clearButton)
        }
        val side = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(listHeader)
            addView(
                ScrollView(context).apply { isVerticalScrollBarEnabled = false; isFillViewport = true; addView(peopleBox) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(14) }
            )
        }

        val content = LinearLayout(this).apply {
            orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            if (wide) {
                addView(carCard, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.85f))
                addView(side, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.15f).apply { leftMargin = dp(20) })
            } else {
                addView(carCard, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
                addView(side, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(16) })
            }
        }

        voicePrompt = label("", 27f, R.color.c_text, bold = true).apply {
            maxWidth = dp(900)
            maxLines = 4
            setLineSpacing(0f, 1.15f)
        }
        voiceHint = label("", 19f, R.color.c_text2)
        wave = WaveView(this)
        yesButton = pillButton("맞아요", filled = true) { answer("맞아요") }.apply { bigButton() }
        retryButton = pillButton("다시 말할게요", filled = false) { answer("다시") }.apply { bigButton() }
        // 등록 취소는 이름, 소지품, 확인 어느 질문에서든 눌러도 된다. 눈에 덜 띄게 연한 테두리로 둔다.
        cancelButton = pillButton("등록 취소", filled = false) { AutoService.current?.cancelByTouch() }.apply {
            bigButton()
            setTextColor(color(R.color.c_text))
            background = box(color(R.color.c_surface), dp(26), color(R.color.c_line), 2)
        }
        confirmRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START
            // 버튼은 줄 폭을 같은 비율로 나눠 써서 타이틀과 글자가 다르게 보여도 잘리지 않는다.
            addView(yesButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(retryButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.3f).apply { leftMargin = dp(10) })
            addView(cancelButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(10) })
            visibility = View.GONE
        }
        // 이벤트 토스트와 같은 틀: 왼쪽 둥근 아이콘, 오른쪽에 글자와 파형과 버튼을 세로로 쌓는다.
        voiceCard = ToastCard(this, dp(760)).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = box(color(R.color.c_surface), dp(30), color(R.color.c_line), 2)
            setPadding(dp(28), dp(22), dp(28), dp(22))
            elevation = dp(10).toFloat()
            voiceIcon = circleIcon(color(R.color.c_brand), Glyph.MIC, color(R.color.c_on_brand), 80)
            voiceGlyph = voiceIcon.getChildAt(0) as GlyphView
            addView(voiceIcon, LinearLayout.LayoutParams(dp(80), dp(80)).apply { rightMargin = dp(24) })
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(voiceHint)
                    addView(voicePrompt, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
                    addView(wave, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(12) })
                    addView(confirmRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) })
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
            visibility = View.GONE
        }

        val main = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(16), dp(28), dp(24))
            addView(topBar)
            addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(14) })
        }

        centerList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        center = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(color(R.color.c_bg))
            setPadding(dp(28), dp(20), dp(28), dp(24))
            visibility = View.GONE
            isClickable = true
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(label("알림", 34f, R.color.c_text, bold = true))
                    addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
                    addView(pillButton("모두 읽음", filled = false) { events.markAllRead() })
                    addView(pillButton("비우기", filled = false) { highlighted = emptySet(); events.clear() }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(10) })
                    addView(pillButton("닫기", filled = true) { hideCenter() }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(10) })
                }
            )
            addView(
                ScrollView(context).apply { isVerticalScrollBarEnabled = false; isFillViewport = true; addView(centerList) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(16) }
            )
        }

        toastGlyph = GlyphView(this, Glyph.CHECK, color(R.color.c_on_brand))
        toastHolder = FrameLayout(this).apply {
            addView(toastGlyph, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        toastTitle = label("", 27f, R.color.c_text, bold = true).apply {
            maxWidth = dp(900)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        toastText = label("", 21f, R.color.c_text2).apply {
            setPadding(0, dp(6), 0, 0)
            maxWidth = dp(900)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        // 등록하지 않은 좌석을 눌렀을 때만 보이는 선택지
        val toastYes = pillButton("등록하기", filled = true) {
            val seat = toastSeat
            val flow = AutoService.current
            val reason = when {
                seat == null -> null
                flow == null -> "자동 모드가 꺼져 있어요"
                else -> flow.registerSeat(seat)
            }
            dismissToast()
            if (reason != null) {
                showEventToast(Event(0L, Kind.WARN, "등록할 수 없어요", reason, System.currentTimeMillis(), true))
            }
        }.apply { bigButton() }
        val toastNo = pillButton("등록 취소", filled = false) { dismissToast() }.apply {
            bigButton()
            setTextColor(color(R.color.c_text))
            background = box(color(R.color.c_surface), dp(26), color(R.color.c_line), 2)
        }
        toastActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
            addView(toastYes, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(toastNo, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(10) })
        }
        eventToast = ToastCard(this, dp(760)).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = box(color(R.color.c_surface), dp(30), color(R.color.c_line), 2)
            setPadding(dp(28), dp(22), dp(28), dp(22))
            elevation = dp(10).toFloat()
            visibility = View.GONE
            isClickable = true
            // 토스트는 눌러서 지운다. 알림 센터는 상단 종 아이콘으로 열린다.
            setOnClickListener {
                ui.removeCallbacks(hideToast)
                animate().cancel()
                visibility = View.GONE
            }
            addView(toastHolder, LinearLayout.LayoutParams(dp(80), dp(80)).apply { rightMargin = dp(24) })
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(toastTitle)
                    addView(toastText)
                    addView(toastActions, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) })
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
        }

        return FrameLayout(this).apply {
            setBackgroundColor(color(R.color.c_bg))
            fitsSystemWindows = true
            addView(main, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(center, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            // 이벤트 토스트와 음성 대화 카드는 본 화면 위 하단에 뜨는 하단 독에 쌓인다. 알림 센터 위에도 보인다.
            val dock = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(
                    eventToast,
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                        .apply {
                            gravity = Gravity.CENTER_HORIZONTAL
                            setMargins(dp(28), dp(12), dp(28), dp(24))
                        }
                )
                addView(
                    voiceCard,
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                        .apply {
                            gravity = Gravity.CENTER_HORIZONTAL
                            setMargins(dp(28), dp(12), dp(28), dp(24))
                        }
                )
            }
            addView(dock, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        }
    }

    // ---------- 갱신 ----------

    private fun refresh() {
        val running = AutoService.running
        statusText.text = if (running) "켜짐" else "꺼짐"
        statusDot.background = box(color(if (running) R.color.c_ok else R.color.c_text2), dp(5))

        val people = store.people()
        val signature = people.joinToString("|") { "${it.id}:${it.name}:${it.items}:${it.seat}" }
        car.setData(people, AutoService.current?.seatStates().orEmpty())
        if (signature != peopleSignature) {
            peopleSignature = signature
            renderPeople(people)
        }
        updateBadge()
    }

    private fun renderPeople(people: List<Person>) {
        countText.text = "${people.size}건"
        countText.visibility = if (people.isEmpty()) View.GONE else View.VISIBLE
        peopleBox.removeAllViews()
        peopleBox.gravity = if (people.isEmpty()) Gravity.CENTER else Gravity.TOP
        if (people.isEmpty()) {
            peopleBox.addView(
                label("아직 등록된 물건이 없어요.\n차에 타시면 음성으로 여쭤볼게요.", 28f, R.color.c_text, bold = true).apply {
                    gravity = Gravity.CENTER
                    setLineSpacing(0f, 1.25f)
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            )
            return
        }
        people.forEach { p ->
            val title = if (p.isBag) "${SeatNames.label(p.seat)} 짐" else p.name
            val avatarText = if (p.isBag) "짐" else p.name.take(1)

            val avatar = TextView(this).apply {
                text = avatarText
                textSize = 20f
                gravity = Gravity.CENTER
                includeFontPadding = false
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(color(R.color.c_brand))
                background = box(color(R.color.c_brand_soft), dp(24))
            }

            val chips = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                if (p.isBag) {
                    addView(chip("주인을 정하지 않았어요", neutral = true))
                } else {
                    p.items.take(3).forEach { addView(chip(it)) }
                    if (p.items.size > 3) addView(chip("+${p.items.size - 3}", neutral = true))
                }
            }

            val info = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(label(title, 20f, R.color.c_text, bold = true))
                addView(chips, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
            }

            peopleBox.addView(
                LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = box(color(R.color.c_surface), dp(20), color(R.color.c_line))
                    setPadding(dp(16), dp(16), dp(18), dp(16))
                    elevation = dp(2).toFloat()
                    // 누르면 이 등록의 하차 안내 화면을 다시 띄우고 음성으로 읽어 준다.
                    isClickable = true
                    setOnClickListener { AutoService.current?.announce(p.id) }
                    addView(avatar, LinearLayout.LayoutParams(dp(48), dp(48)).apply { rightMargin = dp(14) })
                    addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    if (!p.isBag && p.seat != null) {
                        addView(
                            label(SeatNames.label(p.seat), 13f, R.color.c_text2, bold = true).apply {
                                background = box(color(R.color.c_surface2), dp(12))
                                setPadding(dp(10), dp(4), dp(10), dp(4))
                            }
                        )
                    }
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) }
            )
        }
    }

    // 차 그림의 좌석을 눌렀을 때. 운전석은 지원하지 않는다는 안내만 띄운다. 등록이 있으면 등록된 물건 카드를 눌렀을 때와 같이 안내 카드와 음성을 띄우고,
    // 없으면 그 좌석의 상태를 토스트로 알려 준다.
    private fun onSeatTapped(seat: String) {
        Log.d("CheckAgain", "좌석 누름: $seat")
        if (seat == "ROW_1_LEFT") {
            showEventToast(Event(0L, Kind.INFO, "운전석", "운전석은 지원되지 않아요", System.currentTimeMillis(), true))
            return
        }
        val person = store.people().firstOrNull { it.seat == seat }
        val flow = AutoService.current
        if (person != null && flow != null && flow.announce(person.id)) return
        val place = SeatNames.label(seat)
        val seated = flow?.seatStates()?.get(seat) == true
        val text = when {
            person != null -> if (person.isBag) "짐이 등록되어 있어요" else "${person.name}님 · ${person.items.joinToString(", ")}"
            seat == "REAR" || seat == "HOOD" -> "등록된 물건이 없어요"
            seated -> "앉아 있어요. 등록할까요?"
            else -> "비어 있어요"
        }
        // 앉아 있는데 등록이 없는 좌석만 선택지를 준다. 트렁크와 프렁크는 좌석 센서가 없다.
        val askRegister = person == null && seated && seat != "REAR" && seat != "HOOD"
        showEventToast(Event(0L, Kind.INFO, place, text, System.currentTimeMillis(), true), if (askRegister) seat else null)
    }

    private fun chip(value: String, neutral: Boolean = false): TextView =
        label(value, 15f, if (neutral) R.color.c_text2 else R.color.c_brand, bold = true).apply {
            background = box(color(if (neutral) R.color.c_surface2 else R.color.c_brand_soft), dp(15))
            setPadding(dp(13), dp(6), dp(13), dp(6))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(8) }
        }

    private fun refreshVoice() {
        val phase = Live.phase
        if (phase != Phase.IDLE) {
            if (voiceCard.visibility != View.VISIBLE) {
                voiceCard.visibility = View.VISIBLE
                voiceCard.alpha = 0f
                voiceCard.translationY = dp(40).toFloat()
            }
            voiceCard.animate().alpha(1f).translationY(0f).setDuration(180).start()
        } else if (voiceCard.visibility == View.VISIBLE) {
            voiceCard.animate().alpha(0f).translationY(dp(40).toFloat()).setDuration(150)
                .withEndAction { if (Live.phase == Phase.IDLE) voiceCard.visibility = View.GONE }
                .start()
        }
        voicePrompt.text = Live.text
        // 하차 안내는 주황색 테두리와 출구 아이콘으로 등록 대화와 다르게 보여 준다. 파형은 쓰지 않는다.
        val alight = Live.alight
        voiceIcon.background = box(color(if (alight) R.color.c_warn else R.color.c_brand), dp(40))
        voiceGlyph.set(if (alight) Glyph.EXIT else Glyph.MIC, color(R.color.c_on_brand))
        voiceCard.background = if (alight) box(color(R.color.c_surface), dp(30), color(R.color.c_warn), 3)
        else box(color(R.color.c_surface), dp(30), color(R.color.c_line), 2)
        wave.visibility = if (alight) View.GONE else View.VISIBLE
        voiceHint.setTextColor(color(if (alight) R.color.c_warn else R.color.c_text2))
        voiceHint.text = when {
            alight -> "하차 확인"
            phase == Phase.LISTENING -> "듣고 있어요. 말씀해 주세요"
            else -> "챙겼니가 말하는 중"
        }
        // 마이크가 켜진 동안은 모든 버튼, 질문을 읽거나 LLM 응답을 기다리는 동안은 취소 버튼만 보여 준다. 하차 안내에는 취소할 것이 없다.
        val listening = phase == Phase.LISTENING
        confirmRow.visibility = if (listening || (phase == Phase.SPEAKING && !alight)) View.VISIBLE else View.GONE
        // 시작 확인 질문은 "등록할게요 / 건너뛰기" 두 버튼만 보여 준다.
        val gate = Live.gate
        yesButton.text = if (gate) "등록할게요" else "맞아요"
        cancelButton.text = if (gate) "건너뛰기" else "등록 취소"
        yesButton.visibility = if (listening && (Live.confirm || gate)) View.VISIBLE else View.GONE
        retryButton.visibility = if (listening && Live.confirm && !gate) View.VISIBLE else View.GONE
        if (phase != Phase.LISTENING) wave.clear()
    }

    private fun answer(text: String) {
        AutoService.current?.forceAnswer(text)
    }

    private fun toggleLlm() {
        LlmSettings.setEnabled(this, !LlmSettings.enabled(this))
        refreshLlm()
    }

    private fun refreshLlm() {
        val on = LlmSettings.enabled(this)
        val usable = on && LlmSettings.available
        llmText.text = when {
            !on -> "오프라인 모드"
            !LlmSettings.available -> "온라인 키 없음"
            else -> "온라인 모드 켜짐"
        }
        llmDot.background = box(color(if (usable) R.color.c_ok else R.color.c_text2), dp(5))
    }

    private fun updateBadge() {
        val unread = events.unreadCount()
        badge.visibility = if (unread > 0 && !centerOpen) View.VISIBLE else View.GONE
        badge.text = if (unread > 99) "99+" else unread.toString()
    }

    private fun onClear() {
        if (!clearArmed) {
            clearArmed = true
            clearButton.text = "한 번 더 누르기"
            ui.postDelayed({
                clearArmed = false
                clearButton.text = "목록 비우기"
            }, 3000)
            return
        }
        clearArmed = false
        clearButton.text = "목록 비우기"
        val flow = AutoService.current
        if (flow != null) {
            flow.clearRegistrations()
        } else {
            store.clear()
            events.add(Kind.INFO, "목록 비움", "등록된 물건을 모두 지웠어요.")
        }
        ui.postDelayed({ refresh() }, 200)
    }

    // ---------- 이벤트 토스트 ----------

    private fun accent(kind: String): Int = when (kind) {
        Kind.REGISTER -> R.color.c_brand
        Kind.ALIGHT -> R.color.c_warn
        Kind.WARN -> R.color.c_badge
        else -> R.color.c_text2
    }

    private fun dismissToast() {
        ui.removeCallbacks(hideToast)
        eventToast.animate().cancel()
        eventToast.visibility = View.GONE
    }

    // seat가 있으면 "등록하기 / 등록 취소" 선택지를 함께 보여 준다.
    private fun showEventToast(e: Event, seat: String? = null) {
        toastSeat = seat
        toastActions.visibility = if (seat != null) View.VISIBLE else View.GONE
        toastHolder.background = box(color(accent(e.kind)), dp(40))
        toastGlyph.set(glyphFor(e.kind), color(R.color.c_on_brand))
        toastTitle.text = e.title
        toastText.text = e.text
        if (eventToast.visibility != View.VISIBLE) {
            eventToast.visibility = View.VISIBLE
            eventToast.alpha = 0f
            eventToast.translationY = dp(30).toFloat()
        }
        eventToast.animate().alpha(1f).translationY(0f).setDuration(180).start()
        ui.removeCallbacks(hideToast)
        ui.postDelayed(hideToast, if (seat != null) 9000L else 4500L)
    }

    // ---------- 알림 센터 ----------

    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_CENTER, false) == true) showCenter()
    }

    private fun showCenter() {
        // 알림 센터가 열리면 떠 있던 토스트는 닫는다. 같은 내용이 목록에 있다.
        ui.removeCallbacks(hideToast)
        eventToast.animate().cancel()
        eventToast.visibility = View.GONE
        if (!centerOpen) {
            centerOpen = true
            highlighted = events.all().filter { !it.read }.map { it.id }.toSet()
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback)
        }
        center.visibility = View.VISIBLE
        renderCenter()
        events.markAllRead()
        updateBadge()
    }

    private fun hideCenter() {
        if (!centerOpen) return
        centerOpen = false
        highlighted = emptySet()
        center.visibility = View.GONE
        onBackInvokedDispatcher.unregisterOnBackInvokedCallback(backCallback)
        updateBadge()
    }

    private fun onEventsChanged() {
        val all = events.all()
        val fresh = all.filter { it.id > lastToastId }
        if (fresh.isNotEmpty()) {
            lastToastId = fresh.maxOf { it.id }
            if (!centerOpen) showEventToast(fresh.first())
        }
        if (centerOpen) {
            highlighted = highlighted + all.filter { !it.read }.map { it.id }
            renderCenter()
            events.markAllRead()
        }
        updateBadge()
    }

    private fun renderCenter() {
        centerList.removeAllViews()
        val list = events.all()
        centerList.gravity = if (list.isEmpty()) Gravity.CENTER else Gravity.TOP
        if (list.isEmpty()) {
            centerList.addView(
                label("아직 알림이 없어요.", 56f, R.color.c_text, bold = true).apply { gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            )
            return
        }
        list.forEach { e ->
            val fresh = e.id in highlighted
            val dot = circleIcon(color(accent(e.kind)), glyphFor(e.kind), color(R.color.c_on_brand), 44)
            centerList.addView(
                LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    // 새 알림은 파랑을 아주 엷게 섞은 배경과 테두리로만 구분한다.
                    background = if (fresh) box(mix(color(R.color.c_surface), color(R.color.c_brand), 0.1f), dp(18), mix(color(R.color.c_line), color(R.color.c_brand), 0.45f))
                    else box(color(R.color.c_surface), dp(18), color(R.color.c_line))
                    setPadding(dp(18), dp(16), dp(18), dp(16))
                    gravity = Gravity.CENTER_VERTICAL
                    addView(dot, LinearLayout.LayoutParams(dp(44), dp(44)).apply { rightMargin = dp(14) })
                    addView(
                        LinearLayout(context).apply {
                            orientation = LinearLayout.VERTICAL
                            addView(
                                LinearLayout(context).apply {
                                    orientation = LinearLayout.HORIZONTAL
                                    addView(label(e.title, 22f, R.color.c_text, bold = true))
                                    addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
                                    addView(label(timeText(e.at), 16f, R.color.c_text2))
                                }
                            )
                            addView(label(e.text, 20f, R.color.c_text2).apply { setPadding(0, dp(6), 0, 0) })
                        },
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    )
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) }
            )
        }
    }

    private fun timeText(at: Long): String {
        val gap = System.currentTimeMillis() - at
        return when {
            gap < 60_000L -> "방금"
            gap < 3_600_000L -> "${gap / 60_000L}분 전"
            gap < 86_400_000L -> SimpleDateFormat("HH:mm", Locale.KOREA).format(Date(at))
            else -> SimpleDateFormat("M월 d일 HH:mm", Locale.KOREA).format(Date(at))
        }
    }

    // ---------- 시작과 권한 ----------

    private fun audioGranted() =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    // 마이크와 알림 권한을 함께 요청한다. 둘 중 하나만 없어도 요청한다.
    private fun ensurePermissions() {
        val missing = mutableListOf<String>()
        if (!audioGranted()) missing.add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            missing.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1)
    }

    private fun start(reset: Boolean) {
        if (!audioGranted()) {
            ensurePermissions()
            return
        }
        AutoService.start(applicationContext, reset)
        ui.postDelayed({ refresh() }, 500)
    }

    private fun stop() {
        AutoService.stop(applicationContext)
        ui.postDelayed({ refresh() }, 500)
    }

    // ---------- 뷰 도우미 ----------

    private fun glyphFor(kind: String): Int = when (kind) {
        Kind.REGISTER -> Glyph.CHECK
        Kind.ALIGHT -> Glyph.EXIT
        Kind.WARN -> Glyph.WARN
        else -> Glyph.INFO
    }

    private fun circleIcon(bg: Int, glyph: Int, tint: Int, size: Int): FrameLayout = FrameLayout(this).apply {
        background = box(bg, dp(size) / 2)
        addView(GlyphView(context, glyph, tint), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun color(id: Int) = getColor(id)

    private fun box(fill: Int, radius: Int, stroke: Int = 0, strokeWidth: Int = 1): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = radius.toFloat()
            if (stroke != 0) setStroke(maxOf(1, dp(strokeWidth)), stroke)
        }

    private fun label(value: String, size: Float, colorId: Int, bold: Boolean = false) = TextView(this).apply {
        text = value
        textSize = size
        includeFontPadding = false
        setTextColor(color(colorId))
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    // 음성 카드의 버튼은 차량 화면에서 멀리서도 눌러야 해서 크게 키운다.
    private fun TextView.bigButton() {
        textSize = 22f
        maxLines = 1
        setPadding(dp(10), dp(18), dp(10), dp(18))
    }

    private fun pillButton(value: String, filled: Boolean, action: () -> Unit) = TextView(this).apply {
        text = value
        textSize = 18f
        gravity = Gravity.CENTER
        setTypeface(typeface, Typeface.BOLD)
        setPadding(dp(22), dp(14), dp(22), dp(14))
        if (filled) {
            setTextColor(color(R.color.c_on_brand))
            background = box(color(R.color.c_brand), dp(26))
        } else {
            setTextColor(color(R.color.c_brand))
            background = box(color(R.color.c_surface), dp(26), color(R.color.c_brand), 2)
        }
        isClickable = true
        setOnClickListener { action() }
    }

    companion object {
        const val EXTRA_CENTER = "center"
    }
}
