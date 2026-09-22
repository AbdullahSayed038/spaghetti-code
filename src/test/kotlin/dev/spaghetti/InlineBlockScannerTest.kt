package dev.spaghetti

import com.intellij.psi.xml.XmlFile
import dev.spaghetti.untangle.InlineBlock
import dev.spaghetti.untangle.InlineBlockScanner

class InlineBlockScannerTest : SpaghettiTestCase() {

    fun testFindsInlineStylesAndScriptsInDocumentOrder() {
        val file = myFixture.configureByFile("samples/landing.html") as XmlFile

        val blocks = InlineBlockScanner.scan(file)

        assertEquals(
            listOf(InlineBlock.Kind.STYLE, InlineBlock.Kind.STYLE, InlineBlock.Kind.SCRIPT, InlineBlock.Kind.SCRIPT),
            blocks.map { it.kind },
        )
        assertTrue(blocks[0].code.contains(":root"))
        assertTrue(blocks[2].code.contains("function togglePricing()"))
    }

    fun testFindsEveryBlockInLargeAiGeneratedPage() {
        val file = myFixture.configureByFile("samples/nimbus/index.html") as XmlFile

        val blocks = InlineBlockScanner.scan(file)

        // 5 scattered <style> blocks; 4 inline scripts (JSON-LD and the external confetti script are skipped)
        assertEquals(5, blocks.count { it.kind == InlineBlock.Kind.STYLE })
        assertEquals(4, blocks.count { it.kind == InlineBlock.Kind.SCRIPT })
        assertTrue(blocks.sumOf { it.lineCount } > 2500)
    }

    fun testIgnoresExternalAndNonJavaScriptScripts() {
        val file = myFixture.configureByText(
            "page.html",
            """
            <html><head>
              <script src="app.js"></script>
              <script type="application/ld+json">{ "name": "x" }</script>
              <script type="text/template"><div>hi</div></script>
              <script type="module">import x from "./x.js";</script>
            </head><body></body></html>
            """.trimIndent(),
        ) as XmlFile

        val blocks = InlineBlockScanner.scan(file)

        assertEquals(1, blocks.size)
        assertTrue(blocks.single().code.contains("import x"))
    }

    fun testCleanFileHasNoBlocks() {
        val file = myFixture.configureByText(
            "clean.html",
            """<html><head><link rel="stylesheet" href="style.css"></head><body><script src="app.js"></script></body></html>""",
        ) as XmlFile

        assertEmpty(InlineBlockScanner.scan(file))
    }
}
