package dev.spaghetti.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.CheckBoxList
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import dev.spaghetti.plan.PlannedFile
import dev.spaghetti.plan.SplitPlan
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Shows what an untangle would create and lets the user untick a row to leave that code where it is.
 *
 * A row is one or more [PlannedFile]s that share a `groupId` (several files cut from one inline block —
 * see [PlannedFile.groupId]): they are ticked and unticked together, because writing only some of them
 * would either link to a file that was never created, or leave a copy of the code both inline and extracted.
 */
class PreviewDialog(project: Project, private val htmlName: String, plan: SplitPlan) : DialogWrapper(project) {

    private data class Row(val files: List<PlannedFile>) {
        val isGroup get() = files.size > 1
        val label get() = if (isGroup) {
            "${files.size} files: " + files.joinToString(", ") { it.relativePath.substringAfterLast('/') }
        } else {
            "${files[0].relativePath}   (${files[0].summary})"
        }
    }

    private val rows: List<Row> = plan.files.groupBy { it.groupId ?: it.relativePath }.values.map { Row(it) }
    private val list = CheckBoxList<Row>()

    init {
        title = "Untangle $htmlName"
        setOKButtonText("Untangle")
        rows.forEach { list.addItem(it, it.label, true) }
        init()
    }

    /** The plan with only the rows the user left ticked; a group survives only if it was left fully ticked. */
    fun selectedPlan(): SplitPlan = SplitPlan(rows.filter { list.isItemSelected(it) }.flatMap { it.files })

    override fun createCenterPanel(): JComponent {
        val header = JBLabel(
            "<html>These files will be created next to <b>$htmlName</b>. The code is moved as-is, " +
                "and the page keeps looking and behaving the same. Undo with Ctrl+Z.</html>",
        ).apply { border = JBUI.Borders.emptyBottom(8) }

        return JPanel(BorderLayout()).apply {
            add(header, BorderLayout.NORTH)
            add(JBScrollPane(list), BorderLayout.CENTER)
            preferredSize = Dimension(JBUI.scale(560), JBUI.scale(200))
        }
    }
}
