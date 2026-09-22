package dev.spaghetti.ai

/**
 * The AI-summary step: given the files an untangle just wrote, produce `MANIFEST.md` content.
 *
 * Deliberately knows nothing about the network, the API key, or the filesystem — [complete] is the one
 * seam that talks to the outside world, injected so a test can supply a canned response and this class
 * is exercised exactly as it runs for real, without ever making an HTTP call. The actual wiring (resolve
 * the key, call [OpenAiClient], write the file, show a notification) lives in the action, not here.
 */
object SummarizeFlow {

    sealed interface Outcome {
        data class Success(val manifestContent: String, val summaries: List<FileSummary>) : Outcome
        data class Failed(val reason: String) : Outcome
    }

    /** @param complete (systemPrompt, userPrompt) -> the model's raw text reply. */
    fun run(sourceFileName: String, files: List<Pair<String, String>>, complete: (system: String, user: String) -> String): Outcome {
        if (files.isEmpty()) return Outcome.Failed("Nothing to summarize.")

        val responseText = try {
            complete(SummaryPrompt.systemPrompt, SummaryPrompt.userPrompt(files))
        } catch (e: Exception) {
            return Outcome.Failed(e.message ?: "Unknown error calling the API")
        }

        val summaries = try {
            SummaryPrompt.parse(responseText, files.map { it.first })
        } catch (e: SummaryParseException) {
            return Outcome.Failed(e.message ?: "Couldn't parse the API's response")
        }

        return Outcome.Success(ManifestWriter.render(sourceFileName, summaries), summaries)
    }
}
