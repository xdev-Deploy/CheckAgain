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

private const val TAG = "CheckAgain"

data class Person(
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
                    readList(value.optJSONArray("items") ?: JSONArray()),
                    value.optString("seat").ifEmpty { null },
                    value.optBoolean("viaDoor", false),
                    value.optLong("registeredAt", 0L)
                )
            )
        }
        return result
    }

    fun put(name: String, items: List<String>, seat: String? = null, viaDoor: Boolean = false) {
        val current = people().filter { it.name != name }.toMutableList()
        current.add(Person(name, items, seat, viaDoor, System.currentTimeMillis()))
        save(current)
    }

    fun remove(name: String) = save(people().filter { it.name != name })

    fun purge(shouldDrop: (Person) -> Boolean): List<String> {
        val all = people()
        val dropped = all.filter(shouldDrop).map { it.name }
        if (dropped.isNotEmpty()) save(all.filter { it.name !in dropped })
        return dropped
    }

    // 같은 이름이거나 한쪽이 다른 쪽을 포함할 때만 같은 사람으로 본다. 글자 하나 차이는 다른 사람으로 둔다.
    fun match(name: String): String? {
        val names = people().map { it.name }
        names.firstOrNull { it == name }?.let { return it }
        return names.firstOrNull { minOf(it.length, name.length) >= 2 && (it.contains(name) || name.contains(it)) }
    }

    fun bind(name: String, seat: String?, viaDoor: Boolean) {
        save(people().map { if (it.name == name) it.copy(seat = seat, viaDoor = viaDoor) else it })
    }

    fun clear() = save(emptyList())

    private fun readList(array: JSONArray): List<String> = (0 until array.length()).map { array.optString(it) }

    private fun save(list: List<Person>) {
        val json = JSONObject()
        list.forEach { person ->
            json.put(
                person.name,
                JSONObject()
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

    fun speak(context: Context, text: String, onFinished: () -> Unit) {
        val handler = Handler(Looper.getMainLooper())
        val tts = TextToSpeech(context.applicationContext, Mode.ON_DEVICE)
        var finished = false

        fun finish() {
            if (finished) return
            finished = true
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
                if (name == "onDone" || name == "onStop" || name == "onError") handler.post { finish() }
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
