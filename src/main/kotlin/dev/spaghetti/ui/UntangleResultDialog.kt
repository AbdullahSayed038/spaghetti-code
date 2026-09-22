package dev.spaghetti.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.ActionEvent
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Shown centered on screen after a successful untangle, instead of a corner notification that fades
 * before it can be reliably clicked. Has its own "Summarize with AI" button alongside the default
 * Close, so the choice to spend a network call is a real, deliberate click, not a race against a balloon.
 */
class UntangleResultDialog(project: Project, resultTitle: String, private val message: String) : DialogWrapper(project) {

    /** True once this dialog closes, if the user picked Summarize with AI rather than Close. */
    var summarizeRequested = false
        private set

    init {
        title = resultTitle
        setOKButtonText("Close")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val label = JBLabel("<html>$message</html>").apply { border = JBUI.Borders.empty(4, 0) }
        return JPanel(BorderLayout()).apply {
            add(label, BorderLayout.CENTER)
            preferredSize = Dimension(JBUI.scale(460), JBUI.scale(90))
        }
    }

    override fun createActions(): Array<Action> {
        val summarize = object : DialogWrapperAction("Summarize with AI") {
            override fun doAction(e: ActionEvent?) {
                summarizeRequested = true
                close(OK_EXIT_CODE)
            }
        }
        return arrayOf(summarize, okAction)
    }

    /** Exposes the button set for tests; [createActions] itself is protected. */
    internal fun actionsForTest(): Array<Action> = createActions()
}
