package dev.hjun.checkagain

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject

object Kind {
    const val REGISTER = "register"
    const val ALIGHT = "alight"
    const val INFO = "info"
    const val WARN = "warn"
}

data class Event(
    val id: Long,
    val kind: String,
    val title: String,
    val text: String,
    val at: Long,
    val read: Boolean
)

val Person.isBag: Boolean get() = name.startsWith("짐@")
val Person.label: String get() = if (isBag) "짐" else name

object SeatNames {
    fun label(seat: String?): String = when (seat) {
        "ROW_1_LEFT" -> "운전석"
        "ROW_1_RIGHT" -> "조수석"
        "ROW_2_LEFT" -> "뒷좌석 왼쪽"
        "ROW_2_CENTER" -> "뒷좌석 가운데"
        "ROW_2_RIGHT" -> "뒷좌석 오른쪽"
        "REAR" -> "트렁크"
        "HOOD" -> "프렁크"
        else -> "자리 미지정"
    }
}

/** 알림 센터에 모이는 이벤트 기록. 최근 MAX건만 남긴다. */
class EventStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("events", Context.MODE_PRIVATE)

    fun all(): List<Event> = synchronized(LOCK) { read() }.sortedByDescending { it.at }

    fun unreadCount(): Int = synchronized(LOCK) { read() }.count { !it.read }

    fun add(kind: String, title: String, text: String) {
        synchronized(LOCK) {
            val list = read().toMutableList()
            val now = System.currentTimeMillis()
            val id = maxOf(now, (list.maxOfOrNull { it.id } ?: 0L) + 1)
            list.add(Event(id, kind, title, text, now, false))
            save(list.sortedBy { it.at }.takeLast(MAX))
        }
        changed()
    }

    fun markAllRead() {
        val touched = synchronized(LOCK) {
            val list = read()
            if (list.none { !it.read }) {
                false
            } else {
                save(list.map { it.copy(read = true) })
                true
            }
        }
        if (touched) changed()
    }

    fun clear() {
        synchronized(LOCK) { save(emptyList()) }
        changed()
    }

    private fun read(): List<Event> {
        val array = runCatching { JSONArray(prefs.getString("list", "[]") ?: "[]") }.getOrDefault(JSONArray())
        return (0 until array.length()).mapNotNull { index ->
            val o = array.optJSONObject(index) ?: return@mapNotNull null
            Event(
                o.optLong("id"),
                o.optString("kind", Kind.INFO),
                o.optString("title"),
                o.optString("text"),
                o.optLong("at"),
                o.optBoolean("read", false)
            )
        }
    }

    private fun save(list: List<Event>) {
        val array = JSONArray()
        list.forEach {
            array.put(
                JSONObject()
                    .put("id", it.id)
                    .put("kind", it.kind)
                    .put("title", it.title)
                    .put("text", it.text)
                    .put("at", it.at)
                    .put("read", it.read)
            )
        }
        prefs.edit().putString("list", array.toString()).apply()
    }

    companion object {
        private val LOCK = Any()
        private const val MAX = 100
        private val ui = Handler(Looper.getMainLooper())

        @Volatile
        var onChange: (() -> Unit)? = null

        private fun changed() {
            ui.post { onChange?.invoke() }
        }
    }
}

enum class Phase { IDLE, SPEAKING, LISTENING }

/** 지금 진행 중인 음성 대화와 마이크 입력 크기를 화면에 전달한다. */
object Live {

    @Volatile
    var phase = Phase.IDLE
        private set

    @Volatile
    var text = ""
        private set

    @Volatile
    var confirm = false
        private set

    // 하차 안내를 보여 주는 중이면 true. 주황색 안내 카드로 그린다.
    @Volatile
    var alight = false
        private set

    // 시작 시 미등록 탑승자를 한 번에 묻는 확인 질문이면 true. 버튼 문구가 달라진다.
    @Volatile
    var gate = false
        private set

    @Volatile
    var onState: (() -> Unit)? = null

    @Volatile
    var onLevel: ((Float) -> Unit)? = null

    private val ui = Handler(Looper.getMainLooper())

    fun set(phase: Phase, text: String = "", confirm: Boolean = false, alight: Boolean = false, gate: Boolean = false) {
        this.phase = phase
        this.text = text
        this.confirm = confirm
        this.alight = alight
        this.gate = gate
        ui.post { onState?.invoke() }
    }

    fun level(value: Float) {
        ui.post { onLevel?.invoke(value) }
    }
}
