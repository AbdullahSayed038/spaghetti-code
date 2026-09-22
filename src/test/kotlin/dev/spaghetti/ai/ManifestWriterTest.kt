package dev.spaghetti.ai

import org.junit.Assert.assertTrue
import org.junit.Test

class ManifestWriterTest {

    @Test
    fun rendersATableRowPerFileInOrder() {
        val md = ManifestWriter.render(
            "index.html",
            listOf(FileSummary("css/nav.css", "Navigation bar."), FileSummary("js/toast.js", "Toast notifications.")),
        )

        assertTrue(md.contains("# Untangled: index.html"))
        val navAt = md.indexOf("css/nav.css")
        val toastAt = md.indexOf("js/toast.js")
        assertTrue(navAt >= 0 && toastAt > navAt)
        assertTrue(md.contains("Navigation bar."))
        assertTrue(md.contains("Toast notifications."))
    }

    @Test
    fun aPipeInASummaryDoesNotBreakTheMarkdownTable() {
        val md = ManifestWriter.render("index.html", listOf(FileSummary("a.css", "Styles for a | b toggle.")))

        assertTrue(md.contains("a \\| b toggle"))
    }

    @Test
    fun statesTheCodeItselfWasNotTouchedByAi() {
        val md = ManifestWriter.render("index.html", listOf(FileSummary("a.css", "x")))

        assertTrue(md.contains("never touched by AI"))
    }
}
