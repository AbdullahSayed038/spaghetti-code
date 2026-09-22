package dev.spaghetti.ai

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import dev.spaghetti.plan.PlannedFile

/**
 * Wires [SummarizeFlow] up to the real world: the API key (prompting once if needed), a background
 * task so the UI never freezes on the network call, and a `MANIFEST.md` written next to the files an
 * untangle just created. Entirely optional and additive -- called only when the user clicks
 * "Summarize with AI" on the notification after a successful untangle; the untangle itself already
 * finished and cannot be affected by anything that happens here, success or failure.
 */
object SummarizeCommand {

    private const val MANIFEST_NAME = "MANIFEST.md"

    fun run(project: Project, outputDir: VirtualFile, sourceFileName: String, files: List<PlannedFile>) {
        val apiKey = ApiKeyStore.get() ?: promptForKey(project) ?: return

        object : Task.Backgroundable(project, "Summarizing untangled files with OpenAI") {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                val pairs = files.map { it.relativePath to it.content }
                val outcome = SummarizeFlow.run(sourceFileName, pairs) { system, user -> OpenAiClient.complete(apiKey, system, user) }
                when (outcome) {
                    is SummarizeFlow.Outcome.Success -> onSuccess(project, outputDir, outcome)
                    is SummarizeFlow.Outcome.Failed -> notify(project, "Couldn't summarize files", outcome.reason, NotificationType.WARNING)
                }
            }
        }.queue()
    }

    /** Shows the key dialog and, if one was entered, saves it for next time. Null means the user cancelled. */
    private fun promptForKey(project: Project): String? {
        val dialog = ApiKeyDialog(project)
        if (!dialog.showAndGet()) return null
        val key = dialog.enteredKey() ?: return null
        ApiKeyStore.save(key)
        return key
    }

    private fun onSuccess(project: Project, outputDir: VirtualFile, outcome: SummarizeFlow.Outcome.Success) {
        var manifestFile: VirtualFile? = null
        ApplicationManager.getApplication().invokeAndWait {
            WriteCommandAction.writeCommandAction(project)
                .withName("Write untangle summary")
                .run<RuntimeException> {
                    val file = outputDir.findChild(MANIFEST_NAME) ?: outputDir.createChildData(this, MANIFEST_NAME)
                    VfsUtil.saveText(file, outcome.manifestContent)
                    manifestFile = file
                }
        }

        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup("Spaghetti Code")
            .createNotification(
                "Summarized ${outcome.summaries.size} file(s)",
                "Wrote $MANIFEST_NAME next to the untangled files.",
                NotificationType.INFORMATION,
            )
        manifestFile?.let { file ->
            notification.addAction(
                NotificationAction.createSimple("Open $MANIFEST_NAME") {
                    FileEditorManager.getInstance(project).openFile(file, true)
                },
            )
        }
        notification.notify(project)
    }

    private fun notify(project: Project, title: String, content: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Spaghetti Code")
            .createNotification(title, content, type)
            .notify(project)
    }
}
