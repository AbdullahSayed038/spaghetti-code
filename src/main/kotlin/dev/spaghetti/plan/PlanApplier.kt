package dev.spaghetti.plan

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager

/** Writes a [SplitPlan] to disk and rewrites the HTML, as a single undoable command. */
object PlanApplier {

    const val COMMAND_NAME = "Untangle Spaghetti"

    fun apply(project: Project, htmlFile: VirtualFile, plan: SplitPlan) {
        if (plan.isEmpty) return
        val baseDir = htmlFile.parent ?: return
        val document = FileDocumentManager.getInstance().getDocument(htmlFile) ?: return

        WriteCommandAction.writeCommandAction(project)
            .withName(COMMAND_NAME)
            .run<RuntimeException> {
                for (planned in plan.files) {
                    val dirPath = planned.relativePath.substringBeforeLast('/', "")
                    val dir = if (dirPath.isEmpty()) baseDir else VfsUtil.createDirectoryIfMissing(baseDir, dirPath)
                    val created = dir.createChildData(this, planned.relativePath.substringAfterLast('/'))
                    VfsUtil.saveText(created, planned.content)
                }
                // Highest offset first, so the offsets of the edits still to come stay valid.
                for (edit in plan.edits) {
                    document.replaceString(edit.range.startOffset, edit.range.endOffset, edit.replacement)
                }
                PsiDocumentManager.getInstance(project).commitDocument(document)
                FileDocumentManager.getInstance().saveDocument(document)
            }
    }
}
