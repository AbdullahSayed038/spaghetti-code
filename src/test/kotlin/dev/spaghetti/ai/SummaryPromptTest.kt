package dev.spaghetti.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SummaryPromptTest {

    @Test
    fun userPromptIncludesEveryFilesPathAsAHeaderAndItsContent() {
        val prompt = SummaryPrompt.userPrompt(listOf("css/nav.css" to ".nav { color: red; }", "js/toast.js" to "function toast() {}"))

        assertTrue(prompt.contains("=== css/nav.css ==="))
        assertTrue(prompt.contains(".nav { color: red; }"))
        assertTrue(prompt.contains("=== js/toast.js ==="))
        assertTrue(prompt.contains("function toast() {}"))
    }

    @Test
    fun userPromptTruncatesAnUnusuallyLargeFile() {
        val huge = "x".repeat(20_000)
        val prompt = SummaryPrompt.userPrompt(listOf("css/huge.css" to huge))

        assertTrue("prompt should be much shorter than the raw 20000-char file", prompt.length < 10_000)
    }

    @Test
    fun parseReadsAPlainJsonArrayInOrder() {
        val response = """[{"path": "a.css", "summary": "Alpha styles."}, {"path": "b.js", "summary": "Beta logic."}]"""

        val summaries = SummaryPrompt.parse(response, listOf("a.css", "b.js"))

        assertEquals(listOf(FileSummary("a.css", "Alpha styles."), FileSummary("b.js", "Beta logic.")), summaries)
    }

    @Test
    fun parseStripsAMarkdownCodeFenceIfTheModelAddedOneAnyway() {
        val response = "```json\n" + """[{"path": "a.css", "summary": "Alpha."}]""" + "\n```"

        val summaries = SummaryPrompt.parse(response, listOf("a.css"))

        assertEquals("Alpha.", summaries.single().summary)
    }

    @Test
    fun parseIgnoresCommentaryBeforeAndAfterTheArray() {
        val response = "Sure, here you go:\n" + """[{"path": "a.css", "summary": "Alpha."}]""" + "\nHope that helps!"

        val summaries = SummaryPrompt.parse(response, listOf("a.css"))

        assertEquals("Alpha.", summaries.single().summary)
    }

    @Test
    fun parseReturnsRequestedOrderEvenIfTheModelAnsweredOutOfOrder() {
        val response = """[{"path": "b.js", "summary": "B."}, {"path": "a.css", "summary": "A."}]"""

        val summaries = SummaryPrompt.parse(response, listOf("a.css", "b.js"))

        assertEquals(listOf("a.css", "b.js"), summaries.map { it.path })
    }

    @Test
    fun parseThrowsWithTheRawResponseWhenAPathIsMissing() {
        val response = """[{"path": "a.css", "summary": "Alpha."}]"""

        val ex = assertThrows(SummaryParseException::class.java) { SummaryPrompt.parse(response, listOf("a.css", "b.js")) }

        assertTrue(ex.message!!.contains("b.js"))
        assertEquals(response, ex.rawResponse)
    }

    @Test
    fun parseThrowsOnGarbageNotJsonAtAll() {
        assertThrows(SummaryParseException::class.java) { SummaryPrompt.parse("not json at all, sorry", listOf("a.css")) }
    }

    @Test
    fun parseThrowsWhenTheArrayIsEmptyButPathsWereRequested() {
        assertThrows(SummaryParseException::class.java) { SummaryPrompt.parse("[]", listOf("a.css")) }
    }
}
