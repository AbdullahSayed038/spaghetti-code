package dev.spaghetti.plan

import com.intellij.openapi.util.TextRange
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag
import dev.spaghetti.untangle.InlineBlock

/**
 * The "Light" strategy: pull out only what is obviously separate, and change nothing about behavior.
 *
 * - `<style>` blocks that sit next to each other in the cascade are merged into one CSS file. A run
 *   ends wherever another stylesheet or a `<style>` we are leaving alone sits between two blocks,
 *   because merging across it would change which rule wins.
 * - Each large inline script becomes its own JS file and its tag is replaced in place, so scripts
 *   still run in the same order, at the same point in the page, as classic scripts (not modules,
 *   which browsers refuse to load from `file://`).
 * - Small blocks, modules, JSON-LD, external scripts and anything with unusual attributes stay put.
 *
 * Code is moved verbatim. The only change to CSS is removing the indentation it had inside the HTML.
 */
object LightPlanner {

    /** Blocks shorter than this stay inline: extracting them costs more clarity than it buys. */
    const val MIN_LINES = 15

    private val genericNameWords = setOf("script", "js", "javascript", "css", "style", "styles", "code", "the", "a", "an")
    private val cssAttributes = setOf("type")
    private val jsAttributes = setOf("type")
    private val classicJsTypes = setOf("", "text/javascript", "application/javascript")

    /** @param exists tells whether a path (relative to the HTML file's folder) is already taken. */
    fun plan(file: XmlFile, exists: (String) -> Boolean = { false }): SplitPlan {
        val text = file.text
        val page = file.name.substringBeforeLast('.').lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "page" }
        val used = mutableSetOf<String>()
        val files = mutableListOf<PlannedFile>()

        files += planStyles(file, text) { dir, base, ext -> PlanPaths.uniquePath(dir, base, ext, page, used, exists) }
        files += planScripts(file, text) { dir, base, ext -> PlanPaths.uniquePath(dir, base, ext, page, used, exists) }

        return SplitPlan(files)
    }

    // ---- CSS ----------------------------------------------------------------------------------

    private fun planStyles(file: XmlFile, text: String, pathFor: (String, String, String) -> String): List<PlannedFile> {
        return findStyleRuns(file).mapIndexed { index, run ->
            val path = pathFor("css", if (index == 0) "styles" else "styles-${index + 1}", "css")
            val code = mergedStyleRunText(run)
            val lines = run.sumOf { InlineBlock(InlineBlock.Kind.STYLE, it, it.value.text).lineCount }
            val link = """<link rel="stylesheet" href="$path">"""

            val edits = run.mapIndexed { i, tag ->
                if (i == 0) HtmlEdit(tag.textRange, link) else HtmlEdit(wholeLinesIfAlone(text, tag.textRange), "")
            }
            PlannedFile(path, code, "${run.size} <style> block${if (run.size == 1) "" else "s"}, $lines lines of CSS", edits)
        }
    }

    /**
     * Runs of `<style>` blocks that sit next to each other in the cascade, in document order. A run ends
     * wherever another stylesheet, or a `<style>` we are leaving alone, sits between two blocks, because
     * merging across it would change which rule wins.
     */
    internal fun findStyleRuns(file: XmlFile): List<List<XmlTag>> {
        val runs = mutableListOf<MutableList<XmlTag>>()
        var current: MutableList<XmlTag>? = null
        for (tag in PsiTreeUtil.findChildrenOfType(file, XmlTag::class.java)) {
            when {
                isExtractableStyle(tag) -> {
                    if (current == null) current = mutableListOf<XmlTag>().also { runs += it }
                    current.add(tag)
                }
                tag.name.equals("style", ignoreCase = true) || isStylesheetLink(tag) -> current = null
            }
        }
        return runs
    }

    /** The CSS text of a run, as [planStyles] would write it to one file: dedented, joined, in order. */
    internal fun mergedStyleRunText(run: List<XmlTag>): String =
        run.joinToString("\n\n") { dedent(it.value.text).trim('\n', '\r') }.trimEnd() + "\n"

    private fun isExtractableStyle(tag: XmlTag): Boolean {
        if (!tag.name.equals("style", ignoreCase = true)) return false
        if (tag.attributes.any { it.name.lowercase() !in cssAttributes }) return false
        val type = tag.getAttributeValue("type")?.trim()?.lowercase() ?: ""
        if (type != "" && type != "text/css") return false
        val code = tag.value.text
        // @import is only valid at the top of a stylesheet, so a block that has one cannot be merged.
        if (code.contains("@import")) return false
        return InlineBlock(InlineBlock.Kind.STYLE, tag, code).lineCount >= MIN_LINES
    }

    private fun isStylesheetLink(tag: XmlTag): Boolean =
        tag.name.equals("link", ignoreCase = true) &&
            (tag.getAttributeValue("rel") ?: "").split(Regex("\\s+")).any { it.equals("stylesheet", ignoreCase = true) }

    // ---- JavaScript ---------------------------------------------------------------------------

    private fun planScripts(file: XmlFile, text: String, pathFor: (String, String, String) -> String): List<PlannedFile> {
        return findExtractableScripts(file).mapIndexed { index, tag ->
            val code = tag.value.text
            val base = slugFromLeadingComment(code) ?: "script-${index + 1}"
            val path = pathFor("js", base, "js")
            val lines = InlineBlock(InlineBlock.Kind.SCRIPT, tag, code).lineCount
            // Verbatim: re-indenting could change the contents of multi-line template literals.
            val content = code.trim('\n', '\r').trimEnd() + "\n"
            PlannedFile(
                path,
                content,
                "$lines lines of JavaScript",
                listOf(HtmlEdit(tag.textRange, """<script src="$path"></script>""")),
            )
        }
    }

    /** Large `<script>` tags safe to pull into their own file: classic JS only, no unusual attributes. */
    internal fun findExtractableScripts(file: XmlFile): List<XmlTag> =
        PsiTreeUtil.findChildrenOfType(file, XmlTag::class.java).filter(::isExtractableScript)

    private fun isExtractableScript(tag: XmlTag): Boolean {
        if (!tag.name.equals("script", ignoreCase = true)) return false
        if (tag.attributes.any { it.name.lowercase() !in jsAttributes }) return false // src, defer, nonce, id, data-*...
        val type = tag.getAttributeValue("type")?.trim()?.lowercase() ?: ""
        if (type !in classicJsTypes) return false
        return InlineBlock(InlineBlock.Kind.SCRIPT, tag, tag.value.text).lineCount >= MIN_LINES
    }

    // ---- helpers ------------------------------------------------------------------------------

    /** "// pricing logic (runs after the markup)" -> "pricing-logic"; null if the code does not start with a comment. */
    internal fun slugFromLeadingComment(code: String): String? {
        val first = code.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: return null
        if (!first.startsWith("//") && !first.startsWith("/*")) return null
        return slugFromCommentText(first)
    }

    /** "/* ---------- pricing logic ---------- */" or "// pricing logic" -> "pricing-logic"; null if nothing usable is left. */
    internal fun slugFromCommentText(commentText: String): String? {
        val words = commentText
            .removePrefix("//").removePrefix("/*").removeSuffix("*/")
            .substringBefore('(').substringBefore(" - ").substringBefore(':')
            .lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { it.isNotEmpty() && it !in genericNameWords }
            .take(3)
        return words.joinToString("-").ifEmpty { null }
    }

    /** Removes the indentation shared by every non-blank line. */
    internal fun dedent(code: String): String {
        val lines = code.lines()
        val indent = lines.filter { it.isNotBlank() }.minOfOrNull { line -> line.takeWhile { it == ' ' || it == '\t' }.length } ?: 0
        return lines.joinToString("\n") { if (it.length >= indent) it.substring(indent) else it.trimStart() }
    }

    /** If the range is the only thing on its line(s), widen it to swallow the whole line(s), so no blank gap is left behind. */
    private fun wholeLinesIfAlone(text: String, range: TextRange): TextRange {
        var start = range.startOffset
        while (start > 0 && (text[start - 1] == ' ' || text[start - 1] == '\t')) start--
        var end = range.endOffset
        while (end < text.length && (text[end] == ' ' || text[end] == '\t' || text[end] == '\r')) end++
        val startsLine = start == 0 || text[start - 1] == '\n'
        val endsLine = end >= text.length || text[end] == '\n'
        if (!startsLine || !endsLine) return range
        return TextRange(start, if (end < text.length) end + 1 else end)
    }
}
