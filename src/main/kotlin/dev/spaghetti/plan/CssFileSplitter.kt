package dev.spaghetti.plan

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile

/**
 * Splits a *standalone* `.css` file — not one embedded in HTML — at its own top-level comments, the same
 * way [FeaturePlanner] splits an inline `<style>` block. The safe part is what replaces the original file:
 * a short list of `@import url("...");` statements, in the same order the sections were in.
 *
 * `@import` is why this is safe without touching anything else: it is real, standard CSS, it must be the
 * only thing before other rules (which is trivially true here, since it's *all* the file now contains), it
 * loads in the order written, and any `<link href="app.css">` anywhere keeps working unmodified, because
 * `app.css` still exists — it is just an index now. Nothing outside this one file needs to change.
 */
object CssFileSplitter {

    fun plan(cssFile: PsiFile, project: Project, exists: (String) -> Boolean = { false }): SplitPlan {
        val text = cssFile.text
        val baseName = cssFile.name.substringBeforeLast('.').lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "styles" }

        val sections = CommentSections.mergeTiny(CommentSections.cssSections(text, project))
        if (sections.size <= 1) return SplitPlan(emptyList()) // nothing to split a single already-whole file into

        val used = mutableSetOf<String>()
        val plannedFiles = sections.mapIndexed { i, section ->
            val name = section.name ?: "section-${i + 1}"
            val path = PlanPaths.uniquePath(baseName, name, "css", baseName, used, exists)
            val lines = section.text.lines().count { it.isNotBlank() }
            PlannedFile(path, section.text.trim('\n', '\r').trimEnd() + "\n", "$lines lines", listOf(), "css-file")
        }

        val replacement = plannedFiles.joinToString("\n") { """@import url("${it.relativePath}");""" } + "\n"
        val edits = listOf(HtmlEdit(TextRange(0, text.length), replacement))

        return SplitPlan(plannedFiles.mapIndexed { i, f -> if (i == 0) f.copy(edits = edits) else f })
    }
}
