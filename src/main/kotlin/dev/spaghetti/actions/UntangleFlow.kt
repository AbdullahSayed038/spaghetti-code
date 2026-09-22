package dev.spaghetti.actions

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.psi.xml.XmlFile
import dev.spaghetti.plan.CssFileSplitter
import dev.spaghetti.plan.FeaturePlanner
import dev.spaghetti.plan.JsFileSplitter
import dev.spaghetti.plan.LightPlanner
import dev.spaghetti.plan.PlanApplier
import dev.spaghetti.plan.SplitPlan
import dev.spaghetti.plan.TypePlanner

/**
 * Everything that happens after the user picks "Untangle Spaghetti", minus the dialogs.
 * The dialogs are passed in as callbacks, so tests can drive the exact same path without any UI.
 *
 * Three entry points, one per file kind the action supports — [run] for HTML (the flagship case, three
 * strategies to choose from), [runCss] and [runJs] for a standalone stylesheet or script (one strategy;
 * see [CssFileSplitter] and [JsFileSplitter] for why only some standalone JS is even safe to touch).
 */
object UntangleFlow {

    enum class Strategy { LIGHT, BY_FEATURE, BY_TYPE }

    sealed interface Outcome {
        /** The file has nothing big enough to split out; nothing was touched. */
        data object Clean : Outcome

        /** This specific file can't be split safely, and here is the human-readable reason why; nothing was touched. */
        data class Declined(val reason: String) : Outcome

        /** The user backed out of a dialog, or unticked everything; nothing was touched. */
        data object Cancelled : Outcome

        data class Applied(val plan: SplitPlan) : Outcome
    }

    /**
     * @param pickStrategy shown all three proposed plans and returns which one the user wants, or null to cancel.
     *   Not called at all if the file turns out clean, since there would be nothing to choose between.
     * @param choose shown the picked strategy's plan and returns the part the user accepted, or null to cancel.
     */
    fun run(
        project: Project,
        file: VirtualFile,
        pickStrategy: (light: SplitPlan, byFeature: SplitPlan, byType: SplitPlan) -> Strategy?,
        choose: (SplitPlan) -> SplitPlan?,
    ): Outcome {
        val htmlFile = PsiManager.getInstance(project).findFile(file) as? XmlFile ?: return Outcome.Clean
        val exists = existsIn(file)

        val light = LightPlanner.plan(htmlFile, exists)
        // By feature/type only ever subdivide the same blocks Light already found extractable, so if
        // Light has nothing, neither of them would either — no need to compute them just to find that out.
        if (light.isEmpty) return Outcome.Clean

        val byFeature = FeaturePlanner.plan(htmlFile, project, exists)
        val byType = TypePlanner.plan(htmlFile, project, exists)

        val strategy = pickStrategy(light, byFeature, byType) ?: return Outcome.Cancelled
        val plan = when (strategy) {
            Strategy.LIGHT -> light
            Strategy.BY_FEATURE -> byFeature
            Strategy.BY_TYPE -> byType
        }

        return applyChosen(project, file, plan, choose)
    }

    /** @param choose shown the proposed plan and returns the part the user accepted, or null to cancel. */
    fun runCss(project: Project, file: VirtualFile, choose: (SplitPlan) -> SplitPlan?): Outcome {
        val cssFile = PsiManager.getInstance(project).findFile(file) ?: return Outcome.Clean
        val plan = CssFileSplitter.plan(cssFile, project, existsIn(file))
        if (plan.isEmpty) return Outcome.Clean
        return applyChosen(project, file, plan, choose)
    }

    /** @param choose shown the proposed plan and returns the part the user accepted, or null to cancel. */
    fun runJs(project: Project, file: VirtualFile, choose: (SplitPlan) -> SplitPlan?): Outcome {
        val jsFile = PsiManager.getInstance(project).findFile(file) ?: return Outcome.Clean

        return when (val eligibility = JsFileSplitter.checkEligibility(jsFile, project)) {
            is JsFileSplitter.Eligibility.Declined -> Outcome.Declined(eligibility.reason)
            JsFileSplitter.Eligibility.Eligible -> {
                val plan = JsFileSplitter.plan(jsFile, project, existsIn(file))
                if (plan.isEmpty) return Outcome.Clean
                applyChosen(project, file, plan, choose)
            }
        }
    }

    private fun existsIn(file: VirtualFile): (String) -> Boolean {
        val folder = file.parent
        return { path -> folder?.findFileByRelativePath(path) != null }
    }

    private fun applyChosen(project: Project, file: VirtualFile, plan: SplitPlan, choose: (SplitPlan) -> SplitPlan?): Outcome {
        val chosen = choose(plan)?.takeUnless { it.isEmpty } ?: return Outcome.Cancelled
        PlanApplier.apply(project, file, chosen)
        return Outcome.Applied(chosen)
    }
}
