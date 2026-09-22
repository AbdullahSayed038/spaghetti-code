package dev.spaghetti.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SummarizeFlowTest {

    private val files = listOf("css/nav.css" to ".nav { color: red; }", "js/toast.js" to "function toast() {}")

    @Test
    fun successBuildsAManifestFromTheModelsResponse() {
        val canned = """[{"path": "css/nav.css", "summary": "Navigation bar."}, {"path": "js/toast.js", "summary": "Toast notifications."}]"""

        val outcome = SummarizeFlow.run("index.html", files) { _, _ -> canned }

        val success = outcome as SummarizeFlow.Outcome.Success
        assertEquals(listOf("css/nav.css", "js/toast.js"), success.summaries.map { it.path })
        assertTrue(success.manifestContent.contains("Navigation bar."))
    }

    @Test
    fun aNetworkFailureBecomesAFailedOutcomeNotAnException() {
        val outcome = SummarizeFlow.run("index.html", files) { _, _ -> throw OpenAiApiException("no internet") }

        val failed = outcome as SummarizeFlow.Outcome.Failed
        assertTrue(failed.reason.contains("no internet"))
    }

    @Test
    fun aMalformedResponseBecomesAFailedOutcomeWithTheReason() {
        val outcome = SummarizeFlow.run("index.html", files) { _, _ -> "not json" }

        val failed = outcome as SummarizeFlow.Outcome.Failed
        assertTrue(failed.reason.isNotBlank())
    }

    @Test
    fun noFilesIsFailedNotACrashOrAWastedApiCall() {
        var called = false
        val outcome = SummarizeFlow.run("index.html", emptyList()) { _, _ -> called = true; "[]" }

        assertTrue(outcome is SummarizeFlow.Outcome.Failed)
        assertTrue("must not call the API when there is nothing to summarize", !called)
    }

    @Test
    fun theRealSystemAndUserPromptsAreWhatGetsSentToComplete() {
        var seenSystem: String? = null
        var seenUser: String? = null
        SummarizeFlow.run("index.html", files) { system, user -> seenSystem = system; seenUser = user; "[]" }

        assertEquals(SummaryPrompt.systemPrompt, seenSystem)
        assertEquals(SummaryPrompt.userPrompt(files), seenUser)
    }
}
