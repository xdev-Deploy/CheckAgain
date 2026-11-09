package dev.hjun.checkagain

import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.constant.Mode as SttMode
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.Mode as TtsMode
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.Door
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.listener.DoorPositionListener
import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.widget.Button
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.lang.reflect.Proxy
import java.util.Locale

class ProbeActivity : Activity() {

    private val tag = "PleosProbe"
    private val clientId = "PwLKwY2kSaWdjBNfO0j9CQ"
    private val clientSecret = "be441b6b-7157-4ac1-b50f-da760cd20fb2"
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var logView: TextView
    private lateinit var scroll: ScrollView

    private var vehicle: Vehicle? = null
    private var tts: TextToSpeech? = null
    private var stt: SpeechToText? = null
    private val doorListeners = mutableMapOf<DoorValue, DoorPositionListener>()
    private var lastRmsLog = 0L
    private val logSink: (String) -> Unit = { appendUi(it) }

    private val actions: List<Pair<String, () -> Unit>> = listOf(
        "0 목록 초기화" to ::clearList,
        "SDK 구조 덤프" to ::dumpApi,
        "1 Vehicle 초기화" to ::vehicleInit,
        "2 Door 능력 확인" to ::doorCapabilities,
        "3 Door 전체 읽기" to ::doorReadAll,
        "4 Door 구독 시작" to ::doorSubscribe,
        "5 Door 구독 해제" to ::doorUnsubscribe,
        "6 TTS 초기화" to ::ttsInit,
        "7 TTS 말하기" to ::ttsSpeak,
        "8 STT 초기화" to ::sttInit,
        "9 STT 시작" to ::sttStart,
        "10 STT 중지" to ::sttStop,
        "11 STT 한국어" to { sttLocale(Locale.KOREA) },
        "12 STT 영어" to { sttLocale(Locale.US) },
        "13 TTS 한국어" to ::ttsKorean,
        "14 STT 서버모드" to ::sttServerInit,
        "15 신호 탐색" to ::dumpSignals,
        "16 좌석 탐색" to ::seatProbe,
        "17 좌석 해제" to ::seatUnsubscribe,
        "18 자동 모드 시작" to ::autoStart,
        "19 자동 모드 종료" to ::autoStop,
        "20 2열우 비움" to { injectSeat("ROW_2_RIGHT", false) },
        "21 2열우 점유" to { injectSeat("ROW_2_RIGHT", true) },
        "22 2열좌 비움" to { injectSeat("ROW_2_LEFT", false) },
        "23 2열좌 점유" to { injectSeat("ROW_2_LEFT", true) },
        "24 2열중 비움" to { injectSeat("ROW_2_CENTER", false) },
        "25 2열중 점유" to { injectSeat("ROW_2_CENTER", true) },
        "26 조수석 비움" to { injectSeat("ROW_1_RIGHT", false) },
        "27 조수석 점유" to { injectSeat("ROW_1_RIGHT", true) },
        "28 주입 해제" to ::clearInjected,
        "29 주행 모사 켬" to { injectRolling(true) },
        "30 주행 모사 끔" to { injectRolling(false) },
        "31 2열우 문 열림" to { injectDoor("ROW_2_RIGHT", true) },
        "32 2열우 문 닫힘" to { injectDoor("ROW_2_RIGHT", false) },
        "33 2열좌 문 열림" to { injectDoor("ROW_2_LEFT", true) },
        "34 2열좌 문 닫힘" to { injectDoor("ROW_2_LEFT", false) },
        "35 트렁크 열림" to { injectDoor("REAR", true) },
        "36 트렁크 닫힘" to { injectDoor("REAR", false) },
        "로그 지우기" to { logView.text = "" }
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)


        val grid = GridLayout(this).apply { columnCount = 4 }
        actions.forEach { (label, action) ->
            grid.addView(Button(this).apply {
                text = label
                isAllCaps = false
                setOnClickListener { action() }
            })
        }

        logView = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 13f
            setTextIsSelectable(true)
        }
        scroll = ScrollView(this).apply { addView(logView) }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            addView(
                grid,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            )
            addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        })
    }


    override fun onStart() {
        super.onStart()
        if (logView.text.isEmpty()) AutoService.replay().takeLast(80).forEach { appendUi(it) }
        AutoService.sink = logSink
    }

    override fun onStop() {
        // 화면 전환 중에는 새 화면의 onStart로 등록한 뒤 이전 화면의 onStop이 오므로, 내가 등록한 것일 때만 해제한다.
        if (AutoService.sink === logSink) AutoService.sink = null
        super.onStop()
    }

    override fun onDestroy() {
        safe("좌석 구독 해제") { seatUnsubscribe() }
        safe("Door 구독 해제") { doorUnsubscribe() }
        safe("STT release") { stt?.release() }
        safe("TTS release") { tts?.release() }
        safe("Vehicle release") { vehicle?.release() }
        super.onDestroy()
    }

    private fun log(message: String) {
        Log.d(tag, message)
        appendUi(message)
    }

    private fun appendUi(message: String) {
        runOnUiThread {
            logView.append(message + "\n")
            scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    private fun safe(name: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            log("$name 예외: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun dumpApi() = safe("SDK 덤프") {
        log("DoorValue: ${DoorValue.entries.joinToString { it.name }}")
        listOf(Door::class.java, TextToSpeech::class.java, SpeechToText::class.java).forEach { type ->
            log("[${type.name}]")
            type.methods
                .filter { it.declaringClass == type }
                .sortedBy { it.name }
                .forEach { log("  ${it.toGenericString()}") }
        }
    }

    private fun vehicleInit() = safe("Vehicle 초기화") {
        val v = Vehicle(applicationContext)
        v.initialize()
        vehicle = v
        log("Vehicle initialize 호출 완료")
    }

    private fun door(): Door =
        requireNotNull(vehicle) { "1번 Vehicle 초기화를 먼저 실행" }.getDoor()

    private fun doorCapabilities() = safe("Door 능력 확인") {
        door().checkDoorPositionCapabilities(
            onComplete = { config, areaBounds ->
                log("Door capability access=${config.accessType}")
                DoorValue.entries.forEach { value ->
                    val bounds = areaBounds[DoorArea(value)]
                    log("  ${value.name}: ${bounds?.minValue}..${bounds?.maxValue}")
                }
            },
            onFailed = { log("Door capability 실패: ${it.javaClass.simpleName} ${it.message}") }
        )
    }

    private fun doorReadAll() = safe("Door 읽기") {
        val door = door()
        DoorValue.entries.forEach { value ->
            safe("read ${value.name}") {
                door.getCurrentDoorPosition(
                    area = DoorArea(value),
                    onComplete = { position -> log("Door read ${value.name} = $position") },
                    onFailed = { log("Door read ${value.name} 실패: ${it.javaClass.simpleName} ${it.message}") }
                )
            }
        }
    }

    private fun doorSubscribe() = safe("Door 구독") {
        val door = door()
        DoorValue.entries.forEach { value ->
            if (doorListeners.containsKey(value)) return@forEach
            val listener = object : DoorPositionListener {
                override fun onPositionUpdated(pos: Int?) {
                    log("Door 변경 ${value.name} -> $pos")
                }

                override fun onFailed(e: Exception) {
                    log("Door 구독 오류 ${value.name}: ${e.javaClass.simpleName} ${e.message}")
                }
            }
            safe("register ${value.name}") {
                door.registerDoorPosition(DoorArea(value), listener)
                doorListeners[value] = listener
                log("Door 구독 등록 ${value.name}")
            }
        }
    }

    private fun doorUnsubscribe() = safe("Door 구독 해제") {
        val door = vehicle?.getDoor() ?: return@safe
        doorListeners.toMap().forEach { (value, listener) ->
            safe("unregister ${value.name}") {
                door.unregisterDoorPosition(DoorArea(value), listener)
                doorListeners.remove(value)
                log("Door 구독 해제 ${value.name}")
            }
        }
    }

    private fun ttsInit() = safe("TTS 초기화") {
        val t = TextToSpeech(applicationContext, TtsMode.ON_DEVICE)
        t.initialize()
        tts = t
        log("TTS initialize 호출, 1.5초 뒤 이벤트 리스너 등록")
        ui.postDelayed({ safe("TTS 리스너") { attachProxy(t, "addEventListener", "TTS") } }, 1500)
    }

    private fun ttsSpeak() = safe("TTS 말하기") {
        requireNotNull(tts) { "6번 TTS 초기화를 먼저 실행" }.speak("예림이 핸드폰, 가방 확인하세요")
        log("TTS speak 호출")
    }

    private fun sttInit() = safe("STT 초기화") {
        val s = SpeechToText(applicationContext, SttMode.ON_DEVICE)
        s.initialize()
        stt = s
        log("STT initialize 호출, 1.5초 뒤 결과 리스너 등록")
        ui.postDelayed({ safe("STT 리스너") { attachProxy(s, "addListener", "STT") } }, 1500)
    }

    private fun sttLocale(locale: Locale) = safe("STT 로케일") {
        requireNotNull(stt) { "8번 STT 초기화를 먼저 실행" }.setLocale(locale)
        log("STT setLocale 호출: $locale")
    }

    private fun ttsKorean() = safe("TTS 로케일") {
        requireNotNull(tts) { "6번 TTS 초기화를 먼저 실행" }.setLocale(Locale.KOREA)
        log("TTS setLocale 호출: ${Locale.KOREA}")
    }

    private fun sttServerInit() = safe("STT 서버모드") {
        require(clientId.isNotBlank() && clientSecret.isNotBlank()) {
            "파일 상단 clientId, clientSecret을 채우세요"
        }
        safe("이전 STT release") { stt?.release() }
        val s = SpeechToText(applicationContext, SttMode.HYBRID)
        s.initialize()
        stt = s
        log("STT HYBRID initialize 호출, 1.5초 뒤 리스너와 앱 등록")
        ui.postDelayed({
            safe("STT 앱 등록") {
                attachProxy(s, "addListener", "STT")
                val method = s.javaClass.methods.first {
                    it.name == "registerApp" &&
                            it.parameterTypes.size == 3 &&
                            it.parameterTypes[0] == String::class.java &&
                            it.parameterTypes[1] == String::class.java
                }
                method.invoke(s, clientId, clientSecret, newProxy(method.parameterTypes[2], "STT.register"))
                log("STT registerApp 호출, onConnected 로그가 뜨면 9번 실행")
            }
        }, 1500)
    }

    private fun sttStart() = safe("STT 시작") {
        requireNotNull(stt) { "8번 STT 초기화를 먼저 실행" }.request()
        log("STT request 호출, 마이크에 말하세요")
    }

    private fun sttStop() = safe("STT 중지") {
        requireNotNull(stt) { "8번 STT 초기화를 먼저 실행" }.stop()
        log("STT stop 호출")
    }

    private fun dumpSignals() = safe("신호 탐색") {
        val v = requireNotNull(vehicle) { "1번 Vehicle 초기화를 먼저 실행" }
        v.javaClass.methods
            .filter {
                it.parameterTypes.isEmpty() &&
                        it.name.startsWith("get") &&
                        it.returnType.name.startsWith("ai.pleos.playground.vehicle.api.")
            }
            .sortedBy { it.name }
            .forEach { getter ->
                val registers = getter.returnType.methods
                    .filter { it.name.startsWith("register") }
                    .map { it.name.removePrefix("register") }
                    .distinct()
                    .sorted()
                log("${getter.returnType.simpleName}: ${registers.joinToString()}")
            }
        val seat = v.javaClass.methods
            .firstOrNull { it.name == "getSeat" && it.parameterTypes.isEmpty() }
            ?.invoke(v)
        seat?.javaClass?.methods
            ?.filter { it.name.contains("Occupancy") || it.name.contains("BeltBuckled") }
            ?.sortedBy { it.name }
            ?.forEach { log("  ${it.toGenericString()}") }
        val gear = v.javaClass.methods
            .firstOrNull { it.name == "getGear" && it.parameterTypes.isEmpty() }
            ?.invoke(v)
        gear?.javaClass?.methods
            ?.filter { it.name.contains("GearSelection") }
            ?.sortedBy { it.name }
            ?.forEach { log("  ${it.toGenericString()}") }
    }

    private data class SeatSignal(
        val label: String,
        val check: String,
        val get: String,
        val register: String,
        val unregister: String
    )

    private val seatSignals = listOf(
        SeatSignal(
            "Occupancy",
            "checkSeatOccupancyCapabilities",
            "getCurrentSeatOccupancy",
            "registerSeatOccupancy",
            "unregisterSeatOccupancy"
        ),
        SeatSignal(
            "BeltBuckled",
            "checkSeatBeltBuckledCapabilities",
            "getCurrentSeatBeltBuckledState",
            "registerSeatBeltBuckledState",
            "unregisterSeatBeltBuckledState"
        )
    )

    private val seatSubscriptions = mutableListOf<Triple<SeatSignal, Any, Any>>()

    private fun describeArea(area: Any): String = runCatching {
        val values = area.javaClass.getMethod("getValues").invoke(area)
        if (values is Array<*>) values.joinToString("+") else values.toString()
    }.getOrElse { area.toString() }

    private fun seatProbe() = safe("좌석 탐색") {
        val seat = requireNotNull(vehicle) { "1번 Vehicle 초기화를 먼저 실행" }.getSeat()
        seatSignals.forEach { signal ->
            val check = seat.javaClass.methods.firstOrNull {
                it.name == signal.check && it.parameterTypes.size == 2 && it.parameterTypes[0] == Function1::class.java
            }
            if (check == null) {
                log("${signal.label}: capability 메서드를 찾지 못함")
                return@forEach
            }
            val onAreas: (Any?) -> Unit = { areas ->
                val list = (areas as? List<*>).orEmpty().filterNotNull()
                log("${signal.label} 지원 영역 ${list.size}개: ${list.joinToString { describeArea(it) }}")
                list.forEach { area -> seatTrack(seat, signal, area) }
            }
            val onFail: (Any?) -> Unit = {
                log("${signal.label} capability 실패: ${(it as? Exception)?.message}")
            }
            check.invoke(seat, onAreas, onFail)
        }
    }

    private fun seatTrack(seat: Any, signal: SeatSignal, area: Any) {
        val name = "${signal.label} ${describeArea(area)}"
        val getter = seat.javaClass.methods.firstOrNull { it.name == signal.get && it.parameterTypes.size == 3 }
        val onValue: (Any?) -> Unit = { log("Seat 현재값 $name = $it") }
        val onFail: (Any?) -> Unit = { log("Seat 읽기 실패 $name: ${(it as? Exception)?.message}") }
        runCatching { getter?.invoke(seat, area, onValue, onFail) }
            .onFailure { log("Seat 읽기 예외 $name: ${it.cause?.message ?: it.message}") }

        val register = seat.javaClass.methods.firstOrNull { it.name == signal.register && it.parameterTypes.size == 2 }
            ?: return
        val listenerType = register.parameterTypes[1]
        val listener = Proxy.newProxyInstance(listenerType.classLoader, arrayOf(listenerType)) { self, call, args ->
            if (call.declaringClass == Any::class.java) {
                when (call.name) {
                    "equals" -> self === args?.firstOrNull()
                    "hashCode" -> System.identityHashCode(self)
                    else -> "SeatProxy"
                }
            } else {
                log("Seat 변경 $name ${call.name}(${args?.joinToString().orEmpty()})")
                null
            }
        }
        runCatching {
            register.invoke(seat, area, listener)
            seatSubscriptions.add(Triple(signal, area, listener))
            log("Seat 구독 등록 $name")
        }.onFailure { log("Seat 구독 실패 $name: ${it.cause?.message ?: it.message}") }
    }

    private fun seatUnsubscribe() = safe("좌석 구독 해제") {
        val seat = vehicle?.getSeat() ?: return@safe
        seatSubscriptions.toList().forEach { (signal, area, listener) ->
            safe("unregister ${signal.label}") {
                seat.javaClass.methods
                    .firstOrNull { it.name == signal.unregister && it.parameterTypes.size == 2 }
                    ?.invoke(seat, area, listener)
            }
        }
        seatSubscriptions.clear()
        log("Seat 구독 해제 완료")
    }

    // 감시를 멈추지 않고 등록된 사람과 짐만 비운다.
    private fun clearList() = safe("목록 초기화") {
        val flow = AutoService.current
        if (flow != null) {
            flow.clearRegistrations()
        } else {
            PassengerStore(applicationContext).clear()
            log("등록 목록 초기화")
        }
    }

    private fun autoStart() = safe("자동 모드 시작") {
        AutoService.start(applicationContext, true)
        log("자동 모드 서비스 시작 요청, 등록 목록 초기화")
    }

    private fun autoStop() = safe("자동 모드 종료") {
        AutoService.stop(applicationContext)
    }

    private fun injectSeat(seat: String, occupied: Boolean) = safe("좌석 주입") {
        val flow = AutoService.current
        if (flow == null) {
            log("자동 모드가 실행 중이 아님")
            return@safe
        }
        flow.injectSeat(seat, occupied)
    }

    private fun injectDoor(door: String, open: Boolean) = safe("문 주입") {
        val flow = AutoService.current
        if (flow == null) {
            log("자동 모드가 실행 중이 아님")
            return@safe
        }
        flow.injectDoor(door, open)
    }

    private fun clearInjected() = safe("주입 해제") {
        AutoService.current?.clearInjected()
    }

    private fun injectRolling(on: Boolean) = safe("주행 모사") {
        val flow = AutoService.current
        if (flow == null) {
            log("자동 모드가 실행 중이 아님")
            return@safe
        }
        flow.injectRolling(on)
    }

    private fun attachProxy(target: Any, methodName: String, label: String) {
        val method = target.javaClass.methods.firstOrNull {
            it.name == methodName && it.parameterTypes.size == 1
        }
        if (method == null) {
            log("$label: $methodName 메서드를 찾지 못함")
            return
        }
        val type = method.parameterTypes[0]
        method.invoke(target, newProxy(type, label))
        log("$label 리스너 등록 완료, 타입=${type.name}")
    }

    private fun newProxy(type: Class<*>, label: String): Any =
        Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { self, call, args ->
            when {
                call.declaringClass == Any::class.java -> when (call.name) {
                    "equals" -> self === args?.firstOrNull()
                    "hashCode" -> System.identityHashCode(self)
                    else -> "ProbeProxy"
                }
                else -> {
                    val now = System.currentTimeMillis()
                    val quiet = call.name == "onUpdatedRms" && now - lastRmsLog < 1000
                    if (!quiet) {
                        if (call.name == "onUpdatedRms") lastRmsLog = now
                        log("$label.${call.name}(${args?.joinToString { it.toString() }.orEmpty()})")
                    }
                    when (call.returnType) {
                        java.lang.Boolean.TYPE -> false
                        java.lang.Integer.TYPE -> 0
                        java.lang.Long.TYPE -> 0L
                        java.lang.Double.TYPE -> 0.0
                        java.lang.Float.TYPE -> 0f
                        else -> null
                    }
                }
            }
        }
}