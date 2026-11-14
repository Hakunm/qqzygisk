package com.qm.qqzygisk.hook.app.chat

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.text.NoCopySpan
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ImageSpan
import android.text.style.StyleSpan
import android.text.style.URLSpan
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatTextSimplifierFlowTest {
    @Test
    fun realDictionariesConvertDisplayWithoutMutatingTextOrInteractiveSpans() {
        val assets = InstrumentationRegistry.getInstrumentation().targetContext.assets
        val converter = ChatTextConverter.fromStreams { assets.open("qqzygisk/opencc/$it") }
        val original = "乾坤 頭髮 後臺 简体 ASCII 😀 丽豈 @頭髮 https://例子.test/頭髮 [髮] {髮} 髮"
        val expected = "乾坤 头发 后台 简体 ASCII 😀 丽岂 @頭髮 https://例子.test/頭髮 [髮] {髮} 发"
        val source = SpannableString(original)
        val style = StyleSpan(Typeface.BOLD)
        val supplementaryStyle = StyleSpan(Typeface.ITALIC)
        val mention = URLSpan("qq://mention")
        val emoji = ImageSpan(ColorDrawable(Color.RED))
        val unknown = Any()
        val noCopy = NoCopySpan.Concrete()
        val flags = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        fun attach(span: Any, fragment: String, offset: Int = 0) {
            val start = original.indexOf(fragment) + offset
            assertTrue(start >= 0)
            source.setSpan(span, start, start + fragment.length - offset, flags)
        }
        // A partial visual span must not break the 乾坤 phrase context.
        source.setSpan(style, 0, 1, flags)
        attach(supplementaryStyle, "丽")
        attach(mention, "@頭髮")
        attach(emoji, "[髮]")
        attach(unknown, "{髮}")
        attach(noCopy, "頭髮")

        // Like QQText, the visible CharSequence and toString() need not represent the same text.
        val input = object : Spanned by source {
            override val length: Int get() = source.length
            override fun get(index: Int): Char = source[index]
            override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
                source.subSequence(startIndex, endIndex)
            override fun toString(): String = "different source representation"
        }
        val before = listOf(style, supplementaryStyle, mention, emoji, unknown, noCopy).associateWith {
            Triple(source.getSpanStart(it), source.getSpanEnd(it), source.getSpanFlags(it))
        }
        val converted = converter.convert(input)
        assertEquals(expected, converted.toString())
        assertTrue(converted is Spanned)
        val result = converted as Spanned
        assertEquals(0, result.getSpanStart(style))
        assertEquals(1, result.getSpanEnd(style))
        val originalSupplementary = original.indexOf("丽")
        val convertedSupplementary = expected.indexOf("丽")
        assertEquals(originalSupplementary, source.getSpanStart(supplementaryStyle))
        assertEquals(originalSupplementary + 2, source.getSpanEnd(supplementaryStyle))
        assertEquals(convertedSupplementary, result.getSpanStart(supplementaryStyle))
        assertEquals(convertedSupplementary + 1, result.getSpanEnd(supplementaryStyle))
        for ((span, fragment) in listOf(mention to "@頭髮", emoji to "[髮]", unknown to "{髮}")) {
            val start = expected.indexOf(fragment)
            assertEquals(start, result.getSpanStart(span))
            assertEquals(start + fragment.length, result.getSpanEnd(span))
            assertEquals(flags, result.getSpanFlags(span))
        }
        assertEquals(-1, result.getSpanStart(noCopy))
        assertEquals(original, source.toString())
        for ((span, position) in before) {
            assertEquals(position.first, source.getSpanStart(span))
            assertEquals(position.second, source.getSpanEnd(span))
            assertEquals(position.third, source.getSpanFlags(span))
        }
        assertSame(converted, converter.convert(converted))
        val unchanged: CharSequence = "简体 ASCII 😀"
        assertSame(unchanged, converter.convert(unchanged))
    }
}
