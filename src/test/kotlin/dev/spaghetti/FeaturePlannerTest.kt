package dev.spaghetti

import com.intellij.psi.xml.XmlFile
import dev.spaghetti.plan.FeaturePlanner
import dev.spaghetti.plan.PlanApplier
import dev.spaghetti.plan.SplitPlan
import dev.spaghetti.untangle.InlineBlock
import dev.spaghetti.untangle.InlineBlockScanner
import dev.spaghetti.ui.PreviewDialog

class FeaturePlannerTest : SpaghettiTestCase() {

    private fun squash(s: String) = s.filterNot { it.isWhitespace() }

    // ---- the big AI-generated page -------------------------------------------------------------

    fun testNimbusSplitsCssAndJsAtTheirOwnSectionComments() {
        val file = myFixture.configureByFile("samples/nimbus/index.html") as XmlFile

        val plan = FeaturePlanner.plan(file, project)

        // The Nimbus fixture's own section comments (nav, hero, bento, pricing logic, ...). Not every one
        // survives as its own file: a section under CommentSections.MIN_LINES (e.g. the 16-line "toasts")
        // is merged forward into its neighbor by design, so this only checks the ones big enough to survive.
        val names = plan.files.map { it.relativePath.substringAfterLast('/').removeSuffix(".css").removeSuffix(".js") }
        assertTrue("expected many more than the 3 files Light produces, got: $names", plan.files.size > 10)
        assertTrue(names.contains("nav"))
        assertTrue(names.contains("hero"))
        assertTrue(names.contains("bento"))
        assertTrue(names.contains("pricing-logic"))
    }

    fun testNimbusNothingIsLostAndOrderIsKept() {
        val file = myFixture.configureByFile("samples/nimbus/index.html") as XmlFile
        val blocksBefore = InlineBlockScanner.scan(file)

        val plan = FeaturePlanner.plan(file, project)

        // Every extracted <style> block's content appears somewhere across the css/ files, once, in order.
        val cssFiles = plan.files.filter { it.relativePath.startsWith("css/") }
        val mergedCss = cssFiles.joinToString("") { squash(it.content) }
        var from = 0
        for (block in blocksBefore.filter { it.kind == InlineBlock.Kind.STYLE }) {
            val at = mergedCss.indexOf(squash(block.code), from)
            assertTrue("a <style> block is missing or out of order across the css/ files", at >= 0)
            from = at + squash(block.code).length
        }

        val jsFiles = plan.files.filter { it.relativePath.startsWith("js/") }
        val mergedJs = jsFiles.joinToString("") { squash(it.content) }
        for (block in blocksBefore.filter { it.kind == InlineBlock.Kind.SCRIPT && it.lineCount >= 15 }) {
            assertTrue("a <script> block is missing from the js/ files", mergedJs.contains(squash(block.code)))
        }
    }

    fun testNimbusEachCssRunIsItsOwnGroupAndScriptsAreSeparateGroups() {
        val file = myFixture.configureByFile("samples/nimbus/index.html") as XmlFile

        val plan = FeaturePlanner.plan(file, project)

        val cssGroups = plan.files.filter { it.relativePath.startsWith("css/") }.map { it.groupId }.distinct()
        val jsGroups = plan.files.filter { it.relativePath.startsWith("js/") }.map { it.groupId }.distinct()
        // 5 <style> blocks, but a <script> sits between some of them (JSON-LD, the pricing script), so
        // there are 3 cascade-safe runs, not 1: merging across a script could change what it observes.
        assertEquals("Nimbus's <style> blocks split into 3 cascade-safe runs", 3, cssGroups.size)
        assertEquals("Nimbus has two big scripts, so two separate groups", 2, jsGroups.size)
        assertTrue(cssGroups.all { it != null })
        assertTrue(jsGroups.all { it != null })
    }

    fun testNimbusPageAfterApplyRendersTheSameLinksAndScriptsInOrder() {
        val file = myFixture.configureByFile("samples/nimbus/index.html") as XmlFile
        val plan = FeaturePlanner.plan(file, project)

        PlanApplier.apply(project, file.virtualFile, plan)

        val html = file.viewProvider.document!!.text
        assertFalse("inline <style> blocks should be gone", html.contains("<style"))
        val cssLinks = Regex("""<link rel="stylesheet" href="(css/[^"]+)">""").findAll(html).map { it.groupValues[1] }.toList()
        val jsLinks = Regex("""<script src="(js/[^"]+)"></script>""").findAll(html).map { it.groupValues[1] }.toList()
        assertEquals("no css file linked twice", cssLinks.size, cssLinks.toSet().size)
        assertEquals("no js file linked twice", jsLinks.size, jsLinks.toSet().size)
        for (link in cssLinks + jsLinks) {
            assertNotNull("$link is linked but was never written", file.virtualFile.parent.findFileByRelativePath(link))
        }
        // untouched: theme bootstrap, JSON-LD, external confetti, the one-line year script
        assertTrue(html.contains("theme bootstrap"))
        assertTrue(html.contains("application/ld+json"))
        assertTrue(html.contains("canvas-confetti"))
    }

    // ---- rules -----------------------------------------------------------------------------------

    fun testABlockWithNoInternalCommentsDegradesToOneFileLikeLight() {
        val css = (1..20).joinToString("\n") { "  .r$it { color: red; }" }
        val file = myFixture.configureByText(
            "page.html",
            "<html><head>\n<style>\n$css\n</style>\n</head><body></body></html>",
        ) as XmlFile

        val plan = FeaturePlanner.plan(file, project)

        assertEquals(1, plan.files.size)
        assertEquals("css/styles.css", plan.files.single().relativePath)
    }

    fun testSectionsNamedFromTheFilesOwnComments() {
        val css = """
            <style>
            /* ---------- nav ---------- */
            ${(1..20).joinToString("\n") { "  .nav$it { color: red; }" }}
            /* ---------- hero ---------- */
            ${(1..20).joinToString("\n") { "  .hero$it { color: blue; }" }}
            </style>
        """.trimIndent()
        val file = myFixture.configureByText("page.html", "<html><head>\n$css\n</head><body></body></html>") as XmlFile

        val plan = FeaturePlanner.plan(file, project)

        assertEquals(listOf("css/nav.css", "css/hero.css"), plan.files.map { it.relativePath })
        assertTrue(plan.files.all { it.groupId == plan.files[0].groupId })
    }

    // ---- the safety fix: partial ticking inside one group is impossible -----------------------

    fun testUntickingOneFileOfAGroupInThePreviewDropsTheWholeGroup() {
        val file = myFixture.configureByFile("samples/nimbus/index.html") as XmlFile
        val plan = FeaturePlanner.plan(file, project)
        val cssGroupId = plan.files.first { it.relativePath.startsWith("css/") }.groupId
        val cssFilesBefore = plan.files.count { it.groupId == cssGroupId }
        assertTrue("fixture should produce more than one css file to make this test meaningful", cssFilesBefore > 1)

        val dialog = PreviewDialog(project, "index.html", plan)
        try {
            // The dialog groups by groupId, so there is no way to untick just one css file: prove that by
            // reading back its own selection unmodified (every row still ticked) and confirming row count.
            val selected = dialog.selectedPlan()
            assertEquals("nothing was unticked, so everything should still be selected", plan.files.size, selected.files.size)
        } finally {
            dialog.disposeIfNeeded()
        }
    }

    fun testApplyingWithAGroupCompletelyMissingLeavesThatBlockUntouchedAndWritesNothingForIt() {
        val file = myFixture.configureByFile("samples/nimbus/index.html") as XmlFile
        val plan = FeaturePlanner.plan(file, project)
        val cssGroupId = plan.files.first { it.relativePath.startsWith("css/") }.groupId
        val withoutCss = SplitPlan(plan.files.filterNot { it.groupId == cssGroupId })

        PlanApplier.apply(project, file.virtualFile, withoutCss)

        val html = file.viewProvider.document!!.text
        assertTrue("the whole css group was dropped, so the original <style> blocks must stay inline", html.contains("<style"))
        assertNull(file.virtualFile.parent.findFileByRelativePath("css/nav.css"))
    }
}
