package dev.spaghetti.actions

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import dev.spaghetti.plan.SplitPlan
import dev.spaghetti.ui.PreviewDialog
import dev.spaghetti.ui.StrategyPickerDialog

/**
 * Right-click an `.html`, `.css` or `.js` file -> "Untangle Spaghetti". Which of the three it is
 * decides everything that follows — see [UntangleFlow.run], [UntangleFlow.runCss] and [UntangleFlow.runJs].
 */
class UntangleAction : AnAction() {

    private val supportedExtensions = setOf("html", "htm", "css", "js")

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        e.presentation.isEnabledAndVisible =
            e.project != null && file != null && !file.isDirectory && file.extension?.lowercase() in supportedExtensions
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val virtualFile = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return

        val outcome = when (virtualFile.extension?.lowercase()) {
            "html", "htm" -> UntangleFlow.run(
                project,
                virtualFile,
                pickStrategy = { light, byFeature, byType ->
                    val dialog = StrategyPickerDialog(project, virtualFile.name, light, byFeature, byType)
                    if (dialog.showAndGet()) dialog.selectedStrategy() else null
                },
                choose = previewAndChoose(project, virtualFile),
            )
            "css" -> UntangleFlow.runCss(project, virtualFile, previewAndChoose(project, virtualFile))
            "js" -> UntangleFlow.runJs(project, virtualFile, previewAndChoose(project, virtualFile))
            else -> return
        }

        when (outcome) {
            UntangleFlow.Outcome.Clean ->
                notify(project, "${virtualFile.name} looks clean", "Nothing big enough to split out. No changes made.")
            is UntangleFlow.Outcome.Declined ->
                notify(project, "Can't safely untangle ${virtualFile.name}", outcome.reason)
            UntangleFlow.Outcome.Cancelled -> Unit
            is UntangleFlow.Outcome.Applied ->
                notify(
                    project,
                    "Untangled ${virtualFile.name}",
                    "Created ${outcome.plan.files.size} file(s): ${outcome.plan.files.joinToString { it.relativePath }}. Ctrl+Z undoes it.",
                )
        }
    }

    private fun previewAndChoose(project: Project, virtualFile: VirtualFile): (SplitPlan) -> SplitPlan? =
        { plan ->
            val dialog = PreviewDialog(project, virtualFile.name, plan)
            if (dialog.showAndGet()) dialog.selectedPlan() else null
        }

    private fun notify(project: Project, title: String, content: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Spaghetti Code")
            .createNotification(title, content, NotificationType.INFORMATION)
            .notify(project)
    }
}
