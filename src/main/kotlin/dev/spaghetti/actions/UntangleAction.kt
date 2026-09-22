package dev.spaghetti.actions

import com.intellij.ide.highlighter.HtmlFileType
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.Project
import dev.spaghetti.ui.PreviewDialog
import dev.spaghetti.ui.StrategyPickerDialog

/**
 * Right-click an HTML file -> "Untangle Spaghetti".
 *
 * Plans all three strategies, lets the user pick one, shows a preview, and applies the files they
 * leave ticked. Next: a standalone JavaScript/app.js file, not just inline `<script>` blocks.
 */
class UntangleAction : AnAction() {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        e.presentation.isEnabledAndVisible =
            e.project != null && file != null && !file.isDirectory && file.fileType == HtmlFileType.INSTANCE
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val virtualFile = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return

        val outcome = UntangleFlow.run(
            project,
            virtualFile,
            pickStrategy = { light, byFeature, byType ->
                val dialog = StrategyPickerDialog(project, virtualFile.name, light, byFeature, byType)
                if (dialog.showAndGet()) dialog.selectedStrategy() else null
            },
            choose = { plan ->
                val dialog = PreviewDialog(project, virtualFile.name, plan)
                if (dialog.showAndGet()) dialog.selectedPlan() else null
            },
        )

        when (outcome) {
            UntangleFlow.Outcome.Clean ->
                notify(project, "${virtualFile.name} looks clean", "Nothing big enough to split out. No changes made.")
            UntangleFlow.Outcome.Cancelled -> Unit
            is UntangleFlow.Outcome.Applied ->
                notify(
                    project,
                    "Untangled ${virtualFile.name}",
                    "Created ${outcome.plan.files.size} file(s): ${outcome.plan.files.joinToString { it.relativePath }}. Ctrl+Z undoes it.",
                )
        }
    }

    private fun notify(project: Project, title: String, content: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Spaghetti Code")
            .createNotification(title, content, NotificationType.INFORMATION)
            .notify(project)
    }
}
