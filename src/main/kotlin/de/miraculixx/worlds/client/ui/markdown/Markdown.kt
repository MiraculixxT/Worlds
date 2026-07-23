package de.miraculixx.worlds.client.ui.markdown

import java.net.URI
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.chat.Style

/** A parsed, renderable block of a Markdown document. */
sealed interface MdBlock {
    data class Heading(val level: Int, val text: Component) : MdBlock
    data class Paragraph(val text: Component) : MdBlock
    data class ListItem(val bullet: String, val text: Component) : MdBlock
    data class Code(val text: Component) : MdBlock
    data class Image(val url: String, val alt: String) : MdBlock
    data object Rule : MdBlock
    data object Spacer : MdBlock
}

/**
 * Small Markdown-to-[MdBlock] parser covering the subset that appears in mod readmes: headings,
 * paragraphs, bullet/numbered lists, fenced code, horizontal rules, block images, and the inline
 * spans `**bold**`, `*italic*`/`_italic_`, `` `code` `` and `[text](url)`.
 */
object Markdown {
    private val IMAGE_LINE = Regex("""^!\[(.*?)]\((\S+?)\)\s*$""")
    private val HEADING = Regex("""^(#{1,6})\s+(.*)$""")
    private val UNORDERED = Regex("""^\s*[-*+]\s+(.*)$""")
    private val ORDERED = Regex("""^\s*(\d+)\.\s+(.*)$""")
    private val RULE = Regex("""^\s*([-*_])\1{2,}\s*$""")

    fun parse(markdown: String?): List<MdBlock> {
        if (markdown.isNullOrBlank()) return emptyList()
        val blocks = ArrayList<MdBlock>()
        val lines = markdown.replace("\r\n", "\n").replace("\r", "\n").split("\n")

        var i = 0
        val paragraph = StringBuilder()
        fun flushParagraph() {
            if (paragraph.isNotBlank()) blocks.add(MdBlock.Paragraph(inline(paragraph.trim().toString())))
            paragraph.setLength(0)
        }

        while (i < lines.size) {
            val line = lines[i]
            when {
                line.startsWith("```") -> {
                    flushParagraph()
                    val code = StringBuilder()
                    i++
                    while (i < lines.size && !lines[i].startsWith("```")) {
                        code.appendLine(lines[i]); i++
                    }
                    blocks.add(MdBlock.Code(Component.literal(code.toString().trimEnd())
                        .withStyle { it.withColor(ChatFormatting.GRAY) }))
                }
                line.isBlank() -> {
                    flushParagraph()
                    if (blocks.lastOrNull() !is MdBlock.Spacer && blocks.isNotEmpty()) blocks.add(MdBlock.Spacer)
                }
                RULE.matches(line) -> { flushParagraph(); blocks.add(MdBlock.Rule) }
                IMAGE_LINE.matches(line) -> {
                    flushParagraph()
                    val m = IMAGE_LINE.find(line)!!
                    blocks.add(MdBlock.Image(m.groupValues[2], m.groupValues[1]))
                }
                HEADING.matches(line) -> {
                    flushParagraph()
                    val m = HEADING.find(line)!!
                    blocks.add(MdBlock.Heading(m.groupValues[1].length, inline(m.groupValues[2])))
                }
                UNORDERED.matches(line) -> {
                    flushParagraph()
                    blocks.add(MdBlock.ListItem("•", inline(UNORDERED.find(line)!!.groupValues[1])))
                }
                ORDERED.matches(line) -> {
                    flushParagraph()
                    val m = ORDERED.find(line)!!
                    blocks.add(MdBlock.ListItem("${m.groupValues[1]}.", inline(m.groupValues[2])))
                }
                else -> {
                    if (paragraph.isNotEmpty()) paragraph.append(' ')
                    paragraph.append(line.trim())
                }
            }
            i++
        }
        flushParagraph()
        return blocks
    }

    // --- inline span parsing -------------------------------------------------

    private val INLINE = Regex("""(\*\*|__)(.+?)\1|(\*|_)(.+?)\3|`([^`]+?)`|\[(.+?)]\((\S+?)\)""")

    /** Parse a single line of inline Markdown into a styled [Component]. */
    fun inline(text: String): Component {
        val root: MutableComponent = Component.empty()
        var last = 0
        for (match in INLINE.findAll(text)) {
            if (match.range.first > last) root.append(Component.literal(text.substring(last, match.range.first)))
            val g = match.groupValues
            when {
                g[1].isNotEmpty() -> root.append(Component.literal(g[2]).withStyle { it.withBold(true) })
                g[3].isNotEmpty() -> root.append(Component.literal(g[4]).withStyle { it.withItalic(true) })
                g[5].isNotEmpty() -> root.append(Component.literal(g[5]).withStyle { it.withColor(ChatFormatting.GRAY) })
                g[6].isNotEmpty() -> root.append(linkComponent(g[6], g[7]))
            }
            last = match.range.last + 1
        }
        if (last < text.length) root.append(Component.literal(text.substring(last)))
        return root
    }

    private fun linkComponent(label: String, url: String): Component {
        var style: Style = Style.EMPTY
            .withColor(ChatFormatting.BLUE)
            .withUnderlined(true)
        style = try {
            style.withClickEvent(ClickEvent.OpenUrl(URI.create(url)))
        } catch (_: Exception) {
            style
        }
        return Component.literal(label).setStyle(style)
    }
}
