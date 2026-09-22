package dev.spaghetti.plan

import com.intellij.openapi.project.Project
import com.intellij.psi.xml.XmlFile

/**
 * The "By feature" strategy: the same blocks [LightPlanner] would extract, but cut further at each
 * block's own top-level comments — `/* ---------- hero ---------- */`, `// toasts` — into one file per
 * named section. See [CommentSections] for why this can never change the cascade or execution order.
 *
 * A block with no useful internal comments (or where every section is too small once cut) degrades to
 * exactly what Light would have produced: one file for the whole block.
 */
object FeaturePlanner {

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
                classify = { section, i, total -> "css" to (section.name ?: if (total == 1) "styles" else "styles-${i + 1}") },
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
                    "js" to (section.name ?: if (total == 1) "script-${index + 1}" else "script-${index + 1}-${i + 1}")
                },
            )
        }

        return SplitPlan(files)
    }
}
