package dev.spaghetti.plan

import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.css.CssBlock
import com.intellij.psi.util.PsiTreeUtil

/** A named chunk of a larger CSS or JS text, cut at a top-level comment. [name] is null for the text before the first comment. */
internal data class Section(val name: String?, val text: String)

/**
 * Splits a big CSS or JS text into [Section]s at its own top-level `/* ... */` or `//` comments — the
 * section boundaries an AI tool (or a person) already wrote into the file, such as `/* ---------- hero
 * ---------- */`. Splitting only at these points, and only re-emitting the exact same text in the exact
 * same order across several files, cannot change the cascade or execution order: a run of `<link>` or
 * `<script src>` tags in that order behaves exactly like the one block they replace.
 *
 * A comment nested inside a rule body or a function body is not a section boundary; only the ones a
 * human would call "top level" are used.
 */
internal object CommentSections {

    /** Merge a section shorter than this into its neighbor: extracting it costs more clarity than it buys. */
    const val MIN_LINES = 15

    fun cssSections(text: String, project: Project): List<Section> {
        val type = FileTypeManager.getInstance().getFileTypeByExtension("css")
        val file = PsiFileFactory.getInstance(project).createFileFromText("__untangle_temp__.css", type, text)
        // findChildrenOfType walks every comment in the file; only the ones with no rule-body ancestor are top level.
        val topLevelComments = PsiTreeUtil.findChildrenOfType(file, PsiComment::class.java)
            .filter { PsiTreeUtil.getParentOfType(it, CssBlock::class.java) == null }
            .sortedBy { it.textRange.startOffset }
        return cut(text, topLevelComments.map { it.textRange to it.text })
    }

    fun jsSections(text: String, project: Project): List<Section> {
        val type = FileTypeManager.getInstance().getFileTypeByExtension("js")
        val file = PsiFileFactory.getInstance(project).createFileFromText("__untangle_temp__.js", type, text)
        // Direct children of the file are exactly the top-level statements and comments, in order; a comment
        // inside a function body is a child of that function's block, not of the file, so it is excluded for free.
        val topLevelComments = file.children.filterIsInstance<PsiComment>().sortedBy { it.textRange.startOffset }
        return cut(text, topLevelComments.map { it.textRange to it.text })
    }

    /**
     * Partitions [text] at each comment's own start offset, so a section runs from its comment (inclusive)
     * up to the next one — the comment itself is part of the section's content, never dropped.
     */
    private fun cut(text: String, comments: List<Pair<TextRange, String>>): List<Section> {
        if (comments.isEmpty()) return listOf(Section(null, text)).filter { it.text.isNotBlank() }

        val starts = listOf(0) + comments.map { it.first.startOffset }
        val names = listOf<String?>(null) + comments.map { LightPlanner.slugFromCommentText(it.second) }
        val sections = starts.indices.map { i ->
            val end = if (i + 1 < starts.size) starts[i + 1] else text.length
            Section(names[i], text.substring(starts[i], end))
        }
        return sections.filter { it.text.isNotBlank() }
    }

    /**
     * Merges a section under [MIN_LINES] lines into its neighbor (forward, or backward if it is the last),
     * so untangling never scatters a handful of one-off lines into their own file. Content is never lost —
     * only the merged-away section's own name is; the file takes the name of the bigger section it joins.
     */
    fun mergeTiny(sections: List<Section>): List<Section> {
        if (sections.size <= 1) return sections
        val result = mutableListOf<Section>()
        var pending: Section? = null
        for (section in sections) {
            val merged = if (pending == null) section else Section(section.name ?: pending.name, pending.text + "\n\n" + section.text)
            if (lineCount(merged.text) < MIN_LINES) {
                pending = merged
            } else {
                result += merged
                pending = null
            }
        }
        if (pending != null) {
            if (result.isEmpty()) {
                result += pending
            } else {
                val last = result.removeAt(result.lastIndex)
                result += Section(last.name, last.text + "\n\n" + pending.text)
            }
        }
        return result
    }

    private fun lineCount(text: String) = text.lines().count { it.isNotBlank() }
}
