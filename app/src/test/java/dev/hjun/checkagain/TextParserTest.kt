package dev.hjun.checkagain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextParserTest {

    private fun name(text: String) = (TextParser.classifyName(text) as? NameAnswer.Name)?.value

    @Test
    fun 이름만_말하면_이름으로_받는다() {
        assertEquals("민수", name("민수"))
        assertEquals("하은", name("하은"))
        assertEquals("지은", name("지은"))
        assertEquals("김하은", name("김하은"))
        assertEquals("예림", name("예림이"))
        assertEquals("하준", name("하준이야"))
        assertEquals("민수", name("민수입니다"))
    }

    @Test
    fun 문장_꼴로_말해도_이름을_뽑는다() {
        assertEquals("하준", name("이름은 하준이에요"))
        assertEquals("하준", name("제 이름은 하준이야"))
        assertEquals("민수", name("민수가 탔어요"))
        assertEquals("김하은", name("김하은 탔어요"))
        assertEquals("김하은", name("김하은이 탔어요"))
    }

    @Test
    fun 짐을_말하면_짐으로_분류한다() {
        assertEquals(NameAnswer.Bag, TextParser.classifyName("짐"))
        assertEquals(NameAnswer.Bag, TextParser.classifyName("짐이에요"))
        assertEquals(NameAnswer.Bag, TextParser.classifyName("짐만 두고 내렸어요"))
        assertEquals(NameAnswer.Bag, TextParser.classifyName("짐만 있어요"))
        assertEquals(NameAnswer.Bag, TextParser.classifyName("짐 넣었어요"))
    }

    @Test
    fun 거절하면_취소로_분류한다() {
        assertEquals(NameAnswer.Cancel, TextParser.classifyName("아니요"))
        assertEquals(NameAnswer.Cancel, TextParser.classifyName("아니요 괜찮아요"))
        assertEquals(NameAnswer.Cancel, TextParser.classifyName("없어요"))
        assertEquals(NameAnswer.Cancel, TextParser.classifyName("취소"))
        assertEquals(NameAnswer.Cancel, TextParser.classifyName("등록 안 할게요"))
        assertEquals(NameAnswer.Cancel, TextParser.classifyName("아무도 없어요"))
    }

    @Test
    fun 이름이_아닌_긴_문장은_해석하지_않는다() {
        assertEquals(NameAnswer.Unclear, TextParser.classifyName(""))
        assertEquals(NameAnswer.Unclear, TextParser.classifyName("   "))
        assertEquals(NameAnswer.Unclear, TextParser.classifyName("오늘 날씨가 정말 좋네요"))
        assertEquals(NameAnswer.Unclear, TextParser.classifyName("민수 짐이에요"))
    }

    @Test
    fun 취소와_짐_표현이_이름과_섞이지_않는다() {
        assertFalse(TextParser.isCancel("아니요 민수"))
        assertTrue(TextParser.isCancel("아니요"))
        assertTrue(TextParser.isBagOnly("짐만"))
        assertFalse(TextParser.isBagOnly("짐꾼"))
    }

    @Test
    fun 긍정_표현을_가려낸다() {
        assertTrue(TextParser.isYes("네"))
        assertTrue(TextParser.isYes("맞아요"))
        assertTrue(TextParser.isYes("응 맞아"))
        assertFalse(TextParser.isYes("네 근데 모자도"))
        assertEquals("모자도", TextParser.stripLeadYes("네 근데 모자도"))
        assertEquals("어댑터", TextParser.stripLeadYes("어댑터"))
        assertEquals("가방", TextParser.stripNegation("아니 가방"))
    }

    @Test
    fun 소지품을_나눈다() {
        assertEquals(listOf("가방", "핸드폰"), TextParser.splitItems("가방, 핸드폰"))
        assertEquals(listOf("가방", "핸드폰"), TextParser.splitItems("가방이랑 핸드폰이야"))
        assertEquals(listOf("가방", "모자"), TextParser.splitItems("소지품은 가방 그리고 모자"))
    }

    @Test
    fun 물품_정정을_적용한다() {
        val base = listOf("골프백", "가방")
        assertEquals(listOf("골프백", "가방", "모자"), TextParser.fixItems(base, "모자도"))
        assertEquals(listOf("가방"), TextParser.fixItems(base, "골프백은 빼줘요"))
        assertEquals(listOf("가방"), TextParser.fixItems(base, "골프백 빼"))
        assertEquals(listOf("가방", "핸드폰"), TextParser.fixItems(base, "골프백 말고 핸드폰"))
        assertEquals(listOf("가방", "모자"), TextParser.fixItems(base, "골프백 빼고 모자 추가"))
        assertEquals(listOf("골프백", "가방", "모자"), TextParser.fixItems(base, "모자 추가"))
        assertEquals(listOf("우산", "책"), TextParser.fixItems(base, "우산 책"))
    }

    @Test
    fun 이름_정정을_알아듣는다() {
        assertEquals("하준", TextParser.nameFix("이름이 하준이야"))
        assertEquals("하준", TextParser.nameFix("이름은 하준"))
        assertEquals("하준", TextParser.nameFix("하준이라고 해"))
        assertNull(TextParser.nameFix("가방이야"))
    }

    @Test
    fun 다시_말해_달라는_요청을_알아듣는다() {
        listOf("뭐?", "뭐", "뭐라고", "뭐라고요?", "뭐래는 거야", "못 들었어", "못 들었어요", "안 들려", "다시 말해줘", "다시 말해 주세요", "한 번 더 말해줘", "무슨 말이야")
            .forEach { assertTrue(it, TextParser.isRepeatRequest(it)) }
    }

    @Test
    fun 소지품이나_정정은_다시_말해_달라는_요청이_아니다() {
        listOf("핸드폰이랑 가방", "가방 말고 지갑", "모자도", "뭐뭐", "다시마", "", "맞아요", "아니")
            .forEach { assertFalse(it, TextParser.isRepeatRequest(it)) }
    }

    @Test
    fun 이름과_소지품을_한꺼번에_정정한다() {
        assertEquals("하준" to listOf("가방", "핸드폰"), TextParser.parseBoth("이름이 하준이야, 소지품은 가방, 핸드폰이야"))
        assertNull(TextParser.parseBoth("모자도"))
    }
}
