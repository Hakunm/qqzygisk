package com.qm.qqzygisk.hook.app.chat

import android.text.NoCopySpan
import android.text.SpannableString
import android.text.Spanned
import android.util.SparseArray
import com.qm.qqzygisk.hook.utils.ModuleUtils
import java.io.InputStream
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipFile

/** The dictionaries are loaded only on the first conversion, from the module APK, not the host. */
internal object ChatTextSimplifier {
    private val converter: ChatTextConverter by lazy {
        ZipFile(ModuleUtils.moduleAppFilePath).use { zip ->
            ChatTextConverter.fromStreams { name ->
                val path = "assets/qqzygisk/opencc/$name"
                zip.getInputStream(requireNotNull(zip.getEntry(path)) { "Missing dictionary: $path" })
            }
        }
    }

    fun convert(text: CharSequence): CharSequence = converter.convert(text)
}

/** Immutable after construction; [open] must return a fresh stream for each named dictionary. */
internal class ChatTextConverter private constructor(
    private val normalization: SparseArray<String>,
    private val characters: SparseArray<String>,
    private val phrases: Node,
) {
    private class Node {
        val children = SparseArray<Node>(0)
        var value: String? = null
    }

    fun convert(text: CharSequence): CharSequence {
        val length = text.length
        if (length == 0) return text
        val spanned = text as? Spanned
        val spans = spanned?.getSpans(0, length, Any::class.java)
        var protectionMask: BooleanArray? = null
        fun protect(start: Int, end: Int) {
            val from = start.coerceIn(0, length)
            val to = end.coerceIn(from, length)
            if (from == to) return
            val mask = protectionMask ?: BooleanArray(length).also { protectionMask = it }
            java.util.Arrays.fill(mask, from, to, true)
        }
        if (spanned != null && spans != null) {
            for (span in spans) {
                if (span !is NoCopySpan && span.javaClass.name !in SAFE_STYLE_CLASSES) {
                    protect(spanned.getSpanStart(span), spanned.getSpanEnd(span))
                }
            }
        }
        // Android's regex matcher can snapshot toString(), which is not necessarily
        // QQText's visible buffer. Scan the same charAt sequence used for conversion.
        var urlStart = 0
        while (urlStart < length) {
            val prefix = when (text[urlStart].lowercaseChar()) {
                'h' -> when {
                    matchesAt(text, urlStart, "https://") -> 8
                    matchesAt(text, urlStart, "http://") -> 7
                    else -> 0
                }
                'f' -> if (matchesAt(text, urlStart, "ftp://")) 6 else 0
                'w' -> if (matchesAt(text, urlStart, "www.")) 4 else 0
                else -> 0
            }
            if (prefix == 0) {
                urlStart++
                continue
            }
            var urlEnd = urlStart + prefix
            while (urlEnd < length) {
                val c = text[urlEnd]
                if (c.isWhitespace() || c == '<' || c == '>' || c == '\"' || c == '\'') break
                urlEnd++
            }
            protect(urlStart, urlEnd)
            urlStart = urlEnd
        }

        fun isProtected(start: Int, end: Int): Boolean {
            val mask = protectionMask ?: return false
            for (i in start until end) if (mask[i]) return true
            return false
        }
        fun codePointAt(index: Int): Int = Character.codePointAt(text, index)
        fun normalizedAt(index: Int, size: Int): Int {
            val original = codePointAt(index)
            if (isProtected(index, index + size)) return original
            val replacement = normalization.get(original) ?: return original
            return Character.codePointAt(replacement, 0)
        }

        var out: StringBuilder? = null
        var boundaries: IntArray? = null
        var index = 0
        while (index < length) {
            val original = codePointAt(index)
            val originalSize = Character.charCount(original)
            var end = index + originalSize
            var replacement: String? = null
            if (!isProtected(index, end)) {
                var node = phrases
                var cursor = index
                while (cursor < length) {
                    val cp = codePointAt(cursor)
                    val size = Character.charCount(cp)
                    if (isProtected(cursor, cursor + size)) break
                    node = node.children.get(normalizedAt(cursor, size)) ?: break
                    cursor += size
                    node.value?.let {
                        replacement = it
                        end = cursor
                    }
                }
                if (replacement == null) {
                    val normalized = normalizedAt(index, originalSize)
                    replacement = characters.get(normalized) ?: normalization.get(original)
                }
            }
            // Each dictionary mapping preserves code point count, so map each UTF-16 boundary
            // independently even when a supplementary code point changes its UTF-16 width.
            var source = index
            var target = 0
            while (source < end) {
                val cp = codePointAt(source)
                val sourceSize = Character.charCount(cp)
                val mapped = replacement?.let { Character.codePointAt(it, target) } ?: cp
                val mappedSize = Character.charCount(mapped)
                if (out == null && mapped != cp) {
                    out = StringBuilder(length).append(text, 0, source)
                    if (spanned != null) {
                        boundaries = IntArray(length + 1) { it }
                    }
                }
                val builder = out
                if (builder != null) {
                    val outputStart = builder.length
                    builder.appendCodePoint(mapped)
                    boundaries?.let { map ->
                        for (offset in 0..sourceSize) {
                            map[source + offset] = outputStart + offset.coerceAtMost(mappedSize)
                        }
                    }
                }
                source += sourceSize
                if (replacement != null) target += mappedSize
            }
            index = end
        }
        val result = out ?: return text
        if (spanned == null || spans == null) return result.toString()
        val mapped = SpannableString(result.toString())
        val map = checkNotNull(boundaries)
        for (span in spans) {
            if (span is NoCopySpan) continue
            val start = spanned.getSpanStart(span)
            val end = spanned.getSpanEnd(span)
            if (start >= 0 && end >= start && end <= length) {
                mapped.setSpan(span, map[start], map[end], spanned.getSpanFlags(span))
            }
        }
        return mapped
    }

    companion object {
        private fun matchesAt(text: CharSequence, offset: Int, prefix: String): Boolean {
            if (text.length - offset < prefix.length) return false
            for (i in prefix.indices) if (!text[offset + i].equals(prefix[i], ignoreCase = true)) return false
            return true
        }

        private val MAPPING_SEPARATOR = Regex("\\s+")
        // Only framework spans whose effect is purely visual or paragraph layout are allowed
        // to participate in phrase context. Unknown and host-defined spans remain untouched.
        private val SAFE_STYLE_CLASSES = setOf(
            "android.text.style.StyleSpan", "android.text.style.ForegroundColorSpan",
            "android.text.style.BackgroundColorSpan", "android.text.style.RelativeSizeSpan",
            "android.text.style.AbsoluteSizeSpan", "android.text.style.ScaleXSpan",
            "android.text.style.StrikethroughSpan", "android.text.style.UnderlineSpan",
            "android.text.style.TypefaceSpan", "android.text.style.SuperscriptSpan",
            "android.text.style.SubscriptSpan", "android.text.style.LocaleSpan",
            "android.text.style.TextAppearanceSpan", "android.text.style.AlignmentSpan\$Standard",
            "android.text.style.LeadingMarginSpan\$Standard", "android.text.style.BulletSpan",
            "android.text.style.QuoteSpan", "android.text.style.TabStopSpan\$Standard",
        )

        fun fromStreams(open: (String) -> InputStream): ChatTextConverter {
            val normalization = SparseArray<String>()
            val characters = SparseArray<String>()
            val phrases = Node()
            readDictionary(open, "CJK_Compatibility_Ideographs.txt") { key, value ->
                require(key.codePointCount(0, key.length) == 1) { "Invalid normalization key: $key" }
                val cp = key.codePointAt(0)
                require(normalization.get(cp) == null) { "Duplicate normalization key: $key" }
                normalization.put(cp, value)
            }
            readDictionary(open, "TSCharacters.txt") { key, value ->
                require(key.codePointCount(0, key.length) == 1) { "Invalid character key: $key" }
                val cp = key.codePointAt(0)
                require(characters.get(cp) == null) { "Duplicate character key: $key" }
                characters.put(cp, value)
            }
            readDictionary(open, "TSPhrases.txt") { key, value ->
                var node = phrases
                var i = 0
                while (i < key.length) {
                    val cp = key.codePointAt(i)
                    var child = node.children.get(cp)
                    if (child == null) {
                        child = Node()
                        node.children.put(cp, child)
                    }
                    node = child
                    i += Character.charCount(cp)
                }
                require(node.value == null) { "Duplicate phrase key: $key" }
                node.value = value
            }
            return ChatTextConverter(normalization, characters, phrases)
        }

        private fun readDictionary(open: (String) -> InputStream, name: String, add: (String, String) -> Unit) {
            var count = 0
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            java.io.InputStreamReader(open(name), decoder).buffered().useLines { lines ->
                lines.forEachIndexed { lineNumber, raw ->
                    val line = raw.trim()
                    if (line.isEmpty() || line.startsWith('#')) return@forEachIndexed
                    val fields = line.split('\t')
                    require(fields.size == 2 && fields[0].isNotEmpty() && fields[1].isNotBlank()) {
                        "Invalid $name line ${lineNumber + 1}"
                    }
                    val key = fields[0]
                    val values = fields[1].trim().split(MAPPING_SEPARATOR)
                    require(validUnicode(key) && values.all { it.isNotEmpty() && validUnicode(it) }) {
                        "Invalid Unicode in $name line ${lineNumber + 1}"
                    }
                    val value = values.first()
                    require(key.codePointCount(0, key.length) == value.codePointCount(0, value.length)) {
                        "Unequal code point counts in $name line ${lineNumber + 1}"
                    }
                    add(key, value)
                    count++
                }
            }
            require(count > 0) { "Empty dictionary: $name" }
        }

        private fun validUnicode(value: String): Boolean {
            var i = 0
            while (i < value.length) {
                val c = value[i]
                if (Character.isHighSurrogate(c)) {
                    if (i + 1 >= value.length || !Character.isLowSurrogate(value[i + 1])) return false
                    i += 2
                } else {
                    if (Character.isLowSurrogate(c) || c.isWhitespace()) return false
                    i++
                }
            }
            return true
        }
    }
}
