package dev.spaghetti.plan

import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.css.CssMedia
import com.intellij.psi.css.CssRuleset
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlFile

/**
 * The "By type" strategy: the same [CommentSections] cut points as [FeaturePlanner] — so the same safety
 * guarantees apply, nothing is reordered — but each section is filed by what kind of code it is rather
 * than by its own name:
 *
 * - CSS: the file's own un-commented preamble (almost always `:root` custom properties and resets) becomes
 *   `css/variables.css`; a section that is nothing but `@media` rules becomes `css/responsive.css`;
 *   everything else goes in `css/components/<name>.css`.
 * - JS: the file's own un-commented preamble (top-level `var`/`let`/`const`, before any function runs)
 *   becomes `js/state.js`; everything else goes in `js/handlers/<name>.js`.
 *
 * This never merges sections that were not already adjacent — only Light-style "obviously separate"
 * regrouping across the *whole* file (say, every reset rule together regardless of where it sat) would
 * risk changing which rule wins a cascade tie, so this strategy does not attempt it.
 */
object TypePlanner {

    fun plan(file: XmlFile, project: Project, exists: (String) -> Boolean = { false }): SplitPlan {
        val page = file.name.substringBeforeLast('.').lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "page" }
        val used = mutableSetOf<String>()
        val files = mutableListOf<PlannedFile>()

        LightPlanner.findStyleRuns(file).forEachIndexed { runIndex, run ->
            files += BlockPlanner.plan(
                sections = CommentSections.mergeTiny(CommentSections.cssSections(LightPlanner.mergedStyleRunText(run), project)),
                ext = "css",
                page = page,
                used = used,
                exists = exists,
                wrapEdit = { path -> """<link rel="stylesheet" href="$path">""" },
                run = run,
                groupId = "css-$runIndex",
                classify = { section, i, total -> classifyCss(section, i, total, project) },
            )
        }

        LightPlanner.findExtractableScripts(file).forEachIndexed { index, tag ->
            files += BlockPlanner.plan(
                sections = CommentSections.mergeTiny(CommentSections.jsSections(tag.value.text, project)),
                ext = "js",
                page = page,
                used = used,
                exists = exists,
                wrapEdit = { path -> """<script src="$path"></script>""" },
                run = listOf(tag),
                groupId = "js-$index",
                classify = { section, i, total ->
                    if (i == 0 && section.name == null) {
                        "js" to "state"
                    } else {
                        "js/handlers" to (section.name ?: if (total == 1) "script-${index + 1}" else "script-${index + 1}-${i + 1}")
                    }
                },
            )
        }

        return SplitPlan(files)
    }

    private fun classifyCss(section: Section, i: Int, total: Int, project: Project): Pair<String, String> = when {
        i == 0 && section.name == null -> "css" to "variables"
        isOnlyMediaRules(section.text, project) -> "css" to "responsive"
        else -> "css/components" to (section.name ?: if (total == 1) "styles" else "component-${i + 1}")
    }

    /** True if every top-level rule in this CSS text is inside an `@media` block (no plain rule sits outside one). */
    private fun isOnlyMediaRules(text: String, project: Project): Boolean {
        val type = FileTypeManager.getInstance().getFileTypeByExtension("css")
        val file = PsiFileFactory.getInstance(project).createFileFromText("__untangle_temp__.css", type, text)
        val rulesets = PsiTreeUtil.findChildrenOfType(file, CssRuleset::class.java)
        if (rulesets.isEmpty()) return false // nothing recognizable as CSS at all; don't mislabel it "responsive"
        return rulesets.all { PsiTreeUtil.getParentOfType(it, CssMedia::class.java) != null }
    }
}
