package dev.hjun.checkagain

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.listener.DoorPositionListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.lang.reflect.Method
import java.lang.reflect.Proxy

class VehicleMonitor(
    context: Context,
    private val log: (String) -> Unit,
    private val onDoor: (DoorValue, Boolean) -> Unit,
    private val onSeat: (String, Boolean) -> Unit,
    private val onSeatInitial: (String) -> Unit = {}
) {

    private class Signal(
        val name: String,
        val domain: Any,
        val read: Method?,
        val onValue: (Any?) -> Unit
    ) {
        var listener: Any? = null
    }

    private val app = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val doorAreas = DoorValue.entries.filter { it.name.startsWith("ROW_") || it == DoorValue.REAR || it == DoorValue.HOOD }
    private val doorListeners = mutableMapOf<DoorValue, DoorPositionListener>()
    private val doorState = mutableMapOf<DoorValue, Boolean>()
    private val doorUnsupported = mutableSetOf<DoorValue>()
    private val seatAreas = LinkedHashMap<String, Any>()
    private val seatListeners = mutableMapOf<String, Any>()
    private val seatState = mutableMapOf<String, Boolean>()
    private val seatRaw = mutableMapOf<String, Boolean>()
    private val seatTimers = mutableMapOf<String, Runnable>()
    private val seatForced = mutableMapOf<String, Boolean>()
    private val seatChanged = mutableSetOf<String>()
    private val doorForced = mutableMapOf<DoorValue, Boolean>()

    private var vehicle: Vehicle? = null
    private var seatDomain: Any? = null
    private var gear: Signal? = null
    private var brake: Signal? = null
    private val odometers = mutableListOf<Signal>()

    private var running = false
    private var generation = 0
    private var failStreak = 0
    private var retryDelay = RETRY_MIN_MS

    private var gearRaw: String? = null
    private var gearClass: Char? = null
    private var brakePressed: Boolean? = null
    private val odoLast = mutableMapOf<String, Double>()
    private var lastOdoIncrease = 0L
    @Volatile
    private var rollForced = false
    private var moving = false

    @Volatile
    var lastTripAt = 0L
        private set

    fun isMoving(): Boolean = moving

    fun isRolling(): Boolean =
        rollForced || (lastOdoIncrease != 0L && SystemClock.elapsedRealtime() - lastOdoIncrease < ODO_WINDOW_MS)

    fun injectRolling(on: Boolean) {
        rollForced = on
        log("주행 모사 ${if (on) "켬" else "끔"}")
        recompute()
    }

    fun hasDriveSignal(): Boolean = gearClass != null || brakePressed != null || odoLast.isNotEmpty()

    fun hasSeatSensors(): Boolean = seatState.isNotEmpty()

    // 좌석 값이 바뀌었지만 안정화 확인이 끝나지 않은 좌석이 있는지
    fun seatSettling(): Boolean = seatRaw.any { (name, raw) -> seatState[name] != raw }

    fun seats(): Map<String, Boolean> = seatState.toMap()

    // 화면용 좌석 상태. 시작 시 점유된 자리도 센서 값 그대로 앉은 자리로 보여 준다(seatChanged에 등록됨).
    fun seatsForDisplay(): Map<String, Boolean> =
        seatState.mapValues { (name, occupied) -> occupied && (name == DRIVER || name in seatChanged) }

    fun anyDoorOpen(): Boolean = doorState.values.any { it }

    fun injectSeat(name: String, occupied: Boolean) {
        seatForced[name] = occupied
        log("좌석 주입: $name -> ${if (occupied) "점유" else "비움"}")
        applySeat(name, occupied)
    }

    fun injectDoor(name: String, open: Boolean) {
        val area = doorAreas.firstOrNull { it.name == name } ?: return
        doorForced[area] = open
        log("문 주입: $name -> ${if (open) "열림" else "닫힘"}")
        updateDoor(area, if (open) 1 else 0, "주입")
    }

    fun clearInjected() {
        val names = seatForced.keys.toList()
        seatForced.clear()
        doorForced.clear()
        log("주입 해제")
        val seat = seatDomain ?: return
        names.forEach { name -> seatAreas[name]?.let { readSeat(seat, name, it) } }
    }

    fun start() {
        if (running) return
        running = true
        connect()
    }

    fun stop() {
        running = false
        generation++
        handler.removeCallbacksAndMessages(null)
        teardown()
        doorState.clear()
        seatState.clear()
        seatRaw.clear()
        seatTimers.clear()
        seatForced.clear()
        seatChanged.clear()
        doorForced.clear()
        log("VehicleMonitor 종료")
    }

    private fun connect() {
        if (!running) return
        val gen = ++generation
        teardown()
        val v = runCatching { Vehicle(app).also { it.initialize() } }.getOrElse {
            log("Vehicle 초기화 실패: ${it.message}")
            scheduleReconnect(gen)
            return
        }
        vehicle = v
        probeReady(v, gen)
    }

    private fun probeReady(v: Vehicle, gen: Int) {
        runCatching {
            v.getDoor().checkDoorPositionCapabilities(
                onComplete = { _, _ ->
                    handler.post {
                        if (gen != generation || !running) return@post
                        retryDelay = RETRY_MIN_MS
                        failStreak = 0
                        subscribeDoors(v, gen)
                        bindExtras(v, gen)
                        poll(gen)
                    }
                },
                onFailed = {
                    handler.post {
                        log("Door 준비 확인 실패: ${it.message}")
                        scheduleReconnect(gen)
                    }
                }
            )
        }.onFailure {
            log("Door 준비 확인 예외: ${it.message}")
            scheduleReconnect(gen)
        }
    }

    private fun subscribeDoors(v: Vehicle, gen: Int) {
        val door = v.getDoor()
        doorAreas.filter { it !in doorUnsupported }.forEach { value ->
            val listener = object : DoorPositionListener {
                override fun onPositionUpdated(pos: Int?) {
                    handler.post { if (gen == generation) updateDoor(value, pos, "리스너") }
                }

                override fun onFailed(e: Exception) {
                    handler.post {
                        if (gen != generation) return@post
                        if (e.message.orEmpty().contains("not supported")) {
                            doorUnsupported.add(value)
                            log("Door 미지원 영역 제외: ${value.name}")
                        } else {
                            log("Door 리스너 오류 ${value.name}: ${e.message}")
                        }
                    }
                }
            }
            runCatching {
                door.registerDoorPosition(DoorArea(value), listener)
                doorListeners[value] = listener
            }.onFailure { log("Door 구독 실패 ${value.name}: ${it.message}") }
        }
        log("Door 구독 ${doorListeners.size}개, 폴링 ${POLL_MS}ms")
    }

    private fun bindExtras(v: Vehicle, gen: Int) {
        seatDomain = runCatching {
            v.javaClass.methods.firstOrNull { it.name == "getSeat" && it.parameterTypes.isEmpty() }?.invoke(v)
        }.getOrNull()
        bindSeats(gen)
        gear = bind(v, "getGear", "GearSelection") { value -> handler.post { onGear(value) } }
        brake = bind(v, "getBrake", "BrakePedalStatus") { value -> handler.post { onBrake(value) } }
        odometers.clear()
        listOf("DistanceDriven", "TripCurrentDrivingDistance", "TripInstantaneousDrivingDistance").forEach { name ->
            bind(v, "getOdometer", name) { value -> handler.post { onOdometer(name, value) } }
                ?.let { odometers.add(it) }
        }
    }

    private fun bind(v: Vehicle, domainGetter: String, signal: String, onValue: (Any?) -> Unit): Signal? {
        val domain = runCatching {
            v.javaClass.methods.firstOrNull { it.name == domainGetter && it.parameterTypes.isEmpty() }?.invoke(v)
        }.getOrNull()
        if (domain == null) {
            log("신호 도메인 없음: $domainGetter")
            return null
        }
        val methods = domain.javaClass.methods
        val read = methods.firstOrNull {
            it.name == "getCurrent$signal" && it.parameterTypes.size == 2 && it.parameterTypes[0] == Function1::class.java
        }
        val register = methods.firstOrNull { it.name == "register$signal" && it.parameterTypes.size == 1 }
        log("신호 $signal: 읽기=${read != null} 구독=${register != null}")
        if (read == null && register == null) return null
        val sig = Signal(signal, domain, read, onValue)
        if (register != null) {
            val listener = proxyListener(register.parameterTypes[0], onValue)
            runCatching {
                register.invoke(domain, listener)
                sig.listener = listener
            }.onFailure { log("신호 구독 실패 $signal: ${it.cause?.message ?: it.message}") }
        }
        readSignal(sig)
        return sig
    }

    private fun readSignal(sig: Signal) {
        val read = sig.read ?: return
        val onValue: (Any?) -> Unit = { value -> sig.onValue(value) }
        val onFail: (Any?) -> Unit = { }
        runCatching { read.invoke(sig.domain, onValue, onFail) }
    }

    private fun bindSeats(gen: Int) {
        val seat = seatDomain ?: return
        val check = seat.javaClass.methods.firstOrNull {
            it.name == "checkSeatOccupancyCapabilities" &&
                    it.parameterTypes.size == 2 &&
                    it.parameterTypes[0] == Function1::class.java
        }
        if (check == null) {
            log("좌석 점유 API 없음")
            return
        }
        val onAreas: (Any?) -> Unit = { areas ->
            handler.post {
                if (gen == generation) registerSeatAreas(seat, (areas as? List<*>).orEmpty().filterNotNull())
            }
        }
        val onFail: (Any?) -> Unit = { e ->
            handler.post { log("좌석 점유 capability 실패: ${(e as? Exception)?.message}") }
        }
        runCatching { check.invoke(seat, onAreas, onFail) }
            .onFailure { log("좌석 점유 확인 예외: ${it.cause?.message ?: it.message}") }
    }

    private fun registerSeatAreas(seat: Any, areas: List<Any>) {
        val register = seat.javaClass.methods.firstOrNull {
            it.name == "registerSeatOccupancy" && it.parameterTypes.size == 2
        }
        areas.forEach { area ->
            val name = areaName(area)
            if (seatAreas.containsKey(name)) return@forEach
            seatAreas[name] = area
            if (register != null) {
                val listener = proxyListener(register.parameterTypes[1]) { value ->
                    handler.post { updateSeat(name, value as? Boolean) }
                }
                runCatching {
                    register.invoke(seat, area, listener)
                    seatListeners[name] = listener
                }.onFailure { log("좌석 구독 실패 $name: ${it.cause?.message ?: it.message}") }
            }
            readSeat(seat, name, area)
        }
        log("좌석 점유 영역: ${seatAreas.keys.joinToString()}")
    }

    private fun readSeat(seat: Any, name: String, area: Any) {
        val read = seat.javaClass.methods.firstOrNull {
            it.name == "getCurrentSeatOccupancy" && it.parameterTypes.size == 3
        } ?: return
        val onValue: (Any?) -> Unit = { value -> handler.post { updateSeat(name, value as? Boolean) } }
        val onFail: (Any?) -> Unit = { }
        runCatching { read.invoke(seat, area, onValue, onFail) }
    }

    private fun areaName(area: Any): String = runCatching {
        val raw = if (area is Array<*>) area else area.javaClass.getMethod("getValues").invoke(area)
        when (raw) {
            is Array<*> -> raw.joinToString("+")
            is Iterable<*> -> raw.joinToString("+")
            else -> raw.toString()
        }
    }.getOrElse { area.toString() }

    private fun proxyListener(type: Class<*>, onValue: (Any?) -> Unit): Any =
        Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { self, call, args ->
            when {
                call.declaringClass == Any::class.java -> when (call.name) {
                    "equals" -> self === args?.firstOrNull()
                    "hashCode" -> System.identityHashCode(self)
                    else -> "SignalProxy"
                }
                call.name == "onFailed" -> {
                    log("리스너 오류: ${args?.firstOrNull()}")
                    null
                }
                else -> {
                    onValue(args?.firstOrNull())
                    null
                }
            }
        }

    private fun poll(gen: Int) {
        if (gen != generation || !running) return
        val door = vehicle?.getDoor()
        if (door == null) {
            scheduleReconnect(gen)
            return
        }
        seatDomain?.let { seat -> seatAreas.forEach { (name, area) -> readSeat(seat, name, area) } }
        (listOfNotNull(gear, brake) + odometers).forEach { readSignal(it) }
        recompute()

        val targets = doorAreas.filter { it !in doorUnsupported }
        var pending = targets.size
        var failed = 0

        fun done() {
            if (gen != generation || !running) return
            pending--
            if (pending > 0) return
            if (failed == targets.size && targets.isNotEmpty()) failStreak++ else failStreak = 0
            if (failStreak >= FAIL_LIMIT) {
                log("Door 폴링 연속 실패 ${failStreak}회, 재연결")
                scheduleReconnect(gen)
            } else {
                handler.postDelayed({ poll(gen) }, POLL_MS)
            }
        }

        if (targets.isEmpty()) {
            handler.postDelayed({ poll(gen) }, POLL_MS)
            return
        }
        targets.forEach { value ->
            runCatching {
                door.getCurrentDoorPosition(
                    area = DoorArea(value),
                    onComplete = { pos ->
                        handler.post {
                            updateDoor(value, pos, "폴링")
                            done()
                        }
                    },
                    onFailed = { e ->
                        handler.post {
                            if (e.message.orEmpty().contains("not supported")) doorUnsupported.add(value) else failed++
                            done()
                        }
                    }
                )
            }.onFailure {
                failed++
                done()
            }
        }
    }

    private val doorRaw = mutableMapOf<DoorValue, Int?>()

    private fun updateDoor(area: DoorValue, pos: Int?, source: String) {
        if (source != "주입" && doorForced.containsKey(area) ) return
        // 콘솔의 문 조작과 차량 그림의 문 버튼이 서로 다른 값을 보내는지 확인할 수 있게 원본 값이 바뀔 때 남긴다.
        if (!doorRaw.containsKey(area) || doorRaw[area] != pos) {
            doorRaw[area] = pos
            log("Door 원본값 ${area.name}=$pos ($source)")
        }
        val open = (pos ?: 0) > 0
        val before = doorState.put(area, open)
        if (before == null || before == open) return
        if (source == "폴링") log("리스너가 놓친 변화를 폴링으로 감지: ${area.name} -> ${if (open) "열림" else "닫힘"}")
        onDoor(area, open)
    }

    private fun updateSeat(name: String, occupied: Boolean?) {
        if (occupied == null || seatForced.containsKey(name)) return
        applySeat(name, occupied)
    }

    private fun applySeat(name: String, occupied: Boolean) {
        if (seatState[name] == null) {
            seatState[name] = occupied
            seatRaw[name] = occupied
            log("좌석 초기값 $name ${if (occupied) "점유" else "비움"}")
            // 시작할 때 이미 앉아 있는 동승자 좌석은 센서 값 그대로 탑승으로 보고 등록을 묻는다.
            if (occupied && name != DRIVER) {
                seatChanged.add(name)
                onSeatInitial(name)
            }
            return
        }
        if (seatRaw[name] == occupied) return
        seatRaw[name] = occupied
        seatTimers.remove(name)?.let { handler.removeCallbacks(it) }
        if (seatState[name] == occupied) return
        val task = Runnable {
            seatTimers.remove(name)
            if (seatRaw[name] == occupied && seatState[name] != occupied) {
                seatState[name] = occupied
                seatChanged.add(name)
                onSeat(name, occupied)
            }
        }
        seatTimers[name] = task
        handler.postDelayed(task, SEAT_STABLE_MS)
    }

    private fun onGear(value: Any?) {
        val raw = value?.toString()
        val c = classifyGear(raw)
        if (raw != gearRaw) log("기어 값 $raw -> ${c ?: '?'}")
        gearRaw = raw
        gearClass = c
        recompute()
    }

    private fun onBrake(value: Any?) {
        val pressed = when (value) {
            null -> null
            is Boolean -> value
            is Number -> value.toInt() != 0
            else -> value.toString().uppercase().let { it.contains("PRESS") || it == "ON" || it == "TRUE" }
        }
        if (pressed != brakePressed) log("브레이크 $value -> $pressed")
        brakePressed = pressed
        recompute()
    }

    private fun onOdometer(name: String, value: Any?) {
        val d = (value as? Number)?.toDouble() ?: return
        val before = odoLast[name]
        if (before != null && d > before + 0.0001) {
            val now = SystemClock.elapsedRealtime()
            if (lastOdoIncrease == 0L || now - lastOdoIncrease > ODO_WINDOW_MS) log("주행거리 증가 감지: $name $before -> $d")
            lastOdoIncrease = now
        }
        odoLast[name] = d
        recompute()
    }

    private fun classifyGear(raw: String?): Char? {
        val s = raw?.uppercase() ?: return null
        return when {
            Regex("PARK|(^|_)P$").containsMatchIn(s) -> 'P'
            Regex("REVERSE|(^|_)R$").containsMatchIn(s) -> 'R'
            Regex("NEUTRAL|(^|_)N$").containsMatchIn(s) -> 'N'
            Regex("DRIVE|(^|_)D$").containsMatchIn(s) -> 'D'
            else -> null
        }
    }

    private fun recompute() {
        val now = SystemClock.elapsedRealtime()
        val odoMoving = lastOdoIncrease != 0L && now - lastOdoIncrease < ODO_WINDOW_MS
        val inGear = gearClass == 'D' || gearClass == 'R'
        val next = rollForced || odoMoving || (inGear && brakePressed != true)
        if (next && !moving) {
            lastTripAt = System.currentTimeMillis()
            log("주행 시작 감지")
        }
        if (!next && moving) log("정차 감지")
        moving = next
    }

    private fun scheduleReconnect(gen: Int) {
        if (gen != generation || !running) return
        val delay = retryDelay
        retryDelay = (retryDelay * 2).coerceAtMost(RETRY_MAX_MS)
        log("Vehicle 재연결 ${delay}ms 뒤")
        handler.postDelayed({ if (gen == generation) connect() }, delay)
    }

    private fun teardown() {
        val door = runCatching { vehicle?.getDoor() }.getOrNull()
        doorListeners.forEach { (value, listener) ->
            runCatching { door?.unregisterDoorPosition(DoorArea(value), listener) }
        }
        doorListeners.clear()

        seatDomain?.let { seat ->
            val unregister = seat.javaClass.methods.firstOrNull {
                it.name == "unregisterSeatOccupancy" && it.parameterTypes.size == 2
            }
            seatListeners.forEach { (name, listener) ->
                val area = seatAreas[name] ?: return@forEach
                runCatching { unregister?.invoke(seat, area, listener) }
            }
        }
        seatListeners.clear()
        seatAreas.clear()
        seatDomain = null
        seatTimers.values.forEach { handler.removeCallbacks(it) }
        seatTimers.clear()
        seatRaw.clear()
        seatRaw.putAll(seatState)

        (listOfNotNull(gear, brake) + odometers).forEach { sig ->
            val listener = sig.listener ?: return@forEach
            val unregister = sig.domain.javaClass.methods.firstOrNull {
                it.name == "unregister${sig.name}" && it.parameterTypes.size == 1
            }
            runCatching { unregister?.invoke(sig.domain, listener) }
        }
        gear = null
        brake = null
        odometers.clear()

        runCatching { vehicle?.release() }
        vehicle = null
    }

    private companion object {
        const val POLL_MS = 1500L
        const val FAIL_LIMIT = 4
        const val RETRY_MIN_MS = 1000L
        const val RETRY_MAX_MS = 15000L
        const val ODO_WINDOW_MS = 2500L
        const val SEAT_STABLE_MS = 1000L
        const val DRIVER = "ROW_1_LEFT"
    }
}
