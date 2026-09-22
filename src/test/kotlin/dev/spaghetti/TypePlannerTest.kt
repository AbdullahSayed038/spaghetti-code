package dev.spaghetti

import com.intellij.psi.xml.XmlFile
import dev.spaghetti.plan.TypePlanner
import dev.spaghetti.plan.PlanApplier
import dev.spaghetti.untangle.InlineBlock
import dev.spaghetti.untangle.InlineBlockScanner

class TypePlannerTest : SpaghettiTestCase() {

    private fun squash(s: String) = s.filterNot { it.isWhitespace() }
    private fun rules(n: Int, prefix: String) = (1..n).joinToString("\n") { "  .$prefix$it { color: red; }" }

    // ---- CSS classification ---------------------------------------------------------------------

    fun testLeadingUncommentedPreambleBecomesVariables() {
        val css = """
            <style>
            :root { --a: 1; }
            ${rules(20, "reset")}
            /* ---------- nav ---------- */
            ${rules(20, "nav")}
            </style>
        """.trimIndent()
        val file = myFixture.configureByText("page.html", "<html><head>\n$css\n</head><body></body></html>") as XmlFile

        val plan = TypePlanner.plan(file, project)

        assertEquals(listOf("css/variables.css", "css/components/nav.css"), plan.files.map { it.relativePath })
        assertTrue(plan.files[0].content.contains(":root"))
    }

    fun testSectionThatIsOnlyMediaRulesBecomesResponsive() {
        val css = """
            <style>
            /* ---------- nav ---------- */
            ${rules(20, "nav")}
            /* responsive */
            @media (max-width: 768px) {
              ${rules(10, "m1")}
            }
            @media (max-width: 480px) {
              ${rules(10, "m2")}
            }
            </style>
        """.trimIndent()
        val file = myFixture.configureByText("page.html", "<html><head>\n$css\n</head><body></body></html>") as XmlFile

        val plan = TypePlanner.plan(file, project)

        assertEquals(listOf("css/components/nav.css", "css/responsive.css"), plan.files.map { it.relativePath })
    }

    fun testASectionWithBothPlainAndMediaRulesIsNotResponsive() {
        val css = """
            <style>
            /* ---------- mixed ---------- */
            ${rules(20, "mixed")}
            @media (max-width: 768px) {
              ${rules(5, "m")}
            }
            </style>
        """.trimIndent()
        val file = myFixture.configureByText("page.html", "<html><head>\n$css\n</head><body></body></html>") as XmlFile

        val plan = TypePlanner.plan(file, project)

        assertEquals(listOf("css/components/mixed.css"), plan.files.map { it.relativePath })
    }

    fun testOrdinaryFeatureSectionsGoUnderComponents() {
        val css = """
            <style>
            /* ---------- hero ---------- */
            ${rules(20, "hero")}
            /* ---------- pricing ---------- */
            ${rules(20, "pricing")}
            </style>
        """.trimIndent()
        val file = myFixture.configureByText("page.html", "<html><head>\n$css\n</head><body></body></html>") as XmlFile

        val plan = TypePlanner.plan(file, project)

        assertEquals(listOf("css/components/hero.css", "css/components/pricing.css"), plan.files.map { it.relativePath })
    }

    // ---- JS classification -----------------------------------------------------------------------

    fun testLeadingUncommentedJsPreambleBecomesState() {
        val js = (1..20).joinToString("\n") { "  var STATE_$it = $it;" } +
            "\n\n// toasts\n" + (1..20).joinToString("\n") { "  function toast$it() { console.log($it); }" }
        val file = myFixture.configureByText("page.html", "<html><body>\n<script>\n$js\n</script>\n</body></html>") as XmlFile

        val plan = TypePlanner.plan(file, project)

        assertEquals(listOf("js/state.js", "js/handlers/toasts.js"), plan.files.map { it.relativePath })
    }

    // ---- shared safety: nothing lost, order kept, on the real AI-generated page ------------------

    fun testNimbusNothingIsLostAndOrderIsKept() {
        val file = myFixture.configureByFile("samples/nimbus/index.html") as XmlFile
        val blocksBefore = InlineBlockScanner.scan(file)

        val plan = TypePlanner.plan(file, project)

        val mergedCss = plan.files.filter { it.relativePath.startsWith("css/") }.joinToString("") { squash(it.content) }
        var from = 0
        for (block in blocksBefore.filter { it.kind == InlineBlock.Kind.STYLE }) {
            val at = mergedCss.indexOf(squash(block.code), from)
            assertTrue("a <style> block is missing or out of order across the css/ files", at >= 0)
            from = at + squash(block.code).length
        }
    }

    fun testNimbusAfterApplyEveryLinkedFileWasActuallyWritten() {
        val file = myFixture.configureByFile("samples/nimbus/index.html") as XmlFile
        val plan = TypePlanner.plan(file, project)

        PlanApplier.apply(project, file.virtualFile, plan)

        val html = file.viewProvider.document!!.text
        val links = Regex("""(?:href|src)="((?:css|js)/[^"]+)"""").findAll(html).map { it.groupValues[1] }.toList()
        assertTrue(links.isNotEmpty())
        for (link in links) {
            assertNotNull("$link is linked but was never written", file.virtualFile.parent.findFileByRelativePath(link))
        }
    }

    fun testGroupingIsStillAllOrNothing() {
        val file = myFixture.configureByFile("samples/nimbus/index.html") as XmlFile
        val plan = TypePlanner.plan(file, project)

        val cssGroupId = plan.files.first { it.relativePath.startsWith("css/") }.groupId
        assertNotNull(cssGroupId)
        assertTrue(plan.files.filter { it.relativePath.startsWith("css/") }.all { it.groupId == cssGroupId })
    }
}
