package dev.spaghetti.plan

import com.intellij.openapi.util.TextRange

/** Replace [range] of the original HTML with [replacement]. An empty replacement deletes the range. */
data class HtmlEdit(val range: TextRange, val replacement: String)

/**
 * One new file the plan wants to create.
 *
 * [relativePath] is relative to the folder of the HTML file being untangled. [edits] are the changes
 * to the HTML that only make sense if this file is created (swap the inline block for a link to it),
 * so unticking a file in the preview also drops its edits.
 *
 * [groupId] is set when several files came from cutting up one inline block (see [FeaturePlanner]): the
 * block's HTML edit lives on only one of them, so the preview must tick or untick every file that shares
 * a [groupId] together — ticking some but not others would either 404 (a file linked but not written) or
 * silently keep a copy of the code inline as well as extracted (a file written but nothing removed from
 * the HTML). Files with no [groupId] (everything [LightPlanner] produces) are each their own group.
 */
data class PlannedFile(
    val relativePath: String,
    val content: String,
    val summary: String,
    val edits: List<HtmlEdit>,
    val groupId: String? = null,
)

/** Everything an untangle would do to one HTML file. */
data class SplitPlan(val files: List<PlannedFile>) {
    val isEmpty: Boolean get() = files.isEmpty()

    /** All HTML edits, in the order they must be applied (last offset first, so earlier offsets stay valid). */
    val edits: List<HtmlEdit> get() = files.flatMap { it.edits }.sortedByDescending { it.range.startOffset }
}
