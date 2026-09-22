package dev.spaghetti

import dev.spaghetti.plan.CommentSections

class CommentSectionsTest : SpaghettiTestCase() {

    private fun lines(n: Int, prefix: String) = (1..n).joinToString("\n") { "  .$prefix$it { color: red; }" }
    private fun jsLines(n: Int, prefix: String) = (1..n).joinToString("\n") { "  var $prefix$it = $it;" }

    // ---- CSS ------------------------------------------------------------------------------------

    fun testCssSplitsAtTopLevelComments() {
        val css = """
            :root { --a: 1; }

            /* ---------- nav ---------- */
            .nav { color: red; }
            .nav a { color: blue; }

            /* ---------- hero ---------- */
            .hero { color: green; }
            @media (max-width: 768px) { .hero { color: black; } }
        """.trimIndent()

        val sections = CommentSections.cssSections(css, project)

        assertEquals(listOf(null, "nav", "hero"), sections.map { it.name })
        assertTrue(sections[0].text.contains(":root"))
        assertTrue(sections[1].text.contains(".nav {") && sections[1].text.contains(".nav a {"))
        assertTrue(sections[2].text.contains(".hero {") && sections[2].text.contains("@media"))
    }

    fun testCssNothingIsLostAndOrderIsPreserved() {
        val css = """
            /* ---------- nav ---------- */
            .nav { x: 1; }
            /* ---------- hero ---------- */
            .hero { x: 2; }
            /* ---------- footer ---------- */
            .footer { x: 3; }
        """.trimIndent()

        val sections = CommentSections.cssSections(css, project)
        val squash = sections.joinToString("") { it.text }.filterNot { it.isWhitespace() }
        assertEquals(css.filterNot { it.isWhitespace() }, squash)
    }

    fun testCssCommentInsideARuleIsNotASectionBoundary() {
        val css = """
            /* ---------- hero ---------- */
            .hero {
              /* fixme: revisit this color */
              color: red;
            }
        """.trimIndent()

        val sections = CommentSections.cssSections(css, project)

        assertEquals(listOf("hero"), sections.map { it.name })
        assertTrue(sections[0].text.contains("fixme"))
    }

    fun testCssCommentInsideAMediaBlockIsNotASectionBoundary() {
        val css = """
            /* ---------- responsive ---------- */
            @media (max-width: 768px) {
              /* mobile nav collapse */
              .nav { display: none; }
            }
        """.trimIndent()

        val sections = CommentSections.cssSections(css, project)

        assertEquals(listOf("responsive"), sections.map { it.name })
    }

    fun testCssStringThatLooksLikeACommentIsNotASectionBoundary() {
        val css = """
            /* ---------- hero ---------- */
            .hero::before { content: "/* not a comment */"; }
            /* ---------- footer ---------- */
            .footer { color: gray; }
        """.trimIndent()

        val sections = CommentSections.cssSections(css, project)

        assertEquals(listOf("hero", "footer"), sections.map { it.name })
    }

    fun testCssWithNoCommentsIsOneUnnamedSection() {
        val css = ".a { x: 1; }\n.b { x: 2; }"

        val sections = CommentSections.cssSections(css, project)

        assertEquals(listOf(null), sections.map { it.name })
        assertEquals(css, sections.single().text)
    }

    // ---- JS ---------------------------------------------------------------------------------------

    fun testJsSplitsAtTopLevelComments() {
        val js = """
            var GLOBAL = 1;

            // toasts
            function showToast(msg) { console.log(msg); }

            /* theme */
            function setTheme(t) { document.body.dataset.theme = t; }
        """.trimIndent()

        val sections = CommentSections.jsSections(js, project)

        assertEquals(listOf(null, "toasts", "theme"), sections.map { it.name })
        assertTrue(sections[0].text.contains("GLOBAL"))
        assertTrue(sections[1].text.contains("// toasts") && sections[1].text.contains("showToast"))
        assertTrue(sections[2].text.contains("/* theme */") && sections[2].text.contains("setTheme"))
    }

    fun testJsCommentInsideAFunctionBodyIsNotASectionBoundary() {
        val js = """
            // toasts
            function showToast(msg) {
              // fixme: debounce this
              console.log(msg);
            }
        """.trimIndent()

        val sections = CommentSections.jsSections(js, project)

        assertEquals(listOf("toasts"), sections.map { it.name })
        assertTrue(sections[0].text.contains("fixme"))
    }

    fun testJsNothingIsLostAndOrderIsPreserved() {
        val js = """
            // first
            var a1 = 1;
            // second
            var b1 = 2;
        """.trimIndent()

        val sections = CommentSections.jsSections(js, project)
        val squash = sections.joinToString("") { it.text }.filterNot { it.isWhitespace() }
        assertEquals(js.filterNot { it.isWhitespace() }, squash)
    }

    // ---- merging tiny sections ----------------------------------------------------------------

    fun testTinySectionsMergeForwardIntoTheNextNamedSection() {
        val css = """
            /* ---------- tiny ---------- */
            .tiny { x: 1; }
            /* ---------- big ---------- */
            ${lines(20, "big")}
        """.trimIndent()
        val sections = CommentSections.cssSections(css, project)

        val merged = CommentSections.mergeTiny(sections)

        assertEquals(listOf("big"), merged.map { it.name })
        assertTrue(merged[0].text.contains(".tiny") && merged[0].text.contains(".big1"))
    }

    fun testTrailingTinySectionMergesBackwardIntoThePreviousOne() {
        val css = """
            /* ---------- big ---------- */
            ${lines(20, "big")}
            /* ---------- tiny ---------- */
            .tiny { x: 1; }
        """.trimIndent()
        val sections = CommentSections.cssSections(css, project)

        val merged = CommentSections.mergeTiny(sections)

        assertEquals(listOf("big"), merged.map { it.name })
        assertTrue(merged[0].text.contains(".tiny"))
    }

    fun testAllSectionsBigEnoughAreLeftAsIs() {
        val css = """
            /* ---------- nav ---------- */
            ${lines(20, "nav")}
            /* ---------- hero ---------- */
            ${lines(20, "hero")}
        """.trimIndent()
        val sections = CommentSections.cssSections(css, project)

        val merged = CommentSections.mergeTiny(sections)

        assertEquals(listOf("nav", "hero"), merged.map { it.name })
    }
}
