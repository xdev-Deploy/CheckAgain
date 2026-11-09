package dev.hjun.checkagain

import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.Mode
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.lang.reflect.Proxy
import java.util.UUID

private const val TAG = "CheckAgain"

data class Person(
    val id: String,
    val name: String,
    val items: List<String>,
    val seat: String? = null,
    val viaDoor: Boolean = false,
    val registeredAt: Long = 0L
)

class PassengerStore(context: Context) {

    private val prefs = context.getSharedPreferences("passengers", Context.MODE_PRIVATE)

    fun people(): List<Person> {
        val json = runCatching { JSONObject(prefs.getString("data", "{}") ?: "{}") }.getOrDefault(JSONObject())
        val result = ArrayList<Person>()
        json.keys().forEach { key ->
            val value = json.optJSONObject(key) ?: return@forEach
            result.add(
                Person(
                    key,
                    value.optString("name").ifEmpty { key },
                    readList(value.optJSONArray("items") ?: JSONArray()),
                    value.optString("seat").ifEmpty { null },
                    value.optBoolean("viaDoor", false),
                    value.optLong("registeredAt", 0L)
                )
            )
        }
        return result.sortedBy { it.registeredAt }
    }

    // id가 있으면 그 항목을 덮어쓰고, 없으면 새 항목을 만든다. 저장된 id를 돌려준다.
    fun put(id: String?, name: String, items: List<String>, seat: String? = null, viaDoor: Boolean = false): String {
        val key = id ?: UUID.randomUUID().toString().take(8)
        val current = people().filter { it.id != key }.toMutableList()
        current.add(Person(key, name, items, seat, viaDoor, System.currentTimeMillis()))
        save(current)
        return key
    }

    fun remove(id: String) = save(people().filter { it.id != id })

    fun purge(shouldDrop: (Person) -> Boolean): List<Person> {
        val all = people()
        val dropped = all.filter(shouldDrop)
        if (dropped.isNotEmpty()) save(all.filter { p -> dropped.none { it.id == p.id } })
        return dropped
    }

    // 같은 이름이거나 한쪽이 다른 쪽을 포함하는 등록자를 찾는다. 글자 하나 차이는 다른 사람으로 둔다.
    // skip이 true인 등록자는 후보에서 뺀다. 같은 이름이어도 아직 자리에 앉아 있는 사람은 동명이인이다.
    fun findSame(name: String, skip: (Person) -> Boolean): Person? {
        val candidates = people().filterNot(skip)
        return candidates.firstOrNull { it.name == name }
            ?: candidates.firstOrNull {
                minOf(it.name.length, name.length) >= 2 && (it.name.contains(name) || name.contains(it.name))
            }
    }

    fun bind(id: String, seat: String?, viaDoor: Boolean) {
        save(people().map { if (it.id == id) it.copy(seat = seat, viaDoor = viaDoor) else it })
    }

    fun clear() = save(emptyList())

    private fun readList(array: JSONArray): List<String> = (0 until array.length()).map { array.optString(it) }

    private fun save(list: List<Person>) {
        val json = JSONObject()
        list.forEach { person ->
            json.put(
                person.id,
                JSONObject()
                    .put("name", person.name)
                    .put("items", JSONArray(person.items))
                    .put("seat", person.seat ?: "")
                    .put("viaDoor", person.viaDoor)
                    .put("registeredAt", person.registeredAt)
            )
        }
        prefs.edit().putString("data", json.toString()).apply()
    }
}

object TtsSpeaker {

    // 지금 읽고 있는 TTS. 취소 버튼이 읽던 말을 멈출 때 쓴다.
    @Volatile
    private var active: TextToSpeech? = null

    fun stopCurrent() {
        val tts = active ?: return
        runCatching { tts.javaClass.getMethod("stop").invoke(tts) }
            .onFailure { Log.w(TAG, "TTS stop 실패", it) }
    }

    fun speak(context: Context, text: String, onStarted: (() -> Unit)? = null, onFinished: () -> Unit) {
        val handler = Handler(Looper.getMainLooper())
        val tts = TextToSpeech(context.applicationContext, Mode.ON_DEVICE)
        active = tts
        var finished = false

        fun finish() {
            if (finished) return
            finished = true
            if (active === tts) active = null
            runCatching { tts.release() }
            onFinished()
        }

        // 문장 길이에 맞춰 대기 시간을 잡아 긴 안내가 중간에 끊기지 않게 한다.
        handler.postDelayed({ finish() }, 5000L + text.length * 350L)

        val addListener = tts.javaClass.methods.firstOrNull {
            it.name == "addEventListener" && it.parameterTypes.size == 1
        }
        val initialize = tts.javaClass.methods.firstOrNull {
            it.name == "initialize" && it.parameterTypes.size == 1
        }
        if (initialize == null) {
            Log.e(TAG, "initialize(listener) 메서드를 찾지 못함")
            finish()
            return
        }

        val eventProxy = addListener?.let { method ->
            newProxy(method.parameterTypes[0]) { name ->
                Log.d(TAG, "TTS.$name")
                when (name) {
                    "onStart" -> handler.post { if (!finished) onStarted?.invoke() }
                    "onDone", "onStop", "onError" -> handler.post { finish() }
                }
            }
        }
        val initProxy = newProxy(initialize.parameterTypes[0]) { name ->
            Log.d(TAG, "TTS init.$name")
            when (name) {
                "onSuccess" -> handler.post {
                    runCatching {
                        if (addListener != null && eventProxy != null) addListener.invoke(tts, eventProxy)
                        tts.speak(text)
                    }.onFailure {
                        Log.e(TAG, "speak 실패", it)
                        finish()
                    }
                }
                "onFailure" -> handler.post { finish() }
            }
        }

        runCatching { initialize.invoke(tts, initProxy) }.onFailure {
            Log.e(TAG, "initialize 실패", it)
            finish()
        }
    }

    private fun newProxy(type: Class<*>, onCall: (String) -> Unit): Any =
        Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { self, call, args ->
            when {
                call.declaringClass == Any::class.java -> when (call.name) {
                    "equals" -> self === args?.firstOrNull()
                    "hashCode" -> System.identityHashCode(self)
                    else -> "TtsProxy"
                }
                else -> {
                    onCall(call.name)
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
