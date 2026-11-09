package dev.hjun.checkagain

sealed interface NameAnswer {
    object Cancel : NameAnswer
    object Bag : NameAnswer
    object Unclear : NameAnswer
    data class Name(val value: String) : NameAnswer
}

/**
 * 음성 인식 결과를 해석하는 순수 함수 모음.
 * 안드로이드에 의존하지 않아 JVM 단위 테스트로 검증할 수 있다.
 */
object TextParser {

    private const val REMOVE_TAIL =
        "(?:빼고|빼줘요?|빼\\s*주세요|빼요|빼|지우고|지워줘요?|지워\\s*주세요|지워요|지워|삭제해?\\s*줘요?|삭제|없애\\s*줘요?|없애\\s*주세요|없애|아니야|아니에요|아냐)"
    private const val ADD_TAIL =
        "(?:추가해?\\s*줘요?|추가해\\s*주세요|추가해요|추가해|추가|더\\s*있어요?|넣어\\s*줘요?|넣어\\s*주세요|넣어요|넣어|있어요?)"

    private val punctuation = Regex("[\\s.,!?]")
    private val negPrefix = Regex("^(?:아니고|아니야|아니요|아니|아냐|틀렸어|틀려|다시)[,.\\s]*")
    private val namePrefix = Regex("^이름(?:이|은|는)?\\s*")

    private val cancelWord =
        Regex("^(?:아니(?:요|야|에요)?|아냐|없어요?|없다|없습니다|괜찮(?:아요|아|습니다)?|됐어요?|취소(?:해줘|해|할게요|할게)?|그만(?:해요|해|할게요|할게)?)+$")
    private val cancelPhrase = Regex("^(?:등록(?:은)?(?:안|하지않|하지말|취소)|아무도(?:없|안))")
    private val bagWord = Regex("^짐(?:만(?:있(?:어요|어|습니다))?|뿐(?:이야|이에요|입니다)?|이야|이에요|입니다|이요|이다|요)?$")
    private val bagPhrase = Regex("짐(?:만|뿐)|^짐(?:을|은|이)?(?:넣|올렸|실었|뒀|두었|두고)")

    // "네 근데 모자도"처럼 정정이 이어지는 말은 긍정으로 보지 않는다. 짧은 긍정 표현 전체가 일치할 때만 긍정이다.
    private val yesWord =
        Regex("^(?:응|네|넵|넹|예|어|그래요?|그렇죠|그렇습니다|맞아요?|맞습니다|맞네요?|맞다|좋아요?|오케이|그렇게해줘요?|그대로해줘요?|등록해줘요?|등록해요?)+$")

    // 정정 앞에 붙는 긍정 표현("네 근데 모자도")은 떼고 정정 부분만 본다. 단어 안의 글자("어댑터")는 떼지 않는다.
    private val leadYes =
        Regex("^(?:(?:응|네|넵|넹|예|어|맞아요?|맞는데|그래요?)(?:[,.]\\s*|\\s+))+(?:(?:근데|그런데|그리고|다만|하지만)(?:[,.]\\s*|\\s+))?")

    private val namePhrase = Regex("^(?:제\\s*)?이름(?:이|은|는)?\\s*(\\S+)$")
    private val ridePhrase = Regex("^(\\S{2,10})\\s*(?:탔어요|탔습니다|탔어|탑승했어요|탑승했습니다|탑승했어)$")

    private val replaceRe = Regex("^(.+?)\\s*(?:말고|아니고)\\s*(.+)$")
    private val removeRe = Regex("^(.+?)\\s*(?:은|는|을|를)?\\s*$REMOVE_TAIL$")
    private val addRe = Regex("^(.+?)\\s*(?:도|을|를)?\\s*$ADD_TAIL$")
    private val addDoRe = Regex("^(.+?)도$")
    private val calledAs = Regex("^(.+?)\\s*(?:이)?라고\\s*(?:해요?|불러\\s*줘요?|합니다)$")
    private val bothRe = Regex(
        "^(?:이름(?:이|은|는)?\\s*)?(\\S{1,8}?)\\s*(?:이야|이에요|예요|입니다|이고|야)?[,\\s]+(?:소지품|물건)(?:은|는|이)?\\s*(.+)$"
    )

    fun stripNegation(text: String): String = text.replace(negPrefix, "")

    fun stripLeadYes(text: String): String = text.replace(leadYes, "")

    private fun flat(text: String): String = text.replace(punctuation, "")

    // 짧은 답 전체가 일치할 때만 취소로 본다. 앞글자만 보면 이름이 잘못 분류된다.
    fun isCancel(text: String): Boolean = cancelWord.matches(flat(text))

    fun isBagOnly(text: String): Boolean = bagWord.matches(flat(text))

    fun isYes(text: String): Boolean = yesWord.matches(flat(text))

    // "뭐?", "못 들었어"처럼 질문을 다시 들려 달라는 말. LLM을 쓸 수 없을 때 소지품이나 정정으로 잘못 받지 않도록 먼저 거른다.
    private val repeatWhole = Regex("^뭐(?:라고|라는거야|라는데|래|래는거야|라그랬어|라고했어|라고하셨어|야)?요?$")
    private val repeatPhrase = Regex("못들었|안들려|안들렸|잘안들|다시말해|다시한번|한번더|무슨말|뭐라고했|뭐라그랬")

    fun isRepeatRequest(text: String): Boolean {
        val f = flat(text)
        return f.isNotEmpty() && (repeatWhole.matches(f) || repeatPhrase.containsMatchIn(f))
    }

    // "누가 탑승했나요?"에 대한 답을 취소, 짐, 이름, 해석 불가 중 하나로 가른다.
    fun classifyName(text: String): NameAnswer {
        val t = text.trim()
        if (t.isEmpty()) return NameAnswer.Unclear
        val f = flat(t)
        if (isCancel(t) || cancelPhrase.containsMatchIn(f)) return NameAnswer.Cancel
        if (isBagOnly(t) || bagPhrase.containsMatchIn(f)) return NameAnswer.Bag
        return extractName(t)?.let { NameAnswer.Name(it) } ?: NameAnswer.Unclear
    }

    // 이름만 말한 경우와 "이름은 ○○예요", "○○가 탔어요" 꼴만 이름으로 받는다. 그 밖의 긴 문장은 이름으로 보지 않는다.
    fun extractName(text: String): String? {
        val t = text.trim().trimEnd('.', '!', '?', ',', '，')
        namePhrase.find(t)?.let { return cleanName(it.groupValues[1]) }
        ridePhrase.find(t)?.let { return cleanName(it.groupValues[1]) }
        return cleanName(t)?.takeIf { name -> name.none { it.isWhitespace() } }
    }

    fun cleanName(text: String): String? {
        val base = text.trim().replace(Regex("[.!?,，]"), "")
        // 서술어("~이야", "~예요")는 앞에 두 글자 이상 남을 때만 뗀다.
        val withoutEnding = base.replace(Regex("\\s*(?:이야|야|이에요|예요|입니다|이요|요)$"), "").trim()
        val cleaned = if (withoutEnding.length >= 2) withoutEnding else base
        val name = stripParticle(cleaned) ?: return null
        return name.takeIf { it.length in 1..8 }
    }

    // 조사는 떼고 남는 글자가 충분할 때만 뗀다. "하은", "지은", "김하은"처럼 은/는/이/가로 끝나는 이름을 지키는 기준이다.
    private fun stripParticle(head: String): String? {
        val text = head.trim()
        if (text.isEmpty()) return null
        val minRest = when (text.last()) {
            '이', '가' -> 2
            '은', '는' -> 3
            else -> return text
        }
        val rest = text.dropLast(1).trim()
        return if (rest.length >= minRest) rest else text
    }

    fun splitItems(text: String): List<String> {
        val cleaned = text.trim()
            .replace(Regex("^(?:소지품|물건)(?:은|는|을|이|가)?\\s*"), "")
            .replace(Regex("\\s*(?:이야|야|이에요|예요|입니다|이다)[.!?]?$"), "")
        return cleaned
            .split(Regex("(?:이랑|랑|하고)\\s+|\\s*그리고\\s*|[\\s,，]+"))
            .map { it.trim().replace(Regex("(?<=.{2})(?:이랑|랑|하고|과|와)$"), "") }
            .filter { it.isNotEmpty() }
    }

    // 물품 정정: "A 말고 B"는 교체, "A 빼"·"A는 빼줘요"·"A는 아니야"는 삭제,
    // "A 추가"·"A도"·"A도 있어요"는 추가, 그 외에는 새로 말한 목록으로 바꾼다.
    // "A 빼고 B 추가"처럼 한 문장에 정정이 여러 개면 앞에서부터 차례로 적용한다.
    fun fixItems(items: List<String>, text: String): List<String> {
        val clauses = text.split(Regex("(?<=빼고|지우고|넣고|추가하고|추가해서)\\s+|\\s*그리고\\s+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (clauses.size > 1 && clauses.all { isFixAction(it) }) {
            return clauses.fold(items) { acc, clause -> fixOne(acc, clause) }
        }
        return fixOne(items, text)
    }

    private fun isFixAction(clause: String): Boolean =
        replaceRe.containsMatchIn(clause) || removeRe.containsMatchIn(clause) ||
            addRe.containsMatchIn(clause) || addDoRe.containsMatchIn(clause)

    private fun fixOne(items: List<String>, text: String): List<String> {
        replaceRe.find(text)?.let { m ->
            val out = splitItems(m.groupValues[1])
            return (items.filterNot { it in out } + splitItems(m.groupValues[2])).distinct()
        }
        removeRe.find(text)?.let { m ->
            val out = splitItems(m.groupValues[1])
            return items.filterNot { it in out }
        }
        (addRe.find(text) ?: addDoRe.find(text))?.let { m ->
            val added = splitItems(m.groupValues[1]).map { it.removeSuffix("도") }.filter { it.isNotEmpty() }
            return (items + added).distinct()
        }
        return splitItems(text)
    }

    // 이름만 고치는 말: "이름이 하준이야", "이름은 하준", "하준이라고 해"
    fun nameFix(body: String): String? {
        calledAs.find(body)?.let { return cleanName(it.groupValues[1].replace(namePrefix, "")) }
        return if (namePrefix.containsMatchIn(body)) cleanName(body.replace(namePrefix, "")) else null
    }

    // 이름과 소지품을 한 문장에 고치는 말: "이름이 하준이야, 소지품은 가방, 핸드폰이야"
    fun parseBoth(text: String): Pair<String, List<String>>? {
        val m = bothRe.find(text.trim()) ?: return null
        val name = cleanName(m.groupValues[1]) ?: return null
        val items = splitItems(m.groupValues[2])
        return if (items.isEmpty()) null else name to items
    }
}
