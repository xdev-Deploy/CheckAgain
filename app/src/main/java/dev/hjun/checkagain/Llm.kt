package dev.hjun.checkagain

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** 화면의 오프라인 모드 버튼이 바꾸는 값. 꺼 두면 LLM을 부르지 않고 기기 안의 규칙만 쓴다. */
object LlmSettings {

    private const val PREFS = "llm"
    private const val KEY_ENABLED = "enabled"

    fun enabled(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, value: Boolean) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, value).apply()
    }

    // 키가 비어 있으면 호출하지 않는다.
    val available: Boolean get() = BuildConfig.OPENAI_API_KEY.isNotBlank()
}

data class LlmResult(val intent: String, val items: List<String>)

/**
 * 소지품 질문과 확인 질문의 답을 분류한다. 이름은 기기에서 지운 뒤 짧은 텍스트만 HTTPS로 보낸다.
 * 7초 안에 끝나지 않거나 실패하면 null을 돌려주고, 부르는 쪽이 기기 안의 규칙으로 이어 간다.
 */
object LlmClient {

    const val STAGE_ITEMS = "items"
    const val STAGE_CONFIRM = "confirm"
    const val TIMEOUT_MS = 7_000L

    private const val ENDPOINT = "https://api.openai.com/v1/chat/completions"
    private const val MAX_ITEMS = 10
    private const val MAX_ITEM_CHARS = 20

    private val worker = Executors.newCachedThreadPool()
    private val main = Handler(Looper.getMainLooper())

    // onResult는 항상 메인 스레드에서 한 번만 불린다.
    fun classify(
        stage: String,
        text: String,
        items: List<String>,
        log: (String) -> Unit,
        onResult: (LlmResult?) -> Unit
    ) {
        val done = AtomicBoolean(false)
        val started = System.currentTimeMillis()
        var timeoutTask: Runnable? = null

        fun deliver(result: LlmResult?, reason: String) {
            if (!done.compareAndSet(false, true)) return
            timeoutTask?.let { main.removeCallbacks(it) }
            log("LLM $stage: $reason ${System.currentTimeMillis() - started}ms")
            onResult(result)
        }

        val task = Runnable { deliver(null, "시간 초과") }
        timeoutTask = task
        main.postDelayed(task, TIMEOUT_MS)

        worker.execute {
            val outcome = runCatching { request(stage, text, items) }
            main.post {
                outcome.fold(
                    onSuccess = { deliver(it, it?.intent ?: "응답 해석 실패") },
                    onFailure = { deliver(null, "실패 ${it.javaClass.simpleName} ${it.message.orEmpty().take(40)}") }
                )
            }
        }
    }

    private fun request(stage: String, text: String, items: List<String>): LlmResult? {
        val body = JSONObject()
            .put("model", BuildConfig.OPENAI_MODEL)
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", systemPrompt(stage)))
                    .put(JSONObject().put("role", "user").put("content", userMessage(stage, text, items)))
            )
            .put(
                "response_format",
                JSONObject()
                    .put("type", "json_schema")
                    .put(
                        "json_schema",
                        JSONObject().put("name", "reply").put("strict", true).put("schema", schema(stage))
                    )
            )

        val conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 5_000
            readTimeout = TIMEOUT_MS.toInt()
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Authorization", "Bearer ${BuildConfig.OPENAI_API_KEY}")
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            val raw = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val content = JSONObject(raw)
                .getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content")
            return parse(stage, JSONObject(content))
        } finally {
            conn.disconnect()
        }
    }

    private fun parse(stage: String, json: JSONObject): LlmResult? {
        val intent = json.optString("intent")
        if (intent !in intents(stage)) return null
        val array = json.optJSONArray("items") ?: JSONArray()
        val items = (0 until array.length())
            .map { array.optString(it).trim() }
            .filter { it.isNotEmpty() && it.length <= MAX_ITEM_CHARS }
            .distinct()
            .take(MAX_ITEMS)
        // 목록이 필요한 분류인데 비어 있으면 판단하지 못한 것으로 본다.
        if ((intent == "items" || intent == "correct") && items.isEmpty()) return LlmResult("unclear", emptyList())
        return LlmResult(intent, if (intent == "items" || intent == "correct") items else emptyList())
    }

    private fun intents(stage: String): List<String> =
        if (stage == STAGE_ITEMS) listOf("items", "repeat", "unclear")
        else listOf("yes", "no", "correct", "repeat", "unclear")

    private fun schema(stage: String): JSONObject = JSONObject()
        .put("type", "object")
        .put(
            "properties",
            JSONObject()
                .put("intent", JSONObject().put("type", "string").put("enum", JSONArray(intents(stage))))
                .put("items", JSONObject().put("type", "array").put("items", JSONObject().put("type", "string")))
        )
        .put("required", JSONArray(listOf("intent", "items")))
        .put("additionalProperties", false)

    private fun systemPrompt(stage: String): String =
        if (stage == STAGE_ITEMS) {
            "차량 승객의 소지품을 등록하는 음성 앱입니다. 앱이 '소지품은요?'라고 물었고, 사용자의 음성 인식 결과가 주어집니다. " +
                "다음 중 하나로 분류하세요.\n" +
                "- items: 소지품을 말한 경우. items에 물건 이름만 하나씩 담습니다. 조사와 서술어는 빼고 사용자가 말한 낱말을 최대한 그대로 씁니다. 말하지 않은 물건은 만들지 않습니다.\n" +
                "- repeat: 질문을 못 들었거나 다시 말해 달라는 경우. 예: 뭐?, 못 들었어, 뭐라고, 뭐래는 거야, 다시 말해줘.\n" +
                "- unclear: 위 둘 어디에도 해당하지 않거나 알아들을 수 없는 경우.\n" +
                "items가 필요 없는 분류에서는 items를 빈 배열로 둡니다."
        } else {
            "차량 승객의 소지품을 등록하는 음성 앱입니다. 앱이 현재 소지품 목록을 읽어 주고 '맞나요?'라고 물었고, 사용자의 음성 인식 결과가 주어집니다. " +
                "다음 중 하나로 분류하세요.\n" +
                "- yes: 목록이 맞다고 확인한 경우.\n" +
                "- no: 아니라고만 하고 새로 알려 주는 내용이 없는 경우.\n" +
                "- correct: 목록을 고치는 말(빼기, 추가, 교체, 새 목록). items에 고친 뒤의 전체 목록을 담습니다. 말한 부분만 바꾸고 나머지는 유지합니다.\n" +
                "- repeat: 질문을 못 들었거나 다시 말해 달라는 경우. 예: 뭐?, 못 들었어, 뭐라고, 뭐래는 거야, 다시 말해줘.\n" +
                "- unclear: 위 어디에도 해당하지 않거나 알아들을 수 없는 경우.\n" +
                "items가 필요 없는 분류에서는 items를 빈 배열로 둡니다."
        }

    private fun userMessage(stage: String, text: String, items: List<String>): String =
        if (stage == STAGE_ITEMS) "사용자 응답: $text"
        else "현재 목록: ${items.joinToString(", ")}\n사용자 응답: $text"
}
