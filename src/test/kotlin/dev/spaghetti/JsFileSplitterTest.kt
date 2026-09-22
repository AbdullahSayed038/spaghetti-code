package dev.spaghetti

import com.intellij.psi.PsiFile
import dev.spaghetti.plan.JsFileSplitter
import dev.spaghetti.plan.PlanApplier

class JsFileSplitterTest : SpaghettiTestCase() {

    private fun squash(s: String) = s.filterNot { it.isWhitespace() }
    private fun js(name: String, text: String): PsiFile = myFixture.configureByText(name, text)

    // ---- eligibility: the safety gate ------------------------------------------------------------

    fun testClassicScriptWithNoImportIsDeclined() {
        val text = (1..30).joinToString("\n") { "function f$it() { return $it; }" }
        val file = js("app.js", text)

        val eligibility = JsFileSplitter.checkEligibility(file, project)

        assertTrue(eligibility is JsFileSplitter.Eligibility.Declined)
        assertTrue((eligibility as JsFileSplitter.Eligibility.Declined).reason.contains("classic script"))
    }

    fun testModuleThatExportsSomethingIsDeclined() {
        val text = """
            import { helper } from "./helper.js";
            export function doThing() { return helper(); }
        """.trimIndent() + "\n" + (1..20).joinToString("\n") { "function f$it() { return $it; }" }
        val file = js("lib.js", text)

        val eligibility = JsFileSplitter.checkEligibility(file, project)

        assertTrue(eligibility is JsFileSplitter.Eligibility.Declined)
        assertTrue((eligibility as JsFileSplitter.Eligibility.Declined).reason.contains("exports"))
    }

    fun testEntryPointModuleWithImportsAndNoExportsIsEligible() {
        val text = """
            import "./polyfill.js";
            import { x } from "./x.js";

            // boot
            ${(1..20).joinToString("\n") { "function f$it() { return $it; }" }}
        """.trimIndent()
        val file = js("main.js", text)

        val eligibility = JsFileSplitter.checkEligibility(file, project)

        assertEquals(JsFileSplitter.Eligibility.Eligible, eligibility)
    }

    fun testExportInsideAFunctionBodyDoesNotCountAsATopLevelExport() {
        // "export" only matters at the top level; the word appearing inside a string/comment/nested scope must not trip the gate.
        val text = """
            import "./x.js";
            // boot
            function f() {
              var s = "export this string literally contains the word export";
              return s;
            }
            ${(1..20).joinToString("\n") { "function g$it() { return $it; }" }}
        """.trimIndent()
        val file = js("main2.js", text)

        val eligibility = JsFileSplitter.checkEligibility(file, project)

        assertEquals(JsFileSplitter.Eligibility.Eligible, eligibility)
    }

    // ---- the split itself, once eligible -----------------------------------------------------------

    fun testSplitsIntoOneFilePerSectionAndRewritesAsSideEffectImports() {
        val text = """
            import "./polyfill.js";

            // toasts
            ${(1..20).joinToString("\n") { "function toast$it() { return $it; }" }}

            // theme
            ${(1..20).joinToString("\n") { "function theme$it() { return $it; }" }}
        """.trimIndent()
        val file = js("app.js", text)
        check(JsFileSplitter.checkEligibility(file, project) == JsFileSplitter.Eligibility.Eligible)

        val plan = JsFileSplitter.plan(file, project)

        assertEquals(listOf("app/toasts.js", "app/theme.js"), plan.files.map { it.relativePath })
        assertTrue(plan.files.all { it.groupId == "js-file" })

        PlanApplier.apply(project, file.virtualFile, plan)
        assertEquals(
            "import \"./app/toasts.js\";\nimport \"./app/theme.js\";\n",
            file.viewProvider.document!!.text,
        )
        assertNotNull(file.virtualFile.parent.findFileByRelativePath("app/toasts.js"))
        assertNotNull(file.virtualFile.parent.findFileByRelativePath("app/theme.js"))
    }

    fun testTheRewrittenEntryPointStillParsesAsValidJavaScript() {
        val text = """
            import "./polyfill.js";
            // toasts
            ${(1..20).joinToString("\n") { "function toast$it() { return $it; }" }}
            // theme
            ${(1..20).joinToString("\n") { "function theme$it() { return $it; }" }}
        """.trimIndent()
        val file = js("valid.js", text)
        val plan = JsFileSplitter.plan(file, project)
        PlanApplier.apply(project, file.virtualFile, plan)

        val rewritten = myFixture.configureByText("valid-check.js", file.viewProvider.document!!.text)
        val errors = com.intellij.psi.util.PsiTreeUtil.findChildrenOfType(rewritten, com.intellij.psi.PsiErrorElement::class.java)
        assertTrue("rewritten entry point has parse errors: ${errors.map { it.errorDescription }}", errors.isEmpty())
    }

    fun testNothingIsLostAcrossTheSplitFiles() {
        val text = """
            import "./polyfill.js";
            // toasts
            ${(1..20).joinToString("\n") { "function toast$it() { return $it; }" }}
            // theme
            ${(1..20).joinToString("\n") { "function theme$it() { return $it; }" }}
        """.trimIndent()
        val file = js("full.js", text)

        val plan = JsFileSplitter.plan(file, project)
        val merged = plan.files.joinToString("") { squash(it.content) }

        assertEquals(squash(text), merged)
    }

    fun testOneSectionEntryPointIsCleanNotAnEmptySplit() {
        val text = "import \"./x.js\";\n" + (1..20).joinToString("\n") { "function f$it() { return $it; }" }
        val file = js("small.js", text)

        val plan = JsFileSplitter.plan(file, project)

        assertTrue(plan.isEmpty)
    }
}
