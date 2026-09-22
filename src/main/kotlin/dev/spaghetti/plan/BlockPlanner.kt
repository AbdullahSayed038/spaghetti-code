package dev.spaghetti.plan

import com.intellij.openapi.util.TextRange
import com.intellij.psi.xml.XmlTag

/**
 * Turns one inline block's [Section]s into [PlannedFile]s, replacing the block's own tag(s) with one
 * link/script per resulting file, in order — the mechanics [FeaturePlanner] and [TypePlanner] share.
 *
 * [classify] picks each section's directory and base file name; ties are de-duplicated with [PlanPaths].
 * Every resulting file gets [groupId], so the preview must tick or untick them all together (see
 * [PlannedFile.groupId] for why: writing only some of them would 404 a link or duplicate the code).
 */
internal object BlockPlanner {

    fun plan(
        sections: List<Section>,
        ext: String,
        page: String,
        used: MutableSet<String>,
        exists: (String) -> Boolean,
        wrapEdit: (String) -> String,
        run: List<XmlTag>,
        groupId: String,
        classify: (section: Section, index: Int, total: Int) -> Pair<String, String>,
    ): List<PlannedFile> {
        if (sections.isEmpty()) return emptyList()

        val plannedFiles = sections.mapIndexed { i, section ->
            val (dir, base) = classify(section, i, sections.size)
            val path = PlanPaths.uniquePath(dir, base, ext, page, used, exists)
            val lines = section.text.lines().count { it.isNotBlank() }
            PlannedFile(path, section.text.trim('\n', '\r').trimEnd() + "\n", "$lines lines", listOf(), groupId)
        }

        // Replace the run's first tag with every resulting file's link/script, in order; remove the rest of the run.
        // This edit only makes sense if every file in the group survives together — see PlannedFile.groupId.
        val replacement = plannedFiles.joinToString("\n") { wrapEdit(it.relativePath) }
        val htmlText = run.first().containingFile.text
        val edits = run.mapIndexed { i, tag ->
            if (i == 0) HtmlEdit(tag.textRange, replacement) else HtmlEdit(wholeLinesIfAlone(htmlText, tag.textRange), "")
        }

        return plannedFiles.mapIndexed { i, f -> if (i == 0) f.copy(edits = edits) else f }
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
