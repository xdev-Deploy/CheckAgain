package dev.hjun.checkagain

import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.constant.Mode as SttMode
import ai.pleos.playground.vehicle.constant.area.DoorValue
import android.content.Context
import android.os.Handler
import android.os.Looper
import dev.hjun.checkagain.TextParser.classifyName
import dev.hjun.checkagain.TextParser.cleanName
import dev.hjun.checkagain.TextParser.fixItems
import dev.hjun.checkagain.TextParser.isRepeatRequest
import dev.hjun.checkagain.TextParser.isYes
import dev.hjun.checkagain.TextParser.nameFix
import dev.hjun.checkagain.TextParser.parseBoth
import dev.hjun.checkagain.TextParser.splitItems
import dev.hjun.checkagain.TextParser.stripLeadYes
import dev.hjun.checkagain.TextParser.stripNegation
import java.lang.reflect.Proxy

/**
 * 이벤트 처리 규칙
 * - 승차: 좌석이 점유되면 후보에 올리고, 모든 문이 닫히는 즉시 "누가 탑승했나요?"를 묻는다.
 * - 문만 여닫음 / 짐만 넣음: 좌석 변화 없이 문만 열렸다 닫히면 같은 질문을 한다. "짐"이라고 답하면 짐으로 등록한다.
 * - 문 구간: 문이 처음 열릴 때부터 모든 문이 닫히고 좌석 값이 확정될 때까지를 한 구간으로 본다.
 * - 자리 이동: 문 구간 밖에서 등록자의 자리가 비어 있다가 다른 자리가 점유되면 이동으로 보고 자리만 바꾼다.
 * - 하차: 열린 문과 같은 쪽 자리의 등록자가 비면 바로 안내한다. 다른 쪽 자리가 비어 있으면 구간이 끝날 때
 *   그 구간에 아무도 앉지 않은 경우에만 열린 문으로 내린 것으로 본다. 누군가 앉았다면 그 문은 탑승 때문이다.
 * - 시간 기준으로 판단하지 않고 센서와 문 이벤트가 올라올 때 판단한다.
 * - 좌석 센서가 없는 문: 문 기준으로 등록하고, 주행 후 같은 문이 열리면 안내한다.
 * - 트렁크(REAR): 좌석이 없으므로 항상 문 기준이다. 짐을 넣으면 "누구 짐인지" 묻고, 주행 후 열리면 안내한다.
 */
class AutoFlow(
    context: Context,
    private val log: (String) -> Unit,
    private val notice: (kind: String, title: String, text: String) -> Unit = { _, _, _ -> }
) {

    private val app = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val store = PassengerStore(app)

    // 등록 대기 좌석. 값이 true면 문 기준(좌석 센서 없이 문만 여닫힘)
    private val pending = LinkedHashMap<String, Boolean>()

    // 시작 때 이미 앉아 있었던 좌석. 질문을 "탑승했나요"가 아니라 "탑승해 있나요"로 한다.
    private val initialSeats = mutableSetOf<String>()

    // 시작 시 센서가 점유로 읽은 미등록 좌석을 잠시 모아 두는 묶음, 그리고 여러 자리일 때 한 번만 확인하기 위해 둔 좌석들
    private val initialBatch = LinkedHashSet<String>()
    private val gateSeats = LinkedHashSet<String>()
    private var askingGate = false
    private val resolveInitialTask = Runnable { resolveInitial() }

    private val cycleDoors = mutableSetOf<DoorValue>()
    private val speechQueue = ArrayDeque<Triple<String, (() -> Unit)?, () -> Unit>>()

    private var monitor: VehicleMonitor? = null
    private var stopped = true
    private var busy = false
    private var speaking = false
    private var listening = false
    private var seatEdge = false
    private var cycleActive = false
    private var cycleClosed = false
    private var boarded = false
    private var fixes = 0

    // 다시 말해 달라는 요청을 받은 횟수(등록 한 건 기준)와, 늦게 도착한 LLM 응답을 버리기 위한 대화 구분 번호
    private var repeats = 0
    private var llmEpoch = 0

    // 마이크 밖에서 취소를 눌렀을 때 올라간다. 이미 읽고 있던 질문이 끝나도 마이크를 켜지 않게 막는 데 쓴다.
    private var cancelGen = 0
    private val mutedDoors = mutableMapOf<String, Long>()
    private var activeStt: SpeechToText? = null
    private var currentSession: SttSession? = null

    // 마지막으로 말하기 큐가 움직인 시각. 대화 중인데 오래 멈춰 있으면 멈춘 상태로 본다.
    private var activityAt = 0L

    // 실행하면서 측정해 보정하는 값: 글자당 TTS 재생 시간, STT 요청부터 마이크가 준비되기까지의 시간
    private var msPerChar = MS_PER_CHAR.toDouble()
    private var readyLatency = READY_LATENCY_MS

    // 주기적으로 오래된 등록을 지우고, 이벤트 없이 열린 채 남은 문 구간을 닫는다.
    private val maintenanceTask = object : Runnable {
        override fun run() {
            if (stopped) return
            expireOld()
            releaseIfStalled()
            // 좌석 값이 이벤트 없이 원래대로 돌아와 구간이 열린 채 남은 경우를 정리한다.
            monitor?.let { if (cycleActive && cycleClosed && !it.seatSettling()) endCycle(it) }
            handler.postDelayed(this, MAINTENANCE_MS)
        }
    }

    // 하차를 감지하지 못한 등록이 다음 승객에게 잘못 안내되지 않도록 오래된 등록은 지운다.
    private fun expireOld() {
        val now = System.currentTimeMillis()
        val dropped = store.purge { it.registeredAt > 0L && now - it.registeredAt > REGISTRATION_TTL_MS }
        if (dropped.isNotEmpty()) log("오래된 등록 ${dropped.size}건 삭제")
    }

    fun start(reset: Boolean = false) {
        if (monitor != null) return
        stopped = false
        if (reset) {
            store.clear()
            log("등록 목록 초기화")
        }
        expireOld()
        monitor = VehicleMonitor(app, log, ::onDoor, ::onSeat, ::onSeatInitial).also { it.start() }
        handler.postDelayed(maintenanceTask, MAINTENANCE_MS)
        log("자동 모드 시작")
    }

    fun stop() {
        stopped = true
        llmEpoch++
        handler.removeCallbacksAndMessages(null)
        currentSession?.releaseBeep()
        runCatching { activeStt?.stop() }
        runCatching { activeStt?.release() }
        activeStt = null
        currentSession = null
        Live.set(Phase.IDLE)
        Live.level(0f)
        monitor?.stop()
        monitor = null
        pending.clear()
        initialSeats.clear()
        initialBatch.clear()
        gateSeats.clear()
        askingGate = false
        cycleDoors.clear()
        cycleActive = false
        cycleClosed = false
        boarded = false
        speechQueue.clear()
        busy = false
        speaking = false
        listening = false
        log("자동 모드 종료")
    }

    fun injectSeat(seat: String, occupied: Boolean) {
        handler.post { monitor?.injectSeat(seat, occupied) }
    }

    fun injectDoor(door: String, open: Boolean) {
        handler.post { monitor?.injectDoor(door, open) }
    }

    // 터치 버튼으로 지금 듣고 있는 질문에 답한다.
    fun forceAnswer(text: String) {
        handler.post { currentSession?.force(text) }
    }

    // 화면의 "등록 취소" 버튼. 이름·소지품·확인 어느 질문에서든 진행 중인 등록을 바로 접는다.
    fun cancelByTouch() {
        handler.post {
            val session = currentSession
            when {
                session != null -> session.force(CANCEL_TOKEN)
                busy && !stopped -> cancelOutsideMic()
            }
        }
    }

    // 마이크가 꺼져 있는 동안(질문을 읽는 중이거나 LLM 응답을 기다리는 중)에 누른 취소.
    // 읽던 말을 멈추고 대기 중인 말과 늦게 올 LLM 응답을 버린 뒤, 마이크 안에서 취소한 것과 같은 결과로 끝낸다.
    private fun cancelOutsideMic() {
        cancelGen++
        llmEpoch++
        speechQueue.clear()
        TtsSpeaker.stopCurrent()
        if (askingGate) {
            askingGate = false
            gateSeats.clear()
            log("시작 확인 건너뜀(버튼, 마이크 밖)")
            say("알겠어요. 등록하지 않을게요.")
            endDialog("시작 확인 건너뜀")
        } else {
            log("터치로 등록 취소(마이크 밖)")
            say("등록을 취소했어요.")
            decline("터치로 취소")
        }
    }

    // 화면의 차량 그림에 쓸 현재 좌석 점유 상태
    fun seatStates(): Map<String, Boolean> = monitor?.seatsForDisplay().orEmpty()

    fun clearInjected() {
        handler.post { monitor?.clearInjected() }
    }

    fun injectRolling(on: Boolean) {
        handler.post { monitor?.injectRolling(on) }
    }

    // ---------- 문 ----------

    private fun onDoor(area: DoorValue, open: Boolean) {
        log("Door ${area.name} -> ${if (open) "열림" else "닫힘"}")
        val m = monitor ?: return
        if (stopped) return
        if (open) {
            if (area == DoorValue.ROW_1_LEFT) return
            if (!cycleActive || cycleClosed) startCycle()
            cycleDoors.add(area)
            if (!m.isRolling()) announceByDoor(area, m)
        } else if (!m.anyDoorOpen()) {
            onAllClosed(m)
        }
    }

    private fun startCycle() {
        cycleActive = true
        cycleClosed = false
        cycleDoors.clear()
        seatEdge = false
        boarded = false
    }

    private fun onAllClosed(m: VehicleMonitor) {
        // 좌석이 먼저 반응했다면 문이 닫히는 즉시 질문한다.
        tryPrompt()
        if (!cycleActive) return
        cycleClosed = true
        // 좌석 값이 바뀌는 중이면 그 좌석 이벤트가 온 뒤에 구간을 끝낸다.
        if (!m.seatSettling()) endCycle(m)
    }

    private fun endCycle(m: VehicleMonitor) {
        val doors = cycleDoors.toList()
        var edge = seatEdge
        val seated = boarded
        cycleActive = false
        cycleClosed = false
        cycleDoors.clear()
        seatEdge = false
        boarded = false
        if (stopped || doors.isEmpty() || m.isMoving() || m.anyDoorOpen()) return
        // 이번 구간에 아무도 앉지 않았는데 자리가 비어 있는 등록자가 있으면 열린 문으로 내린 것이다.
        if (!seated && !m.isRolling()) {
            val gone = emptySeatOwners(m)
            if (gone.isNotEmpty()) {
                gone.forEach { leave(it) }
                edge = true
            }
        }
        if (edge) return
        // 좌석 변화 없이 문만 열렸다 닫힌 경우: 탑승 또는 짐만 넣은 것으로 보고 질문한다.
        val door = doors.firstOrNull { it != DoorValue.REAR && it != DoorValue.HOOD } ?: doors.first()
        if (mutedDoors[door.name] == m.lastTripAt) {
            log("문만 여닫음, 이번 정차에서 거절한 문: ${door.name}")
            return
        }
        if (store.people().any { it.seat == door.name }) return
        pending[door.name] = true
        log("문만 여닫음: ${door.name} 탑승 또는 짐 후보")
        tryPrompt()
    }

    // 문이 열리면 그 문 쪽 자리의 등록자가 내린 것이다. 센서 없는 자리와 짐은 주행 뒤 같은 문이 열릴 때 안내한다.
    private fun announceByDoor(area: DoorValue, m: VehicleMonitor) {
        emptySeatOwners(m).filter { matchesDoor(it.seat, listOf(area)) }.forEach { leave(it) }
        store.people()
            .filter { it.viaDoor && it.seat == area.name && tripHappened(it) }
            .forEach { leave(it) }
    }

    // 가운데 자리는 같은 열의 어느 문으로도 내릴 수 있다.
    private fun matchesDoor(seat: String?, doors: Collection<DoorValue>): Boolean {
        if (seat == null) return false
        val row = seat.substringBefore("_CENTER", "")
        return doors.any { it.name == seat || (row.isNotEmpty() && it.name.startsWith(row)) }
    }

    // ---------- 좌석 ----------

    private fun onSeat(seat: String, occupied: Boolean) {
        log("Seat $seat -> ${if (occupied) "점유" else "비움"}")
        if (stopped || seat == DRIVER_SEAT) return
        val m = monitor ?: return
        seatEdge = true
        if (occupied) sat(seat, m) else vacated(seat, m)
        if (cycleActive && cycleClosed && !m.seatSettling()) endCycle(m)
    }

    // 시작할 때 센서가 이미 점유로 읽힌 동승자 좌석. 등록이 없으면 타 있던 사람으로 보고 물어본다.
    private fun onSeatInitial(seat: String) {
        if (stopped || seat == DRIVER_SEAT) return
        if (store.people().any { !it.viaDoor && it.seat == seat }) return
        log("시작 시 앉아 있는 좌석: $seat")
        // 좌석 값이 연달아 올라오므로 잠깐 모았다가 한 번에 판단한다.
        initialBatch.add(seat)
        handler.removeCallbacks(resolveInitialTask)
        handler.postDelayed(resolveInitialTask, INITIAL_BATCH_MS)
    }

    // 한 자리면 바로 좌석을 집어 묻고, 여러 자리면 "등록 안 된 탑승자가 N분 계세요. 등록할까요?"를 한 번만 물어본다.
    private fun resolveInitial() {
        val m = monitor ?: return
        val seats = initialBatch.filter { s -> m.seats()[s] == true && store.people().none { it.seat == s } }
        initialBatch.clear()
        if (stopped || seats.isEmpty()) return
        if (seats.size == 1) {
            initialSeats.add(seats.first())
            pending[seats.first()] = false
        } else {
            gateSeats.addAll(seats)
        }
        tryPrompt()
    }

    private fun sat(seat: String, m: VehicleMonitor) {
        if (store.people().any { !it.viaDoor && it.seat == seat }) return
        boarded = true
        // 문 구간 밖에서 등록자의 자리가 비어 있다가 다른 자리가 점유되면 자리 이동
        val mover = if (cycleActive || m.anyDoorOpen()) null else emptySeatOwners(m).firstOrNull()
        if (mover != null) {
            store.bind(mover.id, seat, false)
            log("자리 이동 -> $seat")
            return
        }
        log("이동 아님: 문 구간=$cycleActive, 문 열림=${m.anyDoorOpen()}, 빈 자리 등록자=${emptySeatOwners(m).size}")
        if (m.isRolling()) return
        pending[seat] = false
        log("승차 후보: $seat")
        tryPrompt()
    }

    private fun vacated(seat: String, m: VehicleMonitor) {
        val owner = store.people().firstOrNull { !it.viaDoor && it.seat == seat }
        if (owner == null) {
            pending.remove(seat)
            log("비운 자리에 좌석 기준 등록자 없음: $seat")
            return
        }
        // 이번 문 구간에 열린 문 중 이 자리 쪽 문이 있으면 그 문으로 내린 것이다.
        // 그렇지 않으면 구간이 끝날 때나 다음 이벤트(다른 자리 점유, 문 열림)에서 판단한다.
        if (cycleActive && !m.isRolling() && matchesDoor(owner.seat, cycleDoors)) leave(owner)
    }

    // 좌석 센서 기준으로 지금 자리가 비어 있는 등록자
    private fun emptySeatOwners(m: VehicleMonitor): List<Person> {
        val seats = m.seats()
        return store.people()
            .filter { p -> !p.viaDoor && p.seat?.let { seats[it] } == false }
            .sortedBy { it.registeredAt }
    }

    // ---------- 하차 안내 ----------

    private fun leave(p: Person) {
        seatEdge = true
        store.remove(p.id)
        val text = alightText(p)
        log("하차 안내")
        notice(Kind.ALIGHT, "하차 확인", text)
        speakGuide(text)
    }

    private fun alightText(p: Person): String =
        if (p.name.startsWith(BAG)) {
            "${seatLabel(p.seat)} 짐 확인하세요."
        } else {
            "${p.name}님 ${p.items.joinToString(", ")} 확인하세요."
        }

    // 하차 안내를 소리와 함께 화면 카드로도 보여 준다. 말이 끝난 뒤 잠깐 더 둘 때 다른 대화가 시작됐으면 건드리지 않는다.
    private fun speakGuide(text: String) {
        say(text, onStart = { if (!listening) Live.set(Phase.SPEAKING, text, alight = true) }) {
            handler.postDelayed({
                if (!stopped && !busy && !speaking && !listening) Live.set(Phase.IDLE)
            }, GUIDE_HOLD_MS)
        }
    }

    // 대화 중이라고 되어 있는데 마이크 세션도 없고 말하기 큐가 오래 움직이지 않으면 상태를 풀어 준다.
    // 30초마다 도는 정비 작업과 화면에서 누를 때 매번 부른다.
    private fun releaseIfStalled() {
        if (!busy || currentSession != null) return
        if (System.currentTimeMillis() - activityAt <= STALL_MS) return
        log("멈춘 대화 정리: speaking=$speaking listening=$listening 큐=${speechQueue.size}")
        speechQueue.clear()
        speaking = false
        listening = false
        busy = false
        Live.set(Phase.IDLE)
        Live.level(0f)
    }

    // 앉아 있지만 등록되지 않은 좌석에서 화면의 "등록하기"를 눌렀을 때. 탑승 질문을 바로 시작한다.
    // 사용자가 직접 눌렀으므로 문 열림과 주행 여부는 보지 않고 바로 질문한다. 시작하지 못하면 이유를 돌려준다(성공이면 null).
    fun registerSeat(seat: String): String? {
        val m = monitor
        if (stopped || m == null) return "자동 모드가 꺼져 있어요"
        if (seat == DRIVER_SEAT || m.seats()[seat] != true) return "이 좌석은 비어 있어요"
        if (store.people().any { it.seat == seat }) return "이미 등록된 좌석이에요"
        releaseIfStalled()
        log("화면 등록 요청: $seat busy=$busy listening=$listening speaking=$speaking session=${currentSession != null} pending=${pending.keys}")
        val session = currentSession
        if (busy && pending.keys.firstOrNull() == seat && session != null) return "지금 이 좌석을 묻고 있어요. 말씀해 주세요"
        if (busy && session == null) {
            return if (pending.keys.firstOrNull() == seat) "이 좌석 질문을 하는 중이에요. 잠시 후 말씀해 주세요"
            else "다른 질문을 말하는 중이에요. 끝나면 다시 눌러 주세요"
        }
        if (!busy && (listening || speaking)) return "안내를 말하는 중이에요. 끝나면 다시 눌러 주세요"
        gateSeats.remove(seat)
        val others = LinkedHashMap(pending)
        pending.clear()
        pending[seat] = false
        others.forEach { (k, v) -> if (k != seat) pending[k] = v }
        initialSeats.add(seat)
        if (busy && session != null) {
            // 마이크가 열려 있는 다른 좌석 질문을 끝내고 누른 좌석부터 다시 묻는다.
            session.force(RESTART_TOKEN)
            return null
        }
        busy = true
        fixes = 0
        repeats = 0
        askName(1)
        return null
    }

    // 등록된 물건 카드를 눌렀을 때: 등록은 그대로 두고 하차 안내를 다시 보여 주고 말한다.
    // 화면(메인 스레드)에서 바로 호출한다. 말할 수 없는 상태면 이유를 남기고 false를 돌려 화면이 대신 알릴 수 있게 한다.
    fun announce(id: String): Boolean {
        releaseIfStalled()
        if (stopped || busy || listening || speaking) {
            log("안내 보류: stopped=$stopped busy=$busy listening=$listening speaking=$speaking")
            return false
        }
        val p = store.people().firstOrNull { it.id == id } ?: return false
        log("목록에서 안내 다시 보기")
        speakGuide(alightText(p))
        return true
    }

    private fun tripHappened(p: Person): Boolean {
        val m = monitor ?: return false
        if (m.hasDriveSignal() && m.lastTripAt > p.registeredAt) return true
        return m.lastTripAt == 0L && System.currentTimeMillis() - p.registeredAt > NO_SIGNAL_TRIP_MS
    }

    // 등록은 만료되지 않는다. 하차가 감지되거나 사용자가 초기화할 때만 사라진다.
    fun clearRegistrations() {
        handler.post {
            store.clear()
            pending.clear()
            log("등록 목록 초기화")
            notice(Kind.INFO, "목록 비움", "등록된 물건을 모두 지웠어요.")
        }
    }

    // ---------- 등록 대화 ----------

    private fun tryPrompt() {
        val m = monitor ?: return
        if (stopped || busy || (pending.isEmpty() && gateSeats.isEmpty()) || m.isMoving() || m.anyDoorOpen()) return
        busy = true
        fixes = 0
        repeats = 0
        if (pending.isEmpty()) {
            askGate()
            return
        }
        log("등록 질문 시작: ${pending.keys.joinToString()}")
        askName(1)
    }

    // 시작 시 앉아 있던 미등록 좌석이 여러 개일 때 좌석별 질문 전에 한 번만 묻는다. 동의하면 좌석별 질문을 이어 가고, 아니면 모두 건너뛴다.
    private fun askGate() {
        val n = gateSeats.size
        askingGate = true
        log("시작 확인 질문: ${gateSeats.joinToString()}")
        ask("등록 안 된 탑승자가 ${n}분 계세요. 등록할까요?", 8000, false, true) { answer ->
            askingGate = false
            val t = answer?.replace(Regex("[\\s.,!?]"), "").orEmpty()
            val yes = t.isNotEmpty() && (isYes(t) || t.startsWith("등록할") || t.startsWith("할게"))
            val seats = gateSeats.toList()
            gateSeats.clear()
            if (yes) {
                seats.forEach {
                    initialSeats.add(it)
                    pending[it] = false
                }
                log("시작 확인: 등록 진행")
            } else {
                say("알겠어요. 등록하지 않을게요.")
                log("시작 확인: 건너뜀")
            }
            endDialog("시작 확인")
        }
    }

    private fun nameQuestion(): String {
        val first = pending.entries.first()
        if (first.key == DoorValue.REAR.name) return "트렁크에 누구 짐을 넣었나요?"
        if (first.key == DoorValue.HOOD.name) return "프렁크에 누구 짐을 넣었나요?"
        initialSeats.retainAll(pending.keys)
        // 시작 시 이미 앉아 있던 좌석은 좌석을 집어 묻고, 새로 탄 경우는 대기 좌석이 여러 개일 때만 좌석을 말한다.
        val where = if (pending.size > 1 || first.key in initialSeats) "${seatLabel(first.key)}에 " else ""
        if (first.key in initialSeats) return "${where}누가 탑승해 있나요?"
        val hint = if (first.value) " 짐만 두셨다면 짐이라고 말씀하세요." else ""
        return "${where}누가 탑승했나요?$hint"
    }

    private fun askName(attempt: Int) {
        if (pending.isEmpty()) {
            endDialog("대기 좌석 없음")
            return
        }
        ask(nameQuestion()) { answer ->
            if (answer.isNullOrBlank()) {
                decline("이름 응답 없음")
            } else if (isRepeatRequest(answer)) {
                // "뭐?", "못 들었어"를 이름으로 받지 않는다. 이름 단계는 외부로 보내지 않고 기기 안의 규칙으로만 가린다.
                askAgain { askName(attempt) }
            } else {
                when (val parsed = classifyName(answer)) {
                    NameAnswer.Cancel -> decline("등록 안 함")
                    NameAnswer.Bag -> commitBag()
                    NameAnswer.Unclear -> retry(attempt)
                    is NameAnswer.Name -> askItems(parsed.value, attempt)
                }
            }
        }
    }

    private fun askItems(name: String, attempt: Int) {
        ask("${name}님 소지품은요?") { answer ->
            val text = answer?.trim().orEmpty()
            val body = stripNegation(text)
            val both = parseBoth(body)
            val fixedName = nameFix(body) ?: if (body != text) cleanName(body) else null
            when {
                text.isBlank() -> retry(attempt)
                body.isBlank() -> retry(attempt)
                both != null && canFix() -> {
                    log("이름과 소지품 정정")
                    confirm(both.first, both.second, attempt)
                }
                fixedName != null && canFix() -> {
                    log("이름 정정")
                    askItems(fixedName, attempt)
                }
                else -> resolveItems(name, attempt, text)
            }
        }
    }

    // 소지품 답을 LLM으로 가른다. 되묻기면 같은 질문을 다시 하고, LLM을 쓸 수 없으면 기기 안의 규칙으로 이어 간다.
    private fun resolveItems(name: String, attempt: Int, text: String) {
        withLlm(LlmClient.STAGE_ITEMS, text, name, emptyList()) { r ->
            when {
                r != null && r.intent == "repeat" -> askAgain { askItems(name, attempt) }
                r != null && r.intent == "items" && r.items.isNotEmpty() -> confirm(name, r.items, attempt)
                (r == null || r.intent == "unclear") && isRepeatRequest(text) -> askAgain { askItems(name, attempt) }
                else -> {
                    val items = splitItems(text)
                    if (items.isEmpty()) retry(attempt) else confirm(name, items, attempt)
                }
            }
        }
    }

    private fun confirm(name: String, items: List<String>, attempt: Int) {
        ask("${name}님, ${items.joinToString(", ")}. 맞나요?", 6000, true) { answer ->
            val text = answer?.trim().orEmpty()
            val body = stripLeadYes(stripNegation(text)).trim()
            val both = parseBoth(body)
            val fixedName = nameFix(body)
            when {
                text.isBlank() -> {
                    say("등록하지 않았어요.")
                    decline("확인 응답 없음")
                }
                isYes(text) -> {
                    commit(name, items)
                    say("등록했어요.")
                    endDialog("등록 완료")
                }
                body.isBlank() -> retry(attempt)
                both != null && canFix() -> {
                    log("이름과 소지품 정정")
                    confirm(both.first, both.second, attempt)
                }
                fixedName != null && canFix() -> {
                    log("이름 정정")
                    confirm(fixedName, items, attempt)
                }
                else -> resolveConfirm(name, items, attempt, text, body)
            }
        }
    }

    // 확인 질문의 답 가운데 이름 정정이 아닌 것을 LLM으로 가른다. 실패하면 읽어 준 목록과 기기 안의 규칙(fixItems)으로 이어 간다.
    private fun resolveConfirm(name: String, items: List<String>, attempt: Int, text: String, body: String) {
        withLlm(LlmClient.STAGE_CONFIRM, text, name, items) { r ->
            when {
                r != null && r.intent == "repeat" -> askAgain { confirm(name, items, attempt) }
                r != null && r.intent == "yes" -> {
                    commit(name, items)
                    say("등록했어요.")
                    endDialog("등록 완료")
                }
                r != null && r.intent == "no" -> retry(attempt)
                r != null && r.intent == "correct" && r.items.isNotEmpty() && canFix() -> {
                    log("물품 정정")
                    confirm(name, r.items, attempt)
                }
                (r == null || r.intent == "unclear") && isRepeatRequest(text) -> askAgain { confirm(name, items, attempt) }
                canFix() -> {
                    val corrected = fixItems(items, body)
                    if (corrected.isEmpty()) {
                        retry(attempt)
                    } else {
                        log("물품 정정")
                        confirm(name, corrected, attempt)
                    }
                }
                else -> {
                    say("등록하지 않았어요.")
                    decline("정정 횟수 초과")
                }
            }
        }
    }

    // 사용자가 질문을 못 들었다고 하면 같은 질문을 다시 한다. 재시도 횟수는 올리지 않고, 너무 반복되면 접는다.
    private fun askAgain(again: () -> Unit) {
        repeats++
        if (repeats > MAX_REPEAT) {
            say("등록을 취소할게요.")
            decline("되묻기 초과")
            return
        }
        log("다시 말해 달라는 요청 $repeats")
        again()
    }

    // 이름을 지운 짧은 텍스트만 LLM에 보낸다. 꺼져 있거나 키가 없거나 문장이 길면 부르지 않고 null로 바로 넘긴다.
    private fun withLlm(stage: String, text: String, name: String, items: List<String>, onDone: (LlmResult?) -> Unit) {
        val masked = maskName(text, name)
        if (stopped || !LlmSettings.enabled(app) || !LlmSettings.available || masked.isEmpty() || masked.length > LLM_MAX_CHARS) {
            onDone(null)
            return
        }
        val epoch = llmEpoch
        var waiting = true
        activityAt = System.currentTimeMillis()
        handler.postDelayed({
            if (waiting && epoch == llmEpoch && !stopped) say("잠시만요")
        }, LLM_NOTICE_MS)
        LlmClient.classify(stage, masked, items, log) { result ->
            waiting = false
            activityAt = System.currentTimeMillis()
            if (epoch == llmEpoch && !stopped) onDone(result)
        }
    }

    private fun maskName(text: String, name: String): String {
        val trimmed = text.trim()
        if (name.isBlank()) return trimmed
        return trimmed.replace(Regex(Regex.escape(name) + "(?:이|가|은|는|아|야)?"), "").trim()
    }

    private fun canFix(): Boolean {
        if (fixes >= MAX_FIX) return false
        fixes++
        return true
    }

    private fun retry(attempt: Int) {
        if (attempt >= 2) {
            say("등록을 취소할게요.")
            decline("재시도 초과")
            return
        }
        askName(attempt + 1)
    }

    // 문 기준 후보를 거절하거나 답이 없으면 이번 정차 동안 그 문을 다시 묻지 않는다. 다른 문은 영향을 받지 않는다.
    private fun decline(reason: String) {
        val first = pending.entries.firstOrNull()
        if (first != null) {
            val seat = first.key
            val viaDoor = first.value
            pending.remove(seat)
            if (viaDoor) mutedDoors[seat] = monitor?.lastTripAt ?: -1L
        }
        endDialog(reason)
    }

    private fun commit(name: String, items: List<String>) {
        val first = pending.entries.firstOrNull()
        val seats = monitor?.seats().orEmpty()
        // 같은 이름이라도 그 사람의 자리가 아직 점유 중이면 동명이인이므로 덮어쓰지 않는다.
        val same = store.findSame(name) { p ->
            p.name.startsWith(BAG) || (!p.viaDoor && p.seat != first?.key && p.seat?.let { seats[it] } == true)
        }
        val seat = first?.key ?: same?.seat
        val viaDoor = first?.value ?: same?.viaDoor ?: false
        if (first != null) pending.remove(first.key)
        val stored = if (same != null && same.name.length > name.length) same.name else name
        store.put(same?.id, stored, items, seat, viaDoor)
        log("등록: 좌석=${seat ?: "미지정"}${if (viaDoor) "(문 기준)" else ""}")
        val where = if (seat != null) "${seatLabel(seat)} · " else ""
        notice(Kind.REGISTER, "물건 등록", "$where${stored}님 · ${items.joinToString(", ")}")
    }

    private fun commitBag() {
        val first = pending.entries.firstOrNull()
        if (first == null) {
            endDialog("대기 좌석 없음")
            return
        }
        val seat = first.key
        pending.remove(seat)
        store.put("bag-$seat", "$BAG$seat", listOf("짐"), seat, true)
        log("짐 등록: $seat")
        notice(Kind.REGISTER, "짐 등록", "${seatLabel(seat)}에 짐을 두었어요.")
        say("짐을 등록했어요.")
        endDialog("짐 등록")
    }

    private fun endDialog(reason: String) {
        log("대화 종료: $reason")
        llmEpoch++
        Live.set(Phase.IDLE)
        Live.level(0f)
        busy = false
        if (pending.isNotEmpty() || gateSeats.isNotEmpty()) handler.post { tryPrompt() }
    }

    // ---------- 음성 ----------

    private fun say(text: String, onStart: (() -> Unit)? = null, onDone: () -> Unit = {}) {
        activityAt = System.currentTimeMillis()
        speechQueue.addLast(Triple(text, onStart, onDone))
        if (!speaking) nextSpeech()
    }

    private fun nextSpeech() {
        activityAt = System.currentTimeMillis()
        if (listening) return
        val next = speechQueue.removeFirstOrNull()
        if (next == null) {
            speaking = false
            return
        }
        speaking = true
        val ttsStart = System.currentTimeMillis()
        var playStart = 0L
        log("TTS 시작: ${next.first.length}자")
        TtsSpeaker.speak(app, next.first, onStarted = {
            playStart = System.currentTimeMillis()
            next.second?.invoke()
        }) {
            handler.post {
                val now = System.currentTimeMillis()
                log("TTS 종료 +${now - ttsStart}ms")
                if (playStart != 0L && next.first.length >= 6) {
                    val measured = (now - playStart).toDouble() / next.first.length
                    msPerChar = ((msPerChar + measured) / 2).coerceIn(80.0, 400.0)
                    log("글자당 ${msPerChar.toInt()}ms")
                }
                next.third()
                nextSpeech()
            }
        }
    }

    // 질문을 시작할 때마다 올라간다. 마이크를 놓을 때 다음 질문이 이어졌는지 알아보는 데 쓴다.
    private var askSeq = 0

    private fun ask(prompt: String, timeoutMs: Long = 8000, confirm: Boolean = false, gate: Boolean = false, onAnswer: (String?) -> Unit) {
        askSeq++
        val gen = cancelGen
        log("질문")
        // STT는 요청부터 실제로 듣기 시작하기까지 1.5~2.5초가 걸린다. 그래서 TTS 재생이 시작된 뒤
        // 질문 길이로 추정한 시점에 미리 열어 두고, TTS 완료 이벤트가 오면 그때부터 인식 결과를 받는다.
        var session = SttSession(prompt)
        say(prompt, onStart = {
            if (gen != cancelGen) return@say
            Live.set(Phase.SPEAKING, prompt, confirm, gate = gate)
            // 소리가 실제로 끝나는 시점은 완료 이벤트보다 TTS_TAIL_MS 늦다.
            // 마이크가 그보다 EARLY_LEAD_MS 앞서 준비되도록 요청 시점을 거꾸로 계산한다.
            val speech = (prompt.length * msPerChar).toLong()
            val wait = (speech + TTS_TAIL_MS - EARLY_LEAD_MS - readyLatency).coerceAtLeast(0L)
            log("STT 미리 열기 ${wait}ms 뒤")
            handler.postDelayed({ if (!stopped && gen == cancelGen) session.warmUp() }, wait)
        }) {
            // 읽는 동안 취소가 눌렸으면 마이크를 켜지 않고 미리 열어 둔 STT도 닫는다.
            if (gen != cancelGen) {
                session.discard()
                return@say
            }
            // 미리 열어 둔 STT가 그 사이 실패하거나 끝났으면 새로 만든다.
            if (session.dead) {
                session.discard()
                session = SttSession(prompt)
            }
            listening = true
            log("마이크 켬")
            currentSession = session
            Live.set(Phase.LISTENING, prompt, confirm, gate = gate)
            session.begin(timeoutMs) { text ->
                listening = false
                currentSession = null
                // 말하기 큐를 다시 돌려야 다음 질문이 나온다. 큐를 돌리지 않고 끝내면 이후 모든 질문이 멈춘다.
                if (text == RESTART_TOKEN) {
                    askingGate = false
                    log("누른 좌석으로 질문 전환")
                    Live.set(Phase.IDLE)
                    fixes = 0
                    repeats = 0
                    askName(1)
                    nextSpeech()
                    return@begin
                }
                if (text == CANCEL_TOKEN && askingGate) {
                    // 시작 확인에서 건너뛰기를 누른 경우: 남은 좌석을 모두 접는다.
                    askingGate = false
                    gateSeats.clear()
                    log("시작 확인 건너뜀(버튼)")
                    say("알겠어요. 등록하지 않을게요.")
                    endDialog("시작 확인 건너뜀")
                    nextSpeech()
                    return@begin
                }
                if (text == CANCEL_TOKEN) {
                    log("터치로 등록 취소")
                    say("등록을 취소했어요.")
                    decline("터치로 취소")
                    nextSpeech()
                    return@begin
                }
                Live.set(Phase.SPEAKING, prompt)
                log("응답 ${text?.length ?: 0}자")
                onAnswer(text)
                nextSpeech()
            }
        }
    }

    private inner class SttSession(private val prompt: String) {
        private val stt by lazy { SpeechToText(app, SttMode.ON_DEVICE) }
        private var opened = false
        private var ready = false
        private var failed = false
        private var begun = false
        private var requested = false
        private var done = false
        private var last: String? = null
        private var final: String? = null
        private var callback: ((String?) -> Unit)? = null
        private var addListener: java.lang.reflect.Method? = null
        private var resultProxy: Any? = null
        private val createdAt = System.currentTimeMillis()

        private fun age() = "+${System.currentTimeMillis() - createdAt}ms"

        private var inUtterance = false
        private var tainted = false
        private var quietUntil = Long.MAX_VALUE
        private var listenTimeoutMs = 8000L
        private var timeoutTask: Runnable? = null
        private var endTask: Runnable? = null
        private var restarts = 0
        private var requestedAt = 0L

        // 켜짐 효과음은 STT가 실제로 듣기 시작했고(onReady) 질문 소리도 실제로 끝난 뒤에 낸다. 둘 중 늦은 쪽을 따른다.
        // 꺼짐 효과음은 켜짐 소리를 낸 경우에만 마이크를 끌 때 낸다.
        private var micReady = false
        private var beepPlanned = false
        private var beeped = false
        private var beginAt = 0L
        private val beepOn = Runnable {
            if (done) return@Runnable
            beeped = true
            Earcon.on()
        }

        private fun scheduleBeep() {
            if (done || beepPlanned || !begun || !micReady) return
            beepPlanned = true
            val now = System.currentTimeMillis()
            val delay = (beginAt + TTS_TAIL_MS - now).coerceAtLeast(0L)
            // 효과음이 끝날 때까지는 마이크가 그 소리를 답으로 듣지 않도록 여유를 둔다.
            quietUntil = maxOf(quietUntil, now + delay + Earcon.ON_MS + 40L)
            log("마이크 켜짐 효과음 ${delay}ms 뒤")
            handler.postDelayed(beepOn, delay)
        }

        val dead: Boolean get() = done || failed || (!begun && endTask != null)

        private var rmsFloor = Float.NaN
        private var rmsCount = 0

        // 화면 버튼으로 답을 대신 넣는다. 마이크가 열려 있는 동안에만 받는다.
        fun force(text: String) {
            if (!begun || done) return
            final = text
            finish("터치 입력")
        }

        // 마이크 입력 크기를 파형으로 보낸다. 바닥값을 따라가며 그보다 충분히 큰 소리만 살린다.
        // 질문이 재생되는 동안이나 직후 구간, 질문 소리로 판정된 구간은 0으로 보내 막대가 움직이지 않게 한다.
        private fun onRms(args: Array<Any?>) {
            val v = (args.getOrNull(0) as? Number)?.toFloat() ?: return
            val value = maxOf(v, 1f)
            rmsFloor = when {
                rmsFloor.isNaN() || value < rmsFloor -> value
                value < rmsFloor * 1.25f -> rmsFloor + (value - rmsFloor) * 0.05f
                else -> minOf(rmsFloor * 1.002f, value)
            }
            // 실측값이 수십에서 수백까지 나온다. 절댓값 차가 아니라 바닥값 대비 비율(dB)로 판단한다.
            val db = 20f * kotlin.math.log10(value / rmsFloor)
            if (++rmsCount % 40 == 0) log("RMS $v 바닥 $rmsFloor ${db.toInt()}dB")
            val open = begun && !tainted && System.currentTimeMillis() >= quietUntil
            val level = if (open) ((db - RMS_GATE_DB) / RMS_RANGE_DB).coerceIn(0f, 1f) else 0f
            Live.level(level)
        }

        fun warmUp() {
            if (!dead) open()
        }

        fun discard() = finish("폐기")

        // 인식 한 덩어리가 시작될 때 TTS 소리가 섞였을 수 있는지 정한다.
        // 질문이 끝나기 전이거나 끝난 직후(종료 이벤트보다 소리가 늦게 끝난다)에 시작된 것은 질문 소리로 본다.
        private fun markUtterance() {
            if (inUtterance) return
            inUtterance = true
            endTask?.let { handler.removeCallbacks(it) }
            endTask = null
            tainted = !begun || System.currentTimeMillis() < quietUntil
            if (tainted) log("질문 소리 구간에 시작된 인식, 결과는 버림")
        }

        private fun onEvent(name: String, args: Array<Any?>) {
            if (done) return
            when (name) {
                "onStartedRecognition" -> markUtterance()
                "onUpdated" -> {
                    markUtterance()
                    val raw = args.getOrNull(0) as? String
                    val completed = args.getOrNull(1) as? Boolean ?: false
                    if (!begun || raw.isNullOrBlank()) return
                    // 질문 소리와 같이 시작된 인식은 앞쪽 질문 소리를 떼어 내고 남은 답만 쓴다.
                    val text = if (tainted) stripEcho(raw) else raw
                    if (text.isNullOrBlank()) return
                    if (completed && !tainted && isEcho(text)) {
                        log("질문 소리와 같아 버림")
                        endTask?.let { handler.removeCallbacks(it) }
                        endTask = null
                        last = null
                        handler.postDelayed({ listenAgain() }, ECHO_RETRY_MS)
                        return
                    }
                    last = text
                    if (completed) {
                        final = text
                        finish("인식 완료")
                    }
                }
                "onEndedRecognition" -> {
                    inUtterance = false
                    if (begun && tainted && last == null) {
                        handler.postDelayed({ listenAgain() }, ECHO_RETRY_MS)
                    } else {
                        val task = Runnable { finish("인식 종료") }
                        endTask = task
                        handler.postDelayed(task, 700)
                    }
                }
                "onReady" -> {
                    if (requestedAt != 0L) {
                        val latency = System.currentTimeMillis() - requestedAt
                        requestedAt = 0L
                        readyLatency = ((readyLatency + latency) / 2).coerceIn(800L, 3500L)
                        log("STT 준비까지 ${latency}ms")
                    }
                    micReady = true
                    scheduleBeep()
                }
                "onUpdatedRms" -> onRms(args)
                "onError" -> finish("오류 ${args.getOrNull(0)}")
            }
        }

        // 질문 소리를 듣던 인식이 끝났으면 같은 STT로 다시 듣는다.
        private fun listenAgain() {
            if (done) return
            if (restarts++ >= 2) {
                finish("재시도 초과")
                return
            }
            inUtterance = false
            tainted = false
            last = null
            final = null
            requested = false
            scheduleTimeout()
            log("STT 다시 듣기")
            requestNow()
        }

        private fun scheduleTimeout() {
            timeoutTask?.let { handler.removeCallbacks(it) }
            val task = Runnable { finish("시간 초과") }
            timeoutTask = task
            handler.postDelayed(task, listenTimeoutMs)
        }

        private fun norm(s: String) = s.replace(Regex("[\\s.,!?]"), "")

        // 질문 소리가 앞에 섞인 인식 결과에서 질문과 겹치는 앞부분을 뗀 나머지를 돌려준다.
        // 겹치는 앞부분이 3자 미만이거나 남은 말이 없으면 질문 소리로만 보고 null을 돌려준다.
        private fun stripEcho(text: String): String? {
            val heard = norm(text)
            val spoken = norm(prompt)
            var k = heard.length
            while (k >= 3 && !spoken.contains(heard.substring(0, k))) k--
            if (k < 3) return null
            var seen = 0
            var idx = 0
            while (idx < text.length && seen < k) {
                if (!text[idx].isWhitespace() && text[idx] !in ".,!?") seen++
                idx++
            }
            val rest = text.substring(idx).trim()
            val len = norm(rest).length
            // 한 글자 남은 말은 질문 끝을 잘못 들은 조각일 수 있어 긍정 답일 때만 쓴다.
            if (len == 0 || (len < 2 && !isYes(rest))) return null
            log("질문 소리 뒤 답 분리: $rest")
            return rest
        }

        private fun isEcho(text: String): Boolean {
            val heard = norm(text)
            return heard.length >= 2 && norm(prompt).contains(heard)
        }

        private fun open() {
            if (opened) return
            opened = true
            activeStt = stt
            val add = stt.javaClass.methods.firstOrNull { it.name == "addListener" && it.parameterTypes.size == 1 }
            val initialize = stt.javaClass.methods.firstOrNull { it.name == "initialize" && it.parameterTypes.size == 1 }
            if (add == null || initialize == null) {
                log("STT addListener 또는 initialize(listener)를 찾지 못함")
                failed = true
                return
            }
            addListener = add
            resultProxy = proxyOf(add.parameterTypes[0]) { name, args ->
                if (name != "onUpdatedRms") log("STT.$name(${args.joinToString()}) ${age()}")
                handler.post { onEvent(name, args) }
            }
            val initProxy = proxyOf(initialize.parameterTypes[0]) { name, _ ->
                log("STT init.$name ${age()}")
                when (name) {
                    "onSuccess" -> handler.post { onInitialized() }
                    "onFailure" -> handler.post {
                        failed = true
                        if (begun) finish("초기화 실패")
                    }
                }
            }
            runCatching { initialize.invoke(stt, initProxy) }.onFailure {
                log("STT initialize 실패: ${it.message}")
                failed = true
            }
        }

        private fun onInitialized() {
            if (done) return
            val added = runCatching { addListener?.invoke(stt, resultProxy) }
            if (added.isFailure) {
                log("STT 시작 실패: ${added.exceptionOrNull()?.message}")
                failed = true
                if (begun) finish("시작 실패")
                return
            }
            ready = true
            log("STT 준비 완료 ${age()}")
            requestNow()
        }

        fun begin(timeoutMs: Long, onResult: (String?) -> Unit) {
            callback = onResult
            begun = true
            quietUntil = System.currentTimeMillis() + ECHO_GRACE_MS
            beginAt = System.currentTimeMillis()
            scheduleBeep()
            log("STT begin ${age()}")
            if (stopped) {
                finish("중지됨")
                return
            }
            listenTimeoutMs = timeoutMs
            scheduleTimeout()
            open()
            if (failed) finish("준비 실패")
        }


        private fun requestNow() {
            if (done || requested) return
            requested = true
            if (requestedAt == 0L) requestedAt = System.currentTimeMillis()
            log("STT 요청 ${age()}")
            runCatching { stt.request() }.onFailure {
                log("STT 시작 실패: ${it.message}")
                finish("시작 실패")
            }
        }

        private fun finish(reason: String) {
            if (done) return
            done = true
            if (currentSession === this) currentSession = null
            handler.removeCallbacks(beepOn)
            Live.level(0f)
            log("STT 종료: $reason")
            if (opened) {
                runCatching { stt.stop() }
                runCatching { stt.release() }
                if (activeStt === stt) activeStt = null
            }
            val seq = askSeq
            callback?.invoke(final ?: last)
            // 마이크를 더 쓰지 않고 놓는 모든 경우 꺼짐 소리를 낸다. 답을 듣고 바로 다음 질문이 이어지면(콜백 안에서 새 질문이 시작됨) 내지 않는다.
            if (beeped && askSeq == seq) Earcon.off()
            beeped = false
        }

        // 자동 모드를 꺼서 마이크를 강제로 놓을 때도 꺼짐 소리를 낸다.
        fun releaseBeep() {
            handler.removeCallbacks(beepOn)
            if (beeped) Earcon.off()
            beeped = false
        }
    }

    // 문장 해석은 TextParser.kt에서 처리한다.

    private fun seatLabel(seat: String?): String = when (seat) {
        "ROW_1_RIGHT" -> "조수석"
        "ROW_2_LEFT" -> "뒷좌석 왼쪽"
        "ROW_2_CENTER" -> "뒷좌석 가운데"
        "ROW_2_RIGHT" -> "뒷좌석 오른쪽"
        "REAR" -> "트렁크"
        "HOOD" -> "프렁크"
        else -> "해당 좌석"
    }

    private fun proxyOf(type: Class<*>, onCall: (String, Array<Any?>) -> Unit): Any =
        Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { self, call, args ->
            when {
                call.declaringClass == Any::class.java -> when (call.name) {
                    "equals" -> self === args?.firstOrNull()
                    "hashCode" -> System.identityHashCode(self)
                    else -> "AutoFlowProxy"
                }
                else -> {
                    onCall(call.name, args ?: emptyArray())
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

    private companion object {
        const val DRIVER_SEAT = "ROW_1_LEFT"
        const val BAG = "짐@"
        const val CANCEL_TOKEN = "__cancel__"
        const val RESTART_TOKEN = "__restart__"
        const val STALL_MS = 25_000L

        // 시작 좌석 값이 연달아 올라오는 간격을 묶어 보는 시간
        const val INITIAL_BATCH_MS = 800L
        const val MAINTENANCE_MS = 30_000L

        // 하차 안내 카드를 말이 끝난 뒤에도 둘 시간
        const val GUIDE_HOLD_MS = 3000L

        // 이 시간이 지난 등록은 하차를 놓친 것으로 보고 지운다.
        const val REGISTRATION_TTL_MS = 24L * 60 * 60 * 1000
        const val NO_SIGNAL_TRIP_MS = 120_000L
        const val MAX_FIX = 4
        const val MAX_REPEAT = 3

        // LLM에 보내는 문장 길이 상한과, 응답이 늦을 때 "잠시만요"를 말하는 시점
        const val LLM_MAX_CHARS = 40
        const val LLM_NOTICE_MS = 3_000L

        // STT를 미리 연 시점 계산용. MS_PER_CHAR와 READY_LATENCY_MS는 초기 추정값이고 실행 중 측정값으로 보정된다.
        const val MS_PER_CHAR = 200L
        // 마이크는 질문 소리가 끝나기 이만큼 전에 이미 듣고 있도록 미리 연다.
        const val EARLY_LEAD_MS = 400L
        const val TTS_TAIL_MS = 900L
        const val READY_LATENCY_MS = 1800L

        // TTS 종료 이벤트 이후에도 소리가 잠시 남는다. 이 시간 안에 시작된 인식은 질문 소리로 본다.
        // 소리가 끝나는 시점(TTS_TAIL_MS)에 켜짐 효과음(Earcon.ON_MS)까지 더해 그 안에서 시작된 인식은 앱 소리로 본다.
        const val ECHO_GRACE_MS = TTS_TAIL_MS + Earcon.ON_MS + 40L
        const val ECHO_RETRY_MS = 50L

        // 파형: 바닥값보다 RMS_GATE_DB 이상 커야 움직이고, RMS_RANGE_DB만큼 더 커지면 최대 높이
        const val RMS_GATE_DB = 4f
        const val RMS_RANGE_DB = 20f
    }
}
