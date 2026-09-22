package dev.spaghetti

import com.intellij.psi.xml.XmlFile
import dev.spaghetti.actions.UntangleFlow
import dev.spaghetti.plan.FeaturePlanner
import dev.spaghetti.plan.LightPlanner
import dev.spaghetti.plan.PlanApplier
import dev.spaghetti.plan.TypePlanner
import dev.spaghetti.untangle.InlineBlock
import dev.spaghetti.untangle.InlineBlockScanner

/**
 * Ten different `index.html` patterns real AI tools produce, run through all three strategies.
 * Each test prints a one-line summary; failures point at exactly which page/strategy broke.
 */
class TenPagesRobustnessTest : SpaghettiTestCase() {

    private fun squash(s: String) = s.filterNot { it.isWhitespace() }

    /**
     * Runs Light, By feature and By type on [html]; asserts none crash and none lose or reorder any
     * extractable classic-JS/CSS block (a `type="module"` script is never extracted by design, so it is
     * excluded from the "must survive" check). Applies By feature once and hands the resulting HTML to
     * [postApply] for any extra assertions, so callers never need a second apply on the same virtual file.
     */
    private fun checkAllThreeStrategies(label: String, html: String, minExpectedFiles: Int = 1, postApply: (String) -> Unit = {}) {
        val file = myFixture.configureByText("$label.html", html) as XmlFile
        val blocksBefore = InlineBlockScanner.scan(file)
        // Only classic <script> text is ever extracted (type="module" etc. is intentionally left inline).
        val classicScriptTexts = LightPlanner.findExtractableScripts(file).map { it.value.text }
        val exists: (String) -> Boolean = { false }

        val light = LightPlanner.plan(file, exists)
        val byFeature = FeaturePlanner.plan(file, project, exists)
        val byType = TypePlanner.plan(file, project, exists)

        for ((name, plan) in listOf("Light" to light, "By feature" to byFeature, "By type" to byType)) {
            val mergedCss = plan.files.filter { it.relativePath.startsWith("css/") }.joinToString("") { squash(it.content) }
            val mergedJs = plan.files.filter { it.relativePath.startsWith("js/") }.joinToString("") { squash(it.content) }
            for (block in blocksBefore.filter { it.kind == InlineBlock.Kind.STYLE && it.lineCount >= LightPlanner.MIN_LINES }) {
                assertTrue("[$label/$name] a <style> block went missing", mergedCss.contains(squash(block.code)))
            }
            for (code in classicScriptTexts) {
                assertTrue("[$label/$name] a classic <script> block went missing", mergedJs.contains(squash(code)))
            }
        }
        assertTrue("[$label] expected at least $minExpectedFiles file(s) from Light, got ${light.files.size}", light.files.size >= minExpectedFiles)

        // Apply the richest plan and confirm every link/script it wrote actually resolves to a written file.
        PlanApplier.apply(project, file.virtualFile, byFeature)
        val appliedHtml = file.viewProvider.document!!.text
        val links = Regex("""(?:href|src)="((?:css|js)/[^"]+)"""").findAll(appliedHtml).map { it.groupValues[1] }.toList()
        for (link in links) {
            assertNotNull("[$label] $link is linked but was never written", file.virtualFile.parent.findFileByRelativePath(link))
        }
        println("[$label] OK - Light:${light.files.size} files, By feature:${byFeature.files.size}, By type:${byType.files.size}, ${links.size} links all resolve")
        postApply(appliedHtml)
    }

    // ---- 1. Bootstrap-style: CDN framework + override block + inline style="" attributes ----------

    fun test1BootstrapStyleOverridesPlusInlineStyleAttributes() {
        val overrides = """
            /* ---------- navbar ---------- */
            ${(1..12).joinToString("\n") { ".navbar-custom .nav-link-$it { color: #333; }" }}
            /* ---------- cards ---------- */
            ${(1..12).joinToString("\n") { ".card-$it { border-radius: 8px; box-shadow: 0 2px 6px rgba(0,0,0,.08); }" }}
        """.trimIndent()
        val html = """
            <!DOCTYPE html><html><head>
            <link href="https://cdn.jsdelivr.net/npm/bootstrap@5.3.3/dist/css/bootstrap.min.css" rel="stylesheet">
            <style>
            $overrides
            </style>
            </head><body>
            <div class="container" style="padding-top: 40px;">
              <div class="card" style="margin-bottom: 12px;"><div class="card-body" style="padding: 16px;">Hi</div></div>
            </div>
            <script src="https://cdn.jsdelivr.net/npm/bootstrap@5.3.3/dist/js/bootstrap.bundle.min.js"></script>
            </body></html>
        """.trimIndent()
        // inline style="" attributes are not this plugin's job: confirm they're untouched, not swept up.
        checkAllThreeStrategies("1-bootstrap", html) { appliedHtml ->
            assertTrue(appliedHtml.contains("""style="padding-top: 40px;""""))
        }
    }

    // ---- 2. Tailwind-style: almost all utility classes, one tiny <style> block ---------------------

    fun test2TailwindStyleTinyStyleBlockStaysClean() {
        val html = """
            <!DOCTYPE html><html><head><script src="https://cdn.tailwindcss.com"></script>
            <style>
              @keyframes fade { from { opacity: 0 } to { opacity: 1 } }
            </style>
            </head><body class="bg-slate-950 text-white min-h-screen flex items-center justify-center">
              <h1 class="text-4xl font-bold tracking-tight md:text-6xl">Hello</h1>
            </body></html>
        """.trimIndent()
        val file = myFixture.configureByText("2-tailwind.html", html).virtualFile
        val outcome = UntangleFlow.run(project, file, { _, _, _ -> UntangleFlow.Strategy.LIGHT }, { it })
        assertEquals("a 2-line keyframe block is below MIN_LINES, so there's nothing worth extracting", UntangleFlow.Outcome.Clean, outcome)
        println("[2-tailwind] OK - correctly left alone (Clean)")
    }

    // ---- 3. One huge vanilla style block, zero internal comments (worst case for Feature/Type) -----

    fun test3HugeUncommentedStyleBlockDegradesToOneFile() {
        val css = (1..600).joinToString("\n") { ".sel-$it { color: #${(it * 137) % 0xFFFFFF}; margin: ${it % 40}px; }" }
        val html = "<html><head><style>\n$css\n</style></head><body></body></html>"
        checkAllThreeStrategies("3-huge-uncommented", html)

        val file = myFixture.configureByText("3-check.html", html) as XmlFile
        val byFeature = FeaturePlanner.plan(file, project)
        assertEquals("no comments means By feature can't subdivide it, and that's fine", 1, byFeature.files.size)
    }

    // ---- 4. Two <style> blocks separated by a real toolbar+script gap: must NOT merge --------------

    fun test4TwoStyleBlocksSeparatedByAScriptGapStaySeparateRuns() {
        val block1 = (1..20).joinToString("\n") { ".hero-$it { color: red; }" }
        val block2 = (1..20).joinToString("\n") { ".footer-$it { color: blue; }" }
        val toolbarScript = (1..20).joinToString("\n") { "function toolbarAction$it() { console.log($it); }" }
        val html = """
            <html><head>
            <style>
            $block1
            </style>
            </head><body>
            <div id="toolbar"></div>
            <script>
            $toolbarScript
            </script>
            <style>
            $block2
            </style>
            </body></html>
        """.trimIndent()
        val file = myFixture.configureByText("4-gap.html", html) as XmlFile

        val light = LightPlanner.plan(file)

        assertEquals("the script between them must split the cascade into two runs", listOf("css/styles.css", "css/styles-2.css"), light.files.filter { it.relativePath.startsWith("css/") }.map { it.relativePath })
        checkAllThreeStrategies("4-gap", html)
    }

    // ---- 5. type="module" main script stays inline; a big classic script is extracted --------------

    fun test5ModuleScriptStaysInlineClassicScriptIsExtracted() {
        val classic = (1..30).joinToString("\n") { "function legacyHandler$it() { document.title = '$it'; }" }
        val module = (1..30).joinToString("\n") { "import { x$it } from './mod$it.js';" }
        val html = """
            <html><body>
            <script>
            $classic
            </script>
            <script type="module">
            $module
            </script>
            </body></html>
        """.trimIndent()
        val file = myFixture.configureByText("5-module.html", html) as XmlFile

        val light = LightPlanner.plan(file)

        assertEquals(1, light.files.count { it.relativePath.startsWith("js/") })
        checkAllThreeStrategies("5-module", html) { appliedHtml ->
            assertTrue("the module script must stay inline (file:// can't load ES modules)", appliedHtml.contains("""type="module""""))
        }
    }

    // ---- 6. JSON-LD + analytics + a confetti-like external script, sprinkled between real blocks ---

    fun test6ThirdPartyAndStructuredDataScriptsAreLeftAloneAmongRealBlocks() {
        val css = (1..20).joinToString("\n") { ".x$it { color: red; }" }
        val logic = (1..20).joinToString("\n") { "function trackEvent$it() { console.log($it); }" }
        val html = """
            <html><head>
            <script type="application/ld+json">{"@type":"Product","name":"Nimbus"}</script>
            <style>
            $css
            </style>
            <script src="https://www.googletagmanager.com/gtag/js?id=G-XXXX"></script>
            </head><body>
            <script src="https://cdn.jsdelivr.net/npm/canvas-confetti@1.9.3/dist/confetti.browser.min.js"></script>
            <script>
            $logic
            </script>
            </body></html>
        """.trimIndent()
        checkAllThreeStrategies("6-thirdparty", html) { appliedHtml ->
            assertTrue(appliedHtml.contains("application/ld+json"))
            assertTrue(appliedHtml.contains("googletagmanager"))
            assertTrue(appliedHtml.contains("canvas-confetti"))
        }
    }

    // ---- 7. Dashboard: onclick handlers reference functions defined in the big inline script -------

    fun test7DashboardOnclickHandlersStillReachTheirFunctionsTextually() {
        val script = """
            function sortColumn(name) { console.log('sort', name); }
            function deleteRow(id) { console.log('delete', id); }
            ${(1..25).joinToString("\n") { "function pad$it() { return $it; }" }}
        """.trimIndent()
        val html = """
            <html><body>
            <table>
              <tr><th onclick="sortColumn('name')">Name</th><th onclick="sortColumn('date')">Date</th></tr>
              <tr><td>Row 1</td><td><button onclick="deleteRow(1)">x</button></td></tr>
            </table>
            <script>
            $script
            </script>
            </body></html>
        """.trimIndent()
        val file = myFixture.configureByText("7-dashboard.html", html) as XmlFile
        val plan = LightPlanner.plan(file)
        val jsContent = plan.files.first { it.relativePath.startsWith("js/") }.content
        assertTrue(jsContent.contains("function sortColumn"))
        assertTrue(jsContent.contains("function deleteRow"))
        checkAllThreeStrategies("7-dashboard", html)
    }

    // ---- 8. :root-heavy design-token page: By type should bucket it as variables.css ---------------

    fun test8DesignTokenHeavyPageBucketsVariablesSeparately() {
        val tokens = (1..40).joinToString("\n") { "  --color-$it: #${(it * 97) % 0xFFFFFF};" }
        val components = (1..20).joinToString("\n") { ".btn-$it { padding: 8px; }" }
        val html = """
            <html><head><style>
            :root {
            $tokens
            }
            /* ---------- buttons ---------- */
            $components
            </style></head><body></body></html>
        """.trimIndent()
        val file = myFixture.configureByText("8-tokens.html", html) as XmlFile

        val byType = TypePlanner.plan(file, project)

        assertTrue(byType.files.any { it.relativePath == "css/variables.css" })
        assertTrue(byType.files.first { it.relativePath == "css/variables.css" }.content.contains("--color-1:"))
        checkAllThreeStrategies("8-tokens", html)
    }

    // ---- 9. Arabic RTL content and Arabic comments: UTF-8 end to end --------------------------------

    fun test9ArabicRtlContentAndCommentsRoundTripCleanly() {
        val css = (1..20).joinToString("\n") { ".بند-$it { color: red; }" }
        val script = (1..20).joinToString("\n") { "function عرض$it() { console.log($it); }" }
        val html = """
            <html dir="rtl" lang="ar"><head><meta charset="UTF-8">
            <style>
            /* ---------- الرأس ---------- */
            $css
            </style>
            </head><body>
              <h1>مرحبا بكم في نيمبوس</h1>
              <script>
              // القوائم
              $script
              </script>
            </body></html>
        """.trimIndent()
        checkAllThreeStrategies("9-arabic", html) { appliedHtml ->
            assertTrue(appliedHtml.contains("مرحبا بكم في نيمبوس"))
        }
    }

    // ---- 10. A genuinely small, already-clean page: must not false-positive as spaghetti -----------

    fun test10GenuinelySmallCleanPageIsLeftAlone() {
        val html = """
            <html><head><link rel="stylesheet" href="style.css"><title>Contact</title></head>
            <body><h1>Contact us</h1><p>Email: hi@example.com</p><script src="app.js"></script></body></html>
        """.trimIndent()
        val file = myFixture.configureByText("10-small.html", html).virtualFile
        val outcome = UntangleFlow.run(project, file, { _, _, _ -> UntangleFlow.Strategy.LIGHT }, { it })
        assertEquals(UntangleFlow.Outcome.Clean, outcome)
        println("[10-small] OK - correctly left alone (Clean)")
    }
}
