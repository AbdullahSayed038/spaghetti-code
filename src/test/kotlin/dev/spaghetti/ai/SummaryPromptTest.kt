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
    fun responseSchemaPinsTheSummariesArrayToTheExactFileCount() {
        val schema = SummaryPrompt.responseSchema(32)

        val summaries = schema.getAsJsonObject("properties").getAsJsonObject("summaries")
        assertEquals(32, summaries.get("minItems").asInt)
        assertEquals(32, summaries.get("maxItems").asInt)
    }

    @Test
    fun parseReadsSummariesByPositionInOrder() {
        val response = """{"summaries": ["Alpha styles.", "Beta logic."]}"""

        val summaries = SummaryPrompt.parse(response, listOf("a.css", "b.js"))

        assertEquals(listOf(FileSummary("a.css", "Alpha styles."), FileSummary("b.js", "Beta logic.")), summaries)
    }

    @Test
    fun parseStripsAMarkdownCodeFenceIfTheModelAddedOneAnyway() {
        val response = "```json\n" + """{"summaries": ["Alpha."]}""" + "\n```"

        val summaries = SummaryPrompt.parse(response, listOf("a.css"))

        assertEquals("Alpha.", summaries.single().summary)
    }

    @Test
    fun parseIgnoresCommentaryBeforeAndAfterTheObject() {
        val response = "Sure, here you go:\n" + """{"summaries": ["Alpha."]}""" + "\nHope that helps!"

        val summaries = SummaryPrompt.parse(response, listOf("a.css"))

        assertEquals("Alpha.", summaries.single().summary)
    }

    @Test
    fun parseThrowsWithTheRawResponseWhenTheCountIsShort() {
        val response = """{"summaries": ["Alpha."]}"""

        val ex = assertThrows(SummaryParseException::class.java) { SummaryPrompt.parse(response, listOf("a.css", "b.js")) }

        assertTrue(ex.message!!.contains("2"))
        assertTrue(ex.message!!.contains("1"))
        assertEquals(response, ex.rawResponse)
    }

    @Test
    fun parseThrowsWhenTheCountIsTooMany() {
        val response = """{"summaries": ["Alpha.", "Extra."]}"""

        assertThrows(SummaryParseException::class.java) { SummaryPrompt.parse(response, listOf("a.css")) }
    }

    @Test
    fun parseThrowsOnGarbageNotJsonAtAll() {
        assertThrows(SummaryParseException::class.java) { SummaryPrompt.parse("not json at all, sorry", listOf("a.css")) }
    }

    @Test
    fun parseThrowsWhenTheSummariesKeyIsMissing() {
        assertThrows(SummaryParseException::class.java) { SummaryPrompt.parse("""{"oops": []}""", listOf("a.css")) }
    }

    @Test
    fun parseThrowsWhenTheArrayIsEmptyButPathsWereRequested() {
        assertThrows(SummaryParseException::class.java) { SummaryPrompt.parse("""{"summaries": []}""", listOf("a.css")) }
    }
}
