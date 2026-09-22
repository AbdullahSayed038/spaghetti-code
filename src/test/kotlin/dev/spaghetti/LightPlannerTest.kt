package dev.spaghetti

import com.intellij.psi.xml.XmlFile
import dev.spaghetti.plan.LightPlanner
import dev.spaghetti.plan.PlanApplier
import dev.spaghetti.plan.SplitPlan
import dev.spaghetti.untangle.InlineBlock
import dev.spaghetti.untangle.InlineBlockScanner

class LightPlannerTest : SpaghettiTestCase() {

    private fun css(n: Int, prefix: String) = (1..n).joinToString("\n") { "  .$prefix$it { color: red; }" }
    private fun js(n: Int, prefix: String) = (1..n).joinToString("\n") { "  var $prefix$it = $it;" }
    private fun squash(s: String) = s.filterNot { it.isWhitespace() }

    private fun plan(name: String, html: String): Pair<XmlFile, SplitPlan> {
        val file = myFixture.configureByText(name, html) as XmlFile
        return file to LightPlanner.plan(file)
    }

    // ---- the big AI-generated page -------------------------------------------------------------

    fun testNimbusMergesFiveStyleBlocksAndExtractsTwoBigScripts() {
        val file = myFixture.configureByFile("samples/nimbus/index.html") as XmlFile

        val plan = LightPlanner.plan(file)

        assertEquals(
            listOf("css/styles.css", "js/pricing-logic.js", "js/main-app.js"),
            plan.files.map { it.relativePath },
        )
    }

    fun testNimbusNothingIsLostAndOrderIsKept() {
        val file = myFixture.configureByFile("samples/nimbus/index.html") as XmlFile
        val blocks = InlineBlockScanner.scan(file)
        val plan = LightPlanner.plan(file)

        // Every extracted <style> block appears in styles.css, once, in the original order.
        val mergedCss = squash(plan.files.first { it.relativePath == "css/styles.css" }.content)
        var from = 0
        for (block in blocks.filter { it.kind == InlineBlock.Kind.STYLE }) {
            val at = mergedCss.indexOf(squash(block.code), from)
            assertTrue("a <style> block is missing or out of order in styles.css", at >= 0)
            from = at + squash(block.code).length
        }
        assertEquals("styles.css contains something that was not in the page", from, mergedCss.length)

        // Each extracted script is byte-for-byte the original code (only outer blank lines trimmed).
        for (path in listOf("js/pricing-logic.js", "js/main-app.js")) {
            val content = plan.files.first { it.relativePath == path }.content
            assertTrue(blocks.any { it.kind == InlineBlock.Kind.SCRIPT && it.code.trim() == content.trim() })
        }
    }

    fun testNimbusPageAfterApplyKeepsScriptOrderAndLeavesSmallThingsAlone() {
        val file = myFixture.configureByFile("samples/nimbus/index.html") as XmlFile
        val plan = LightPlanner.plan(file)

        PlanApplier.apply(project, file.virtualFile, plan)

        val html = file.viewProvider.document!!.text
        assertFalse("inline <style> blocks should be gone", html.contains("<style"))
        // Same six scripts, same order. Only the pricing script (mid-page) and the main script were swapped for src=.
        assertEquals(
            listOf(
                "<script>",                                                       // theme bootstrap, stays inline
                """<script type="application/ld+json">""",                        // JSON-LD, untouched
                """<script src="js/pricing-logic.js">""",                         // was inline, mid-page
                """<script src="https://cdn.jsdelivr.net/npm/canvas-confetti@1.9.3/dist/confetti.browser.min.js">""",
                """<script src="js/main-app.js">""",                               // was inline, right after confetti
                "<script>",                                                       // one-line year script, stays inline
            ),
            scriptTags(html),
        )
        assertTrue(html.contains("""<link rel="stylesheet" href="css/styles.css">"""))
        assertTrue(html.contains("""<script src="js/pricing-logic.js"></script>"""))
        assertTrue(html.contains("""<script src="js/main-app.js"></script>"""))
        // untouched: theme bootstrap, JSON-LD, external confetti, the one-line year script
        assertTrue(html.contains("theme bootstrap"))
        assertTrue(html.contains("application/ld+json"))
        assertTrue(html.contains("canvas-confetti"))
        assertTrue(html.contains("getElementById(\"year\")"))
        // and the new files really exist next to the page
        val pageDir = file.virtualFile.parent
        val css = pageDir.findFileByRelativePath("css/styles.css")
        val mainJs = pageDir.findFileByRelativePath("js/main-app.js")
        assertNotNull("css/styles.css should be created next to the page (${pageDir.path})", css)
        assertNotNull("js/main-app.js should be created next to the page", mainJs)
        assertEquals(plan.files.first { it.relativePath == "css/styles.css" }.content, String(css!!.contentsToByteArray()))
    }

    // ---- rules ---------------------------------------------------------------------------------

    fun testStylesheetLinkBetweenBlocksSplitsTheCascadeIntoTwoFiles() {
        val (_, plan) = plan(
            "page.html",
            """
            <html><head>
            <style>
            ${css(20, "a")}
            </style>
            <link rel="stylesheet" href="theme.css">
            <style>
            ${css(20, "b")}
            </style>
            </head><body></body></html>
            """.trimIndent(),
        )

        assertEquals(listOf("css/styles.css", "css/styles-2.css"), plan.files.map { it.relativePath })
        assertTrue(plan.files[0].content.contains(".a1 ") && !plan.files[0].content.contains(".b1 "))
    }

    fun testSmallStyleBetweenBlocksAlsoSplitsTheCascade() {
        val (_, plan) = plan(
            "page.html",
            """
            <html><head>
            <style>
            ${css(20, "a")}
            </style>
            <style>.tiny { color: blue; }</style>
            <style>
            ${css(20, "b")}
            </style>
            </head><body></body></html>
            """.trimIndent(),
        )

        assertEquals(2, plan.files.size)
    }

    fun testBlocksWithImportOrMediaAttributeAreLeftInline() {
        val (_, plan) = plan(
            "page.html",
            """
            <html><head>
            <style>
            @import url("x.css");
            ${css(20, "a")}
            </style>
            <style media="print">
            ${css(20, "b")}
            </style>
            </head><body></body></html>
            """.trimIndent(),
        )

        assertTrue(plan.isEmpty)
    }

    fun testModuleScriptsAndSmallScriptsAreLeftInline() {
        val (_, plan) = plan(
            "page.html",
            """
            <html><body>
            <script type="module">
            ${js(30, "m")}
            </script>
            <script>
            ${js(5, "s")}
            </script>
            </body></html>
            """.trimIndent(),
        )

        assertTrue(plan.isEmpty)
    }

    fun testScriptIsNamedAfterItsLeadingCommentOtherwiseNumbered() {
        val (_, plan) = plan(
            "page.html",
            """
            <html><body>
            <script>
            // Cart logic (needs the markup)
            ${js(20, "c")}
            </script>
            <script>
            ${js(20, "d")}
            </script>
            </body></html>
            """.trimIndent(),
        )

        assertEquals(listOf("js/cart-logic.js", "js/script-2.js"), plan.files.map { it.relativePath })
    }

    fun testExistingFilesAreNeverOverwritten() {
        val file = myFixture.configureByText(
            "page.html",
            """
            <html><head>
            <style>
            ${css(20, "a")}
            </style>
            </head><body></body></html>
            """.trimIndent(),
        ) as XmlFile

        val plan = LightPlanner.plan(file) { it == "css/styles.css" }

        assertEquals("css/page-styles.css", plan.files.single().relativePath)
    }

    fun testUntickingAFileDropsItsEditsToo() {
        val (file, plan) = plan(
            "page.html",
            """
            <html><head>
            <style>
            ${css(20, "a")}
            </style>
            </head><body>
            <script>
            ${js(20, "c")}
            </script>
            </body></html>
            """.trimIndent(),
        )

        val onlyCss = SplitPlan(plan.files.filter { it.relativePath.endsWith(".css") })
        PlanApplier.apply(project, file.virtualFile, onlyCss)

        val html = file.viewProvider.document!!.text
        assertFalse(html.contains("<style"))
        assertTrue("the script was unticked so it must stay inline", html.contains("var c1 = 1;"))
    }

    fun testMovedStyleBlocksLeaveNoBlankGapBehind() {
        val (file, plan) = plan(
            "page.html",
            "<html><head>\n  <style>\n${css(20, "a")}\n  </style>\n  <title>x</title>\n  <style>\n${css(20, "b")}\n  </style>\n</head><body></body></html>",
        )

        PlanApplier.apply(project, file.virtualFile, plan)

        assertEquals(
            "<html><head>\n  <link rel=\"stylesheet\" href=\"css/styles.css\">\n  <title>x</title>\n</head><body></body></html>",
            file.viewProvider.document!!.text,
        )
    }

    private fun scriptTags(html: String): List<String> =
        Regex("<script[^>]*>").findAll(html).map { it.value }.toList()
}
