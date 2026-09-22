package dev.spaghetti

import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.xml.XmlFile
import com.intellij.testFramework.TestActionEvent
import com.intellij.util.ui.UIUtil
import dev.spaghetti.actions.UntangleAction
import dev.spaghetti.actions.UntangleFlow
import dev.spaghetti.plan.LightPlanner
import dev.spaghetti.plan.SplitPlan
import dev.spaghetti.ui.PreviewDialog
import dev.spaghetti.ui.StrategyPickerDialog
import dev.spaghetti.ui.UntangleResultDialog

/** Drives the same code the right-click menu runs, without the dialogs. */
class UntangleActionTest : SpaghettiTestCase() {

    private val actionId = "SpaghettiCode.Untangle"

    private fun enabledFor(file: VirtualFile): Boolean {
        val action = ActionManager.getInstance().getAction(actionId) as UntangleAction
        val context = SimpleDataContext.builder()
            .add(CommonDataKeys.PROJECT, project)
            .add(CommonDataKeys.VIRTUAL_FILE, file)
            .build()
        val event = TestActionEvent.createTestEvent(action, context)
        action.update(event)
        return event.presentation.isEnabledAndVisible
    }

    /** Runs the flow as if the user always picks Light at the first dialog, so existing behavior stays easy to test. */
    private fun runPickingLight(file: VirtualFile, choose: (SplitPlan) -> SplitPlan?) =
        UntangleFlow.run(project, file, pickStrategy = { _, _, _ -> UntangleFlow.Strategy.LIGHT }, choose = choose)

    // ---- the menu entry ------------------------------------------------------------------------

    fun testActionIsRegisteredInTheRightClickMenusAndToolsMenu() {
        val manager = ActionManager.getInstance()
        val action = manager.getAction(actionId)
        assertNotNull("action $actionId is not registered; plugin.xml is wrong", action)

        for (menu in listOf("ProjectViewPopupMenu", "EditorPopupMenu", "ToolsMenu")) {
            val group = manager.getAction(menu) as ActionGroup
            assertTrue("$menu should contain Untangle Spaghetti", group.getChildren(null).any { it === action })
        }
        assertEquals("Untangle Spaghetti", action.templatePresentation.text)
    }

    fun testMenuEntryShowsOnHtmlCssAndJsOnly() {
        val html = myFixture.configureByText("page.html", "<html></html>").virtualFile
        val js = myFixture.configureByText("app.js", "var x = 1;").virtualFile
        val css = myFixture.configureByText("site.css", "a { color: red }").virtualFile
        val py = myFixture.configureByText("script.py", "x = 1").virtualFile

        assertTrue(enabledFor(html))
        assertTrue(enabledFor(js))
        assertTrue(enabledFor(css))
        assertFalse(enabledFor(py))
    }

    // ---- what happens on click -----------------------------------------------------------------

    fun testCleanFileIsLeftAloneAndNeitherDialogOpens() {
        val text = """<html><head><link rel="stylesheet" href="a.css"></head><body><script src="a.js"></script></body></html>"""
        val file = myFixture.configureByText("clean.html", text).virtualFile

        val outcome = UntangleFlow.run(
            project,
            file,
            pickStrategy = { _, _, _ -> error("the strategy picker must not open for a clean file") },
            choose = { error("the preview must not open for a clean file") },
        )

        assertEquals(UntangleFlow.Outcome.Clean, outcome)
        assertEquals(text, String(file.contentsToByteArray()))
    }

    fun testCancellingAtTheStrategyPickerChangesNothing() {
        val file = myFixture.configureByFile("samples/nimbus/index.html").virtualFile
        val before = String(file.contentsToByteArray())

        val outcome = UntangleFlow.run(project, file, pickStrategy = { _, _, _ -> null }, choose = { error("must not be reached") })

        assertEquals(UntangleFlow.Outcome.Cancelled, outcome)
        assertEquals(before, String(file.contentsToByteArray()))
    }

    fun testCancellingAtThePreviewChangesNothing() {
        val file = myFixture.configureByFile("samples/nimbus/index.html").virtualFile
        val before = String(file.contentsToByteArray())

        val outcome = runPickingLight(file) { null }

        assertEquals(UntangleFlow.Outcome.Cancelled, outcome)
        assertEquals(before, String(file.contentsToByteArray()))
        assertNull(file.parent.findFileByRelativePath("css/styles.css"))
    }

    fun testUntickingEverythingCountsAsCancelling() {
        val file = myFixture.configureByFile("samples/nimbus/index.html").virtualFile

        val outcome = runPickingLight(file) { SplitPlan(emptyList()) }

        assertEquals(UntangleFlow.Outcome.Cancelled, outcome)
        assertNull(file.parent.findFileByRelativePath("css/styles.css"))
    }

    fun testAcceptingWritesFilesAndRewritesThePage() {
        val file = myFixture.configureByFile("samples/nimbus/index.html").virtualFile

        val outcome = runPickingLight(file) { it }

        val applied = outcome as UntangleFlow.Outcome.Applied
        assertEquals(5, applied.plan.files.size) // 3 cascade-safe css runs (a script splits them) + 2 scripts
        assertNotNull(file.parent.findFileByRelativePath("css/styles.css"))
        assertNotNull(file.parent.findFileByRelativePath("js/main-app.js"))
        assertTrue(String(file.contentsToByteArray()).contains("""<link rel="stylesheet" href="css/styles.css">"""))
    }

    // ---- all three strategies are actually offered and actually applied -------------------------

    fun testAllThreeStrategiesAreComputedAndDifferInSize() {
        val file = myFixture.configureByFile("samples/nimbus/index.html").virtualFile
        var seen: Triple<SplitPlan, SplitPlan, SplitPlan>? = null

        UntangleFlow.run(
            project,
            file,
            pickStrategy = { light, byFeature, byType -> seen = Triple(light, byFeature, byType); null },
            choose = { error("must not be reached") },
        )

        val (light, byFeature, byType) = seen!!
        assertEquals(5, light.files.size) // 3 cascade-safe css runs (a script splits them) + 2 scripts
        assertTrue("By feature should produce far more files than Light", byFeature.files.size > light.files.size)
        assertTrue("By type should also subdivide the file", byType.files.size > light.files.size)
        assertTrue(byType.files.any { it.relativePath == "css/variables.css" })
    }

    fun testPickingByFeatureAppliesTheByFeaturePlan() {
        val file = myFixture.configureByFile("samples/nimbus/index.html").virtualFile

        val outcome = UntangleFlow.run(
            project,
            file,
            pickStrategy = { _, _, _ -> UntangleFlow.Strategy.BY_FEATURE },
            choose = { it },
        )

        val applied = outcome as UntangleFlow.Outcome.Applied
        assertTrue(applied.plan.files.size > 3)
        assertNotNull(file.parent.findFileByRelativePath("css/nav.css"))
    }

    fun testPickingByTypeAppliesTheByTypePlan() {
        val file = myFixture.configureByFile("samples/nimbus/index.html").virtualFile

        val outcome = UntangleFlow.run(
            project,
            file,
            pickStrategy = { _, _, _ -> UntangleFlow.Strategy.BY_TYPE },
            choose = { it },
        )

        val applied = outcome as UntangleFlow.Outcome.Applied
        assertNotNull(file.parent.findFileByRelativePath("css/variables.css"))
        assertNotNull(file.parent.findFileByRelativePath("css/components/nav.css"))
    }

    // ---- standalone .css and .js dispatch through the same action -------------------------------

    fun testRightClickingAStandaloneCssFileSplitsItViaImport() {
        val css = """
            /* ---------- nav ---------- */
            ${(1..20).joinToString("\n") { "  .nav$it { color: red; }" }}
            /* ---------- hero ---------- */
            ${(1..20).joinToString("\n") { "  .hero$it { color: blue; }" }}
        """.trimIndent()
        val file = myFixture.configureByText("site.css", css).virtualFile

        val outcome = UntangleFlow.runCss(project, file) { it }

        val applied = outcome as UntangleFlow.Outcome.Applied
        assertEquals(listOf("site/nav.css", "site/hero.css"), applied.plan.files.map { it.relativePath })
        assertNotNull(file.parent.findFileByRelativePath("site/nav.css"))
        assertTrue(String(file.contentsToByteArray()).contains("""@import url("site/nav.css");"""))
    }

    fun testRightClickingAClassicStandaloneJsFileIsDeclinedWithAReasonNotSilentlyIgnored() {
        val js = (1..30).joinToString("\n") { "function f$it() { return $it; }" }
        val file = myFixture.configureByText("classic.js", js).virtualFile

        val outcome = UntangleFlow.runJs(project, file) { error("must not open a preview") }

        assertTrue(outcome is UntangleFlow.Outcome.Declined)
        assertEquals(js, String(file.contentsToByteArray())) // nothing touched
    }

    fun testRightClickingAnEligibleEntryPointJsFileSplitsItViaSideEffectImports() {
        val js = """
            import "./polyfill.js";
            // toasts
            ${(1..20).joinToString("\n") { "function toast$it() { return $it; }" }}
            // theme
            ${(1..20).joinToString("\n") { "function theme$it() { return $it; }" }}
        """.trimIndent()
        val file = myFixture.configureByText("main.js", js).virtualFile

        val outcome = UntangleFlow.runJs(project, file) { it }

        val applied = outcome as UntangleFlow.Outcome.Applied
        assertEquals(listOf("main/toasts.js", "main/theme.js"), applied.plan.files.map { it.relativePath })
        assertTrue(String(file.contentsToByteArray()).contains("""import "./main/toasts.js";"""))
    }

    // ---- undo ----------------------------------------------------------------------------------

    fun testOneUndoRestoresThePageAndRemovesEveryNewFile() {
        val file = myFixture.configureByFile("samples/nimbus/index.html").virtualFile
        val original = myFixture.editor.document.text
        val editor = TextEditorProvider.getInstance().getTextEditor(myFixture.editor)
        val undo = UndoManager.getInstance(project)

        runPickingLight(file) { it }
        assertFalse("the page should have changed", original == myFixture.editor.document.text)
        assertNotNull("css/styles.css should exist before undo", file.parent.findFileByRelativePath("css/styles.css"))
        assertNotNull("js/main-app.js should exist before undo", file.parent.findFileByRelativePath("js/main-app.js"))
        assertTrue("Undo should be available after untangling", undo.isUndoAvailable(editor))

        undo.undo(editor)
        UIUtil.dispatchAllInvocationEvents()

        assertEquals("one Ctrl+Z should put the page back exactly", original, myFixture.editor.document.text)
        assertNull("css/styles.css should be gone after undo", file.parent.findFileByRelativePath("css/styles.css"))
        assertNull("js/main-app.js should be gone after undo", file.parent.findFileByRelativePath("js/main-app.js"))
        assertNull("js/pricing-logic.js should be gone after undo", file.parent.findFileByRelativePath("js/pricing-logic.js"))
    }

    // ---- the dialogs -----------------------------------------------------------------------------

    fun testPreviewDialogStartsWithEveryRowTicked() {
        val file = myFixture.configureByFile("samples/nimbus/index.html")
        val plan = LightPlanner.plan(file as XmlFile)

        val dialog = PreviewDialog(project, "index.html", plan)
        try {
            assertEquals(plan.files.map { it.relativePath }, dialog.selectedPlan().files.map { it.relativePath })
        } finally {
            dialog.disposeIfNeeded()
        }
    }

    fun testStrategyPickerDefaultsToByFeature() {
        val file = myFixture.configureByFile("samples/nimbus/index.html")
        val exists: (String) -> Boolean = { false }
        val light = LightPlanner.plan(file as XmlFile, exists)
        val byFeature = dev.spaghetti.plan.FeaturePlanner.plan(file, project, exists)
        val byType = dev.spaghetti.plan.TypePlanner.plan(file, project, exists)

        val dialog = StrategyPickerDialog(project, "index.html", light, byFeature, byType)
        try {
            assertEquals(UntangleFlow.Strategy.BY_FEATURE, dialog.selectedStrategy())
        } finally {
            dialog.disposeIfNeeded()
        }
    }

    fun testResultDialogDefaultsToNotRequestingASummary() {
        val dialog = UntangleResultDialog(project, "Untangled index.html", "Created 3 file(s). Ctrl+Z undoes it.")
        try {
            assertFalse("closing any other way than the Summarize button must not trigger a summary", dialog.summarizeRequested)
        } finally {
            dialog.disposeIfNeeded()
        }
    }

    fun testResultDialogHasASummarizeActionAndAClose() {
        val dialog = UntangleResultDialog(project, "Untangled index.html", "Created 3 file(s). Ctrl+Z undoes it.")
        try {
            val labels = dialog.actionsForTest().map { it.getValue(javax.swing.Action.NAME) }
            assertTrue(labels.contains("Summarize with AI"))
            assertTrue(labels.contains("Close"))
        } finally {
            dialog.disposeIfNeeded()
        }
    }
}
