package de.miraculixx.worlds.client.ui.markdown

/**
 * HTML → Markdown
 * Ignores any keys that are not the basics for rendering
 *
 * Line breaks from `<br>` are emitted as Markdown **hard breaks** (two trailing spaces) so they stay
 * inside one paragraph instead of opening a new block per line.
 */
object HtmlToMarkdown {
    private val TAG = Regex("""<!--.*?-->|</?([a-zA-Z][a-zA-Z0-9]*)([^>]*)>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val ATTR_HREF = Regex("""\bhref\s*=\s*("([^"]*)"|'([^']*)'|([^\s>]+))""", RegexOption.IGNORE_CASE)
    private val ATTR_SRC = Regex("""\bsrc\s*=\s*("([^"]*)"|'([^']*)'|([^\s>]+))""", RegexOption.IGNORE_CASE)
    private val ATTR_ALT = Regex("""\balt\s*=\s*("([^"]*)"|'([^']*)'|([^\s>]+))""", RegexOption.IGNORE_CASE)
    private val WHITESPACE = Regex("""\s+""")
    /** A link whose whole content is image(s) — Markdown has no linked-image block, so it is dropped. */
    private val IMAGE_ONLY = Regex("""(!\[[^\]]*]\(\S+\)\s*)+""")
    private val BLANK_RUN = Regex("""\n{3,}""")
    private val NUMERIC_ENTITY = Regex("""&#(x?)([0-9a-fA-F]+);""")

    private val ENTITIES = mapOf(
        "&amp;" to "&", "&lt;" to "<", "&gt;" to ">", "&quot;" to "\"", "&apos;" to "'",
        "&nbsp;" to " ", "&ndash;" to "–", "&mdash;" to "—", "&hellip;" to "…",
        "&bull;" to "•", "&copy;" to "©", "&reg;" to "®", "&trade;" to "™",
        "&ldquo;" to "“", "&rdquo;" to "”", "&lsquo;" to "‘", "&rsquo;" to "’",
    )

    /** Tags whose content is markup, not prose. */
    private val DROPPED = setOf("script", "style", "head", "noscript")

    /**
     * [resolveLink] gets every `href`/`src` and may rewrite or reject it (null = drop the link but keep its text)
     * CurseForge wraps outgoing links in `/linkout?remoteUrl=…`.
     */
    fun convert(html: String?, resolveLink: (String) -> String? = { it }): String {
        if (html.isNullOrBlank()) return ""
        if (!TAG.containsMatchIn(html)) return html.trim()
        val out = StringBuilder()
        var bold = 0
        var italic = 0
        var code = 0
        var drop = 0
        // href + the index of the '[' it opened, so an image-only link can be unwrapped on close.
        val links = ArrayList<Pair<String, Int>?>()
        val lists = ArrayList<Int?>()
        var last = 0

        // <br> inside a span: Markdown matches inline spans within one line, so close every open
        // marker before the break and reopen it after, or the asterisks render literally.
        fun lineBreak() {
            if (out.isEmpty()) return
            val open = buildList {
                if (bold > 0) add("**")
                if (italic > 0) add("*")
                if (code > 0) add("`")
            }
            out.trimTrailingSpaces()
            // Consecutive <br>s leave the reopened markers with nothing between them.
            for (marker in open.asReversed()) {
                if (out.endsWith(marker)) out.setLength(out.length - marker.length) else out.append(marker)
            }
            out.append("  \n")
            for (marker in open) out.append(marker)
        }

        for (m in TAG.findAll(html)) {
            if (drop == 0) out.text(html.substring(last, m.range.first))
            last = m.range.last + 1
            val name = m.groupValues[1].lowercase()
            if (name.isEmpty()) continue
            val closing = m.value.startsWith("</")
            val attrs = m.groupValues[2]

            if (name in DROPPED) {
                if (closing) drop = (drop - 1).coerceAtLeast(0) else drop++
                continue
            }
            if (drop > 0) continue

            when (name) {
                "br" -> lineBreak()
                "p", "blockquote", "table", "ul", "ol", "dl" -> {
                    out.blankLine()
                    if (name == "ul" || name == "ol") {
                        if (closing) lists.removeLastOrNull() else lists.add(if (name == "ol") 1 else null)
                    }
                }
                "div", "section", "article", "tr", "dt", "dd" -> out.newLine()
                "hr" -> { out.blankLine(); out.append("---"); out.blankLine() }
                "h1", "h2", "h3", "h4", "h5", "h6" -> {
                    out.blankLine()
                    if (!closing) out.append("#".repeat(name[1].digitToInt())).append(' ')
                }
                "li" -> if (!closing) {
                    out.newLine()
                    val counter = lists.lastOrNull()
                    if (counter == null) out.append("- ")
                    else {
                        out.append(counter).append(". ")
                        lists[lists.lastIndex] = counter + 1
                    }
                }
                "strong", "b" -> bold = out.emphasis("**", closing, bold)
                "em", "i" -> italic = out.emphasis("*", closing, italic)
                "code" -> code = out.emphasis("`", closing, code)
                "pre" -> { out.blankLine(); out.append("```"); out.blankLine() }
                "td", "th" -> out.space()
                "img" -> attr(ATTR_SRC, attrs)?.let(resolveLink)?.let { src ->
                    out.blankLine()
                    out.append("![").append(attr(ATTR_ALT, attrs) ?: "").append("](").append(src).append(')')
                    out.blankLine()
                }
                "iframe" -> if (!closing) attr(ATTR_SRC, attrs)?.let(resolveLink)?.let { src ->
                    out.blankLine()
                    out.append('[').append(src).append("](").append(src).append(')')
                    out.blankLine()
                }
                "a" -> if (closing) {
                    val link = links.removeLastOrNull()
                    if (link != null) {
                        val (href, mark) = link
                        val content = out.substring(mark + 1)
                        when {
                            content.isBlank() -> out.setLength(mark)
                            // Ignore links on images
                            IMAGE_ONLY.matches(content.trim()) -> out.deleteCharAt(mark)
                            else -> out.append("](").append(href).append(')')
                        }
                    }
                } else {
                    val href = attr(ATTR_HREF, attrs)?.let(resolveLink)
                    links.add(href?.let { it to out.length })
                    if (href != null) out.append('[')
                }
            }
        }
        if (drop == 0) out.text(html.substring(last))
        // A <br> next to a block leaves a line holding nothing but its hard-break spaces.
        val text = out.lineSequence().joinToString("\n") { it.ifBlank { "" } }
        return BLANK_RUN.replace(text, "\n\n").trim()
    }

    private fun attr(regex: Regex, attrs: String): String? {
        val m = regex.find(attrs) ?: return null
        val raw = m.groupValues.drop(2).firstOrNull { it.isNotEmpty() } ?: return null
        return decodeEntities(raw).trim().takeIf { it.isNotEmpty() }
    }

    /** Text between tags: HTML collapses every whitespace run to a single space. */
    private fun StringBuilder.text(raw: String) {
        if (raw.isEmpty()) return
        val collapsed = WHITESPACE.replace(decodeEntities(raw), " ")
        if (collapsed.isBlank()) { space(); return }
        if (collapsed.startsWith(" ")) space()
        append(collapsed.trim())
        if (collapsed.endsWith(" ")) append(' ')
    }

    private fun StringBuilder.space() {
        if (isNotEmpty() && last() != ' ' && last() != '\n') append(' ')
    }

    private fun StringBuilder.trimTrailingSpaces() {
        while (isNotEmpty() && last() == ' ') setLength(length - 1)
    }

    private fun StringBuilder.newLine() {
        if (isEmpty()) return
        trimTrailingSpaces()
        if (last() != '\n') append('\n')
    }

    private fun StringBuilder.blankLine() {
        if (isEmpty()) return
        newLine()
        if (length >= 2 && this[length - 2] != '\n') append('\n')
    }

    /**
     * Emphasis markers are only written for the outermost span CurseForge nests `<strong>` inside `<strong>`,
     * and `****text****` renders as literal asterisks. Trailing spaces are pushed out of
     * the span (`**text **` would not close it) and an empty span is removed entirely.
     */
    private fun StringBuilder.emphasis(marker: String, closing: Boolean, depth: Int): Int {
        if (!closing) {
            if (depth == 0) append(marker)
            return depth + 1
        }
        if (depth != 1) return (depth - 1).coerceAtLeast(0)
        var trailing = 0
        while (isNotEmpty() && last() == ' ') { setLength(length - 1); trailing++ }
        if (endsWith(marker)) setLength(length - marker.length) else append(marker)
        repeat(trailing) { append(' ') }
        return 0
    }

    private fun decodeEntities(text: String): String {
        if ('&' !in text) return text
        var s = text
        for ((entity, ch) in ENTITIES) s = s.replace(entity, ch, ignoreCase = true)
        return NUMERIC_ENTITY.replace(s) { m ->
            val code = m.groupValues[2].toIntOrNull(if (m.groupValues[1].isEmpty()) 10 else 16)
            if (code != null && code in 1..0x10FFFF) String(Character.toChars(code)) else m.value
        }
    }
}
