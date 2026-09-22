package dev.spaghetti.plan

import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory

/**
 * Splits a *standalone* `.js` file, but only in the one case that is actually safe: an ES module entry
 * point that nothing else imports symbols *from*.
 *
 * Two things must both be true, checked by looking at the file's own top-level statements (not a text
 * search, so a comment or string containing the word "export" doesn't count):
 *  - it has at least one top-level `import`, which means whatever loads it already uses
 *    `<script type="module">` (plain `<script>` can't contain `import` at all) — so we are not the ones
 *    deciding to change how it loads, that decision was already made.
 *  - it has **no** top-level `export`. If it exported anything, some other file might do
 *    `import { thing } from "./this.js"`, and splitting it into pieces re-assembled with `export *` can
 *    silently *drop* a name if two of the pieces happen to export the same identifier — that is exactly
 *    the kind of silent behavior change this plugin promises never to cause, so it declines instead.
 *
 * A file that fails either check is [Ineligible.Declined] with a human-readable reason — never a silent
 * no-op and never a guess.
 */
object JsFileSplitter {

    sealed interface Eligibility {
        data object Eligible : Eligibility
        data class Declined(val reason: String) : Eligibility
    }

    fun checkEligibility(jsFile: PsiFile, project: Project): Eligibility {
        val type = FileTypeManager.getInstance().getFileTypeByExtension("js")
        val parsed = PsiFileFactory.getInstance(project).createFileFromText("__untangle_check__.js", type, jsFile.text)
        val topLevel = parsed.children.map { it.text.trim() }

        val hasImport = topLevel.any { it.startsWith("import ") || it.startsWith("import\"") || it.startsWith("import'") }
        val hasExport = topLevel.any { it.startsWith("export ") }

        return when {
            hasExport -> Eligibility.Declined(
                "This file exports something, so another file may import symbols from it by name. Splitting it " +
                    "and re-exporting could silently drop a name if two pieces export the same identifier — left untouched.",
            )
            !hasImport -> Eligibility.Declined(
                "This is a classic script, not an ES module (no top-level import). There's no way to tell what " +
                    "else on the page loads it, so splitting it can't be done without possibly breaking that — left untouched.",
            )
            else -> Eligibility.Eligible
        }
    }

    /** Only call this after [checkEligibility] returned [Eligibility.Eligible]. */
    fun plan(jsFile: PsiFile, project: Project, exists: (String) -> Boolean = { false }): SplitPlan {
        val text = jsFile.text
        val baseName = jsFile.name.substringBeforeLast('.').lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "script" }

        val sections = CommentSections.mergeTiny(CommentSections.jsSections(text, project))
        if (sections.size <= 1) return SplitPlan(emptyList())

        val used = mutableSetOf<String>()
        val plannedFiles = sections.mapIndexed { i, section ->
            val name = section.name ?: "section-${i + 1}"
            val path = PlanPaths.uniquePath(baseName, name, "js", baseName, used, exists)
            val lines = section.text.lines().count { it.isNotBlank() }
            PlannedFile(path, section.text.trim('\n', '\r').trimEnd() + "\n", "$lines lines", listOf(), "js-file")
        }

        // Side-effect-only imports, in order: this re-runs each piece's top-level code exactly once, in the
        // same relative order as the original file, without re-exporting anything (there is nothing to
        // re-export — checkEligibility already ruled that case out).
        val replacement = plannedFiles.joinToString("\n") { """import "./${it.relativePath}";""" } + "\n"
        val edits = listOf(HtmlEdit(TextRange(0, text.length), replacement))

        return SplitPlan(plannedFiles.mapIndexed { i, f -> if (i == 0) f.copy(edits = edits) else f })
    }
}
