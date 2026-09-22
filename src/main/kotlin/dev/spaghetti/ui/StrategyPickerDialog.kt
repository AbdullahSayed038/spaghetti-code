package dev.spaghetti.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import dev.spaghetti.actions.UntangleFlow.Strategy
import dev.spaghetti.plan.SplitPlan
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.ButtonGroup
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JRadioButton
import javax.swing.BoxLayout

/** Lets the user pick one of the three proposed layouts before seeing its file-by-file preview. */
class StrategyPickerDialog(
    project: Project,
    htmlName: String,
    light: SplitPlan,
    byFeature: SplitPlan,
    byType: SplitPlan,
) : DialogWrapper(project) {

    private data class Option(val strategy: Strategy, val emoji: String, val title: String, val description: String, val plan: SplitPlan)

    private val options = listOf(
        Option(Strategy.LIGHT, "🥄", "Light", "Only pull out what's obviously separate: one CSS file, one file per big script.", light),
        Option(
            Strategy.BY_FEATURE,
            "🍝",
            "By feature",
            "Split further at the file's own section comments, so each page feature gets its own file.",
            byFeature,
        ),
        Option(
            Strategy.BY_TYPE,
            "🗂️",
            "By type",
            "Same split as By feature, filed by kind instead: variables, responsive, components, state, handlers.",
            byType,
        ),
    )

    private val buttons = mutableMapOf<Strategy, JRadioButton>()

    init {
        title = "Untangle $htmlName"
        setOKButtonText("Next")
        init()
    }

    /** The strategy the user picked, or null if they cancelled. */
    fun selectedStrategy(): Strategy? = buttons.entries.firstOrNull { it.value.isSelected }?.key

    override fun createCenterPanel(): JComponent {
        val group = ButtonGroup()
        val panel = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }

        val header = JBLabel("<html>Pick a layout. All three move code as-is and change nothing about how the page behaves.</html>")
            .apply { border = JBUI.Borders.emptyBottom(8) }
        panel.add(header)

        for (option in options) {
            val lines = option.plan.files.sumOf { it.content.lines().count { l -> l.isNotBlank() } }
            val radio = JRadioButton(
                "<html><b>${option.emoji} ${option.title}</b> — ${option.plan.files.size} file(s), $lines lines" +
                    "<br><span style='color:gray'>${option.description}</span></html>",
                option.strategy == Strategy.BY_FEATURE,
            )
            radio.border = JBUI.Borders.empty(6, 4)
            group.add(radio)
            buttons[option.strategy] = radio
            panel.add(radio)
        }

        return JPanel(BorderLayout()).apply {
            add(panel, BorderLayout.CENTER)
            preferredSize = Dimension(JBUI.scale(520), JBUI.scale(260))
        }
    }
}
