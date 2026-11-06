package dev.hjun.checkagain

import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.constant.Mode as SttMode
import ai.pleos.playground.vehicle.constant.area.DoorValue
import android.content.Context
import android.os.Handler
import android.os.Looper
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
 */
class AutoFlow(
    context: Context,
    private val log: (String) -> Unit,
    private val notice: (String) -> Unit = {}
) {

    private val app = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val store = PassengerStore(app)

    // 등록 대기 좌석. 값이 true면 문 기준(좌석 센서 없이 문만 여닫힘)
    private val pending = LinkedHashMap<String, Boolean>()

    private val cycleDoors = mutableSetOf<DoorValue>()
    private val speechQueue = ArrayDeque<Pair<String, () -> Unit>>()

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
    private var mutedTripAt = -1L
    private var activeStt: SpeechToText? = null

    private val negPrefix = Regex("^(?:아니고|아니야|아니요|아니|아냐|틀렸어|틀려|다시)[,.\\s]*")
    private val namePrefix = Regex("^이름(?:이|은|는)?\\s*")

    private val purgeTask = object : Runnable {
        override fun run() {
            if (stopped) return
            purge()
            // 좌석 값이 이벤트 없이 원래대로 돌아와 구간이 열린 채 남은 경우를 정리한다.
            monitor?.let { if (cycleActive && cycleClosed && !it.seatSettling()) endCycle(it) }
            handler.postDelayed(this, PURGE_MS)
        }
    }

    fun start(reset: Boolean = false) {
        if (monitor != null) return
        stopped = false
        if (reset) {
            store.clear()
            log("등록 목록 초기화")
        }
        purge()
        monitor = VehicleMonitor(app, log, ::onDoor, ::onSeat).also { it.start() }
        handler.postDelayed(purgeTask, PURGE_MS)
        log("자동 모드 시작")
    }

    fun stop() {
        stopped = true
        handler.removeCallbacksAndMessages(null)
        runCatching { activeStt?.stop() }
        runCatching { activeStt?.release() }
        activeStt = null
        monitor?.stop()
        monitor = null
        pending.clear()
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
        if (mutedTripAt == m.lastTripAt) {
            log("문만 여닫음, 이번 정차는 질문 안 함")
            return
        }
        val door = doors.first()
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

    private fun sat(seat: String, m: VehicleMonitor) {
        if (store.people().any { !it.viaDoor && it.seat == seat }) return
        boarded = true
        // 문 구간 밖에서 등록자의 자리가 비어 있다가 다른 자리가 점유되면 자리 이동
        val mover = if (cycleActive || m.anyDoorOpen()) null else emptySeatOwners(m).firstOrNull()
        if (mover != null) {
            store.bind(mover.name, seat, false)
            log("자리 이동 -> $seat")
            return
        }
        if (m.isRolling()) return
        pending[seat] = false
        log("승차 후보: $seat")
        tryPrompt()
    }

    private fun vacated(seat: String, m: VehicleMonitor) {
        val owner = store.people().firstOrNull { !it.viaDoor && it.seat == seat }
        if (owner == null) {
            pending.remove(seat)
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
        store.remove(p.name)
        val text = if (p.name.startsWith(BAG)) {
            "${seatLabel(p.seat)} 짐 확인하세요."
        } else {
            "${p.name}님 ${p.items.joinToString(", ")} 확인하세요."
        }
        log("하차 안내")
        notice(text)
        say(text)
    }

    private fun tripHappened(p: Person): Boolean {
        val m = monitor ?: return false
        if (m.hasDriveSignal() && m.lastTripAt > p.registeredAt) return true
        return m.lastTripAt == 0L && System.currentTimeMillis() - p.registeredAt > NO_SIGNAL_TRIP_MS
    }

    private fun purge() {
        val now = System.currentTimeMillis()
        val dropped = store.purge { now - it.registeredAt > REG_TTL_MS }
        if (dropped.isNotEmpty()) log("만료 정리: ${dropped.size}건")
    }

    // ---------- 등록 대화 ----------

    private fun tryPrompt() {
        val m = monitor ?: return
        if (stopped || busy || pending.isEmpty() || m.isMoving() || m.anyDoorOpen()) return
        busy = true
        fixes = 0
        log("등록 질문 시작: ${pending.keys.joinToString()}")
        askName(1)
    }

    private fun nameQuestion(): String {
        val first = pending.entries.first()
        val where = if (pending.size > 1) "${seatLabel(first.key)}에 " else ""
        val hint = if (first.value) " 짐만 두셨다면 짐이라고 말씀하세요." else ""
        return "${where}누가 탑승했나요?$hint"
    }

    private fun askName(attempt: Int) {
        if (pending.isEmpty()) {
            endDialog("대기 좌석 없음")
            return
        }
        ask(nameQuestion()) { answer ->
            when {
                answer.isNullOrBlank() -> decline("이름 응답 없음")
                isCancel(answer) -> decline("등록 안 함")
                isBagOnly(answer) -> commitBag()
                else -> {
                    val name = cleanName(answer)
                    if (name == null) retry(attempt) else askItems(name, attempt)
                }
            }
        }
    }

    private fun askItems(name: String, attempt: Int) {
        ask("${name}님 소지품은요?") { answer ->
            val text = answer?.trim().orEmpty()
            val body = text.replace(negPrefix, "")
            val renamed = namePrefix.containsMatchIn(body) || body != text
            val fixedName = if (renamed) cleanName(body.replace(namePrefix, "")) else null
            when {
                text.isBlank() -> retry(attempt)
                body.isBlank() -> retry(attempt)
                fixedName != null && canFix() -> {
                    log("이름 정정")
                    askItems(fixedName, attempt)
                }
                else -> {
                    val items = splitItems(text)
                    if (items.isEmpty()) retry(attempt) else confirm(name, items, attempt)
                }
            }
        }
    }

    private fun confirm(name: String, items: List<String>, attempt: Int) {
        ask("${name}님, ${items.joinToString(", ")}. 맞나요?", 6000) { answer ->
            val text = answer?.trim().orEmpty()
            val body = text.replace(negPrefix, "")
            val fixedName = if (namePrefix.containsMatchIn(body)) cleanName(body.replace(namePrefix, "")) else null
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
                fixedName != null && canFix() -> {
                    log("이름 정정")
                    confirm(fixedName, items, attempt)
                }
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

    // 문 기준 후보를 거절하거나 답이 없으면 이번 정차 동안은 문만 여닫는 경우를 다시 묻지 않는다.
    private fun decline(reason: String) {
        val first = pending.entries.firstOrNull()
        if (first != null) {
            val seat = first.key
            val viaDoor = first.value
            pending.remove(seat)
            if (viaDoor) mutedTripAt = monitor?.lastTripAt ?: -1L
        }
        endDialog(reason)
    }

    private fun commit(name: String, items: List<String>) {
        val key = store.match(name) ?: name
        val first = pending.entries.firstOrNull()
        val old = store.people().firstOrNull { it.name == key }
        val seat = first?.key ?: old?.seat
        val viaDoor = first?.value ?: old?.viaDoor ?: false
        if (first != null) pending.remove(first.key)
        store.put(key, items, seat, viaDoor)
        log("등록: 좌석=${seat ?: "미지정"}${if (viaDoor) "(문 기준)" else ""}")
    }

    private fun commitBag() {
        val first = pending.entries.firstOrNull()
        if (first == null) {
            endDialog("대기 좌석 없음")
            return
        }
        val seat = first.key
        pending.remove(seat)
        store.put("$BAG$seat", listOf("짐"), seat, true)
        log("짐 등록: $seat")
        say("짐을 등록했어요.")
        endDialog("짐 등록")
    }

    private fun endDialog(reason: String) {
        log("대화 종료: $reason")
        busy = false
        if (pending.isNotEmpty()) handler.post { tryPrompt() }
    }

    // ---------- 음성 ----------

    private fun say(text: String, onDone: () -> Unit = {}) {
        speechQueue.addLast(text to onDone)
        if (!speaking) nextSpeech()
    }

    private fun nextSpeech() {
        if (listening) return
        val next = speechQueue.removeFirstOrNull()
        if (next == null) {
            speaking = false
            return
        }
        speaking = true
        TtsSpeaker.speak(app, next.first) {
            handler.post {
                next.second()
                nextSpeech()
            }
        }
    }

    private fun ask(prompt: String, timeoutMs: Long = 8000, onAnswer: (String?) -> Unit) {
        log("질문")
        say(prompt) {
            listening = true
            listenOnce(timeoutMs) { text ->
                listening = false
                log("응답 ${text?.length ?: 0}자")
                onAnswer(text)
                nextSpeech()
            }
        }
    }

    private fun listenOnce(timeoutMs: Long, onResult: (String?) -> Unit) {
        if (stopped) {
            onResult(null)
            return
        }
        val stt = SpeechToText(app, SttMode.ON_DEVICE)
        activeStt = stt
        var done = false
        var last: String? = null
        var final: String? = null

        fun finish(reason: String) {
            if (done) return
            done = true
            log("STT 종료: $reason")
            runCatching { stt.stop() }
            runCatching { stt.release() }
            if (activeStt === stt) activeStt = null
            onResult(final ?: last)
        }

        handler.postDelayed({ finish("시간 초과") }, timeoutMs)

        val addListener = stt.javaClass.methods.firstOrNull { it.name == "addListener" && it.parameterTypes.size == 1 }
        val initialize = stt.javaClass.methods.firstOrNull { it.name == "initialize" && it.parameterTypes.size == 1 }
        if (addListener == null || initialize == null) {
            log("STT addListener 또는 initialize(listener)를 찾지 못함")
            finish("메서드 없음")
            return
        }

        val resultProxy = proxyOf(addListener.parameterTypes[0]) { name, args ->
            when (name) {
                "onUpdated" -> {
                    val text = args.getOrNull(0) as? String
                    val completed = args.getOrNull(1) as? Boolean ?: false
                    if (!text.isNullOrBlank()) last = text
                    if (completed && !text.isNullOrBlank()) {
                        final = text
                        handler.post { finish("인식 완료") }
                    }
                }
                "onEndedRecognition" -> handler.postDelayed({ finish("인식 종료") }, 700)
                "onError" -> handler.post { finish("오류 ${args.getOrNull(0)}") }
            }
        }

        val initProxy = proxyOf(initialize.parameterTypes[0]) { name, _ ->
            when (name) {
                "onSuccess" -> handler.post {
                    runCatching {
                        addListener.invoke(stt, resultProxy)
                        stt.request()
                    }.onFailure {
                        log("STT 시작 실패: ${it.message}")
                        finish("시작 실패")
                    }
                }
                "onFailure" -> handler.post { finish("초기화 실패") }
            }
        }

        runCatching { initialize.invoke(stt, initProxy) }.onFailure {
            log("STT initialize 실패: ${it.message}")
            finish("초기화 예외")
        }
    }

    // ---------- 문장 처리 ----------

    private fun isCancel(text: String): Boolean =
        Regex("^(?:아니|아냐|없어|없다|괜찮|됐어|취소|그만)").containsMatchIn(text.trim())

    private fun isBagOnly(text: String): Boolean =
        Regex("^짐(?:만|이야|이에요|입니다|요)?").containsMatchIn(text.replace(Regex("[\\s.,!?]"), ""))

    private fun isYes(text: String): Boolean =
        Regex("^(?:응|네|예|맞|그래|좋아|오케이)|^어[.!\\s]*$").containsMatchIn(text.trim())

    private fun cleanName(text: String): String? {
        val cleaned = text.trim()
            .replace(Regex("[.!?,，]"), "")
            .replace(Regex("\\s*(?:이야|야|이에요|예요|입니다|이요|요)$"), "")
            .trim()
        val name = stripParticle(cleaned) ?: return null
        return name.takeIf { it.length in 1..8 }
    }

    private fun stripParticle(head: String): String? {
        val cleaned = head.trim().replace(Regex("\\s*(?:이|가|은|는)$"), "").trim()
        return cleaned.ifEmpty { null }
    }

    private fun splitItems(text: String): List<String> {
        val cleaned = text.trim()
            .replace(Regex("^(?:소지품|물건)(?:은|는|을|이|가)?\\s*"), "")
            .replace(Regex("\\s*(?:이야|야|이에요|예요|입니다|이다)[.!?]?$"), "")
        return cleaned
            .split(Regex("(?:이랑|랑|하고)\\s+|\\s*그리고\\s*|[\\s,，]+"))
            .map { it.trim().replace(Regex("(?<=.{2})(?:이랑|랑|하고|과|와)$"), "") }
            .filter { it.isNotEmpty() }
    }

    // 물품 정정: "A 말고 B"는 교체, "A 빼고"는 삭제, "A 추가"·"A도"·"A도 있어"는 추가, 그 외에는 새로 말한 목록으로 바꾼다.
    private fun fixItems(items: List<String>, text: String): List<String> {
        Regex("^(.+?)\\s*(?:말고|아니고)\\s*(.+)$").find(text)?.let { m ->
            val out = splitItems(m.groupValues[1])
            return (items.filterNot { it in out } + splitItems(m.groupValues[2])).distinct()
        }
        Regex("^(.+?)\\s*(?:은|는)?\\s*(?:빼고|빼줘|빼|지워줘|지워)$").find(text)?.let { m ->
            val out = splitItems(m.groupValues[1])
            return items.filterNot { it in out }
        }
        val addTail = Regex("\\s*(?:추가해줘|추가해|추가|더 있어요|더 있어|도 있어요|도 있어|도)$")
        if (addTail.containsMatchIn(text)) {
            val added = splitItems(text.replace(addTail, "")).map { it.removeSuffix("도") }.filter { it.isNotEmpty() }
            return (items + added).distinct()
        }
        return splitItems(text)
    }

    private fun seatLabel(seat: String?): String = when (seat) {
        "ROW_1_RIGHT" -> "조수석"
        "ROW_2_LEFT" -> "뒷좌석 왼쪽"
        "ROW_2_CENTER" -> "뒷좌석 가운데"
        "ROW_2_RIGHT" -> "뒷좌석 오른쪽"
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
        const val PURGE_MS = 30_000L
        const val REG_TTL_MS = 6L * 60 * 60 * 1000
        const val NO_SIGNAL_TRIP_MS = 120_000L
        const val MAX_FIX = 4
    }
}
