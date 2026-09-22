package dev.spaghetti.ai

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JPasswordField

/** Asks for an OpenAI API key once; [ApiKeyStore] saves it (encrypted) so this never has to ask again. */
class ApiKeyDialog(project: Project) : DialogWrapper(project) {

    private val field = JPasswordField(32)

    init {
        title = "OpenAI API key"
        setOKButtonText("Save and summarize")
        init()
    }

    /** Null if the field was left empty when OK was pressed. */
    fun enteredKey(): String? = String(field.password).trim().ifEmpty { null }

    override fun createCenterPanel(): JComponent {
        val label = JBLabel(
            "<html>Needed once, to summarize files with OpenAI. Stored encrypted via IntelliJ's " +
                "PasswordSafe, never in a plain file, and only used when you click Summarize. " +
                "The rest of this plugin works without one.</html>",
        ).apply { border = JBUI.Borders.emptyBottom(8) }

        return JPanel(BorderLayout()).apply {
            add(label, BorderLayout.NORTH)
            add(field, BorderLayout.CENTER)
            preferredSize = Dimension(JBUI.scale(440), JBUI.scale(90))
        }
    }

    override fun getPreferredFocusedComponent(): JComponent = field
}
