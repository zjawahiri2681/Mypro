package com.mypro.minutes.core

/**
 * Escaping for text that people said or typed, so that it can never change the structure of the
 * exported document (no accidental headings, lists, tables, links, HTML or front-matter breaks).
 */
object Md {
    private const val INLINE_SPECIALS = "\\`*_[]<>|~"
    private val LEADING_ORDERED = Regex("^(\\d+)([.)])")

    /** Escapes one line of free text for use inside a paragraph, list item or table cell. */
    fun inline(text: String): String {
        val sb = StringBuilder(text.length + 8)
        for (c in text) {
            when {
                c in INLINE_SPECIALS -> sb.append('\\').append(c)
                c == '\t' -> sb.append(' ')
                c.isISOControl() -> {}
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    /** Escapes a line that will start a Markdown line (block markers only matter there). */
    fun lineStart(text: String): String {
        val t = inline(text)
        if (t.isEmpty()) return t
        LEADING_ORDERED.find(t)?.let { return it.groupValues[1] + "\\" + t.substring(it.groupValues[1].length) }
        return if (t[0] in "#-+=>") "\\" + t else t
    }

    /**
     * Multi-line text inside a list item: the first line follows the item prefix, the rest are indented
     * continuation lines. Blank lines are dropped so the item never splits.
     */
    fun block(text: String, indent: String): String {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return ""
        return buildString {
            append(inline(lines.first()))
            for (l in lines.drop(1)) append("  \n").append(indent).append(lineStart(l))
        }
    }

    /** A table cell: a single line, pipes escaped. */
    fun cell(text: String): String = inline(text.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" / "))

    /** A double-quoted YAML scalar. */
    fun yaml(text: String): String {
        val s = MeetingOps.clean(text).replace("\\", "\\\\").replace("\"", "\\\"")
        return "\"$s\""
    }
}
