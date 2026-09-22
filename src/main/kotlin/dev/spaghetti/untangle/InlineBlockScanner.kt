package dev.spaghetti.untangle

import com.intellij.psi.XmlRecursiveElementVisitor
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag

/** An inline `<style>` or `<script>` block embedded directly in an HTML file. */
data class InlineBlock(val kind: Kind, val tag: XmlTag, val code: String) {
    enum class Kind { STYLE, SCRIPT }

    val lineCount: Int get() = code.lines().count { it.isNotBlank() }
}

/**
 * Finds every inline CSS and JavaScript block in an HTML file, in document order.
 * Skips external scripts (`src=...`) and non-JavaScript scripts such as JSON-LD or templates.
 */
object InlineBlockScanner {

    private val javaScriptTypes = setOf("", "text/javascript", "application/javascript", "module")

    fun scan(file: XmlFile): List<InlineBlock> {
        val blocks = mutableListOf<InlineBlock>()
        file.accept(object : XmlRecursiveElementVisitor() {
            override fun visitXmlTag(tag: XmlTag) {
                super.visitXmlTag(tag)
                when (tag.name.lowercase()) {
                    "style" -> blocks += InlineBlock(InlineBlock.Kind.STYLE, tag, tag.value.text)
                    "script" -> if (isInlineJavaScript(tag)) {
                        blocks += InlineBlock(InlineBlock.Kind.SCRIPT, tag, tag.value.text)
                    }
                }
            }
        })
        return blocks.sortedBy { it.tag.textOffset }
    }

    private fun isInlineJavaScript(tag: XmlTag): Boolean {
        if (tag.getAttribute("src") != null) return false
        val type = tag.getAttributeValue("type")?.trim()?.lowercase() ?: ""
        return type in javaScriptTypes
    }
}
