package dev.spaghetti

import com.intellij.psi.PsiFile
import dev.spaghetti.plan.CssFileSplitter
import dev.spaghetti.plan.PlanApplier

class CssFileSplitterTest : SpaghettiTestCase() {

    private fun squash(s: String) = s.filterNot { it.isWhitespace() }
    private fun rules(n: Int, prefix: String) = (1..n).joinToString("\n") { "  .$prefix$it { color: red; }" }

    private fun css(name: String, text: String): PsiFile = myFixture.configureByText(name, text)

    fun testOneUncommentedSectionIsCleanNotAnEmptySplit() {
        val file = css("site.css", rules(30, "a"))

        val plan = CssFileSplitter.plan(file, project)

        assertTrue(plan.isEmpty)
    }

    fun testSplitsIntoOneFilePerSectionAndRewritesTheOriginalAsImports() {
        val text = """
            /* ---------- nav ---------- */
            ${rules(20, "nav")}
            /* ---------- hero ---------- */
            ${rules(20, "hero")}
        """.trimIndent()
        val file = css("app.css", text)

        val plan = CssFileSplitter.plan(file, project)

        assertEquals(listOf("app/nav.css", "app/hero.css"), plan.files.map { it.relativePath })
        assertTrue(plan.files.all { it.groupId == "css-file" })

        PlanApplier.apply(project, file.virtualFile, plan)
        val rewritten = file.viewProvider.document!!.text
        assertEquals(
            "@import url(\"app/nav.css\");\n@import url(\"app/hero.css\");\n",
            rewritten,
        )
        assertNotNull(file.virtualFile.parent.findFileByRelativePath("app/nav.css"))
        assertNotNull(file.virtualFile.parent.findFileByRelativePath("app/hero.css"))
    }

    fun testImportOrderMatchesOriginalSectionOrder() {
        // @import must load before any other rule and in written order; confirm the plan preserves that order.
        val text = """
            /* ---------- zulu-first ---------- */
            ${rules(20, "z")}
            /* ---------- bravo-second ---------- */
            ${rules(20, "b")}
        """.trimIndent()
        val file = css("order.css", text)

        val plan = CssFileSplitter.plan(file, project)
        PlanApplier.apply(project, file.virtualFile, plan)

        val rewritten = file.viewProvider.document!!.text
        val firstImport = rewritten.indexOf("order/zulu-first.css")
        val secondImport = rewritten.indexOf("order/bravo-second.css")
        assertTrue(firstImport >= 0 && secondImport > firstImport)
    }

    fun testNothingIsLostAcrossTheSplitFiles() {
        val text = """
            /* ---------- nav ---------- */
            ${rules(20, "nav")}
            /* ---------- hero ---------- */
            ${rules(20, "hero")}
            /* ---------- footer ---------- */
            ${rules(20, "footer")}
        """.trimIndent()
        val file = css("full.css", text)

        val plan = CssFileSplitter.plan(file, project)
        val merged = plan.files.joinToString("") { squash(it.content) }

        assertEquals(squash(text), merged)
    }

    fun testExistingFileAtTheTargetPathIsNeverOverwritten() {
        val text = """
            /* ---------- nav ---------- */
            ${rules(20, "nav")}
            /* ---------- hero ---------- */
            ${rules(20, "hero")}
        """.trimIndent()
        val file = css("app2.css", text)

        val plan = CssFileSplitter.plan(file, project) { it == "app2/nav.css" }

        assertFalse(plan.files.any { it.relativePath == "app2/nav.css" })
        assertEquals(2, plan.files.size)
    }
}
