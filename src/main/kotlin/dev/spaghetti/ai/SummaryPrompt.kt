package dev.spaghetti.ai

import com.google.gson.JsonParser
import com.google.gson.JsonSyntaxException

/**
 * Builds the request for, and parses the response from, an AI summary call. Pure text in, data out —
 * no network here, so this is fully testable without ever calling the real API. [OpenAiClient] does
 * the actual HTTP call and hands this module the raw response text.
 */
object SummaryPrompt {

    /** A single file's content is capped this long in the prompt: keeps token use sane even for an
     *  unusually large file (e.g. a Light-only result with everything still merged into one). */
    private const val MAX_CHARS_PER_FILE = 6000

    val systemPrompt = """
        You summarize source files for a developer who just auto-split one large file into several.
        For each file you are given, write ONE short sentence (under 20 words) describing what that
        file is responsible for -- not a list of its contents, what it *does* on the page or in the app.
        Respond with ONLY a JSON array, no markdown code fence, no commentary before or after it:
        [{"path": "css/hero.css", "summary": "..."}, ...]
        Every path you were given must appear exactly once, in the same order, with the same path string.
    """.trimIndent()

    fun userPrompt(files: List<Pair<String, String>>): String =
        files.joinToString("\n\n") { (path, content) ->
            "=== $path ===\n" + content.take(MAX_CHARS_PER_FILE)
        }

    /** Parses the model's raw text reply into one [FileSummary] per requested path, in the order given. */
    fun parse(rawResponseText: String, requestedPaths: List<String>): List<FileSummary> {
        val jsonText = extractJsonArray(rawResponseText)
        val root = try {
            JsonParser.parseString(jsonText)
        } catch (e: JsonSyntaxException) {
            throw SummaryParseException("The response wasn't valid JSON: ${e.message}", rawResponseText)
        }
        if (!root.isJsonArray) throw SummaryParseException("Expected a JSON array, got: ${root.javaClass.simpleName}", rawResponseText)

        val byPath = mutableMapOf<String, String>()
        for (element in root.asJsonArray) {
            if (!element.isJsonObject) continue
            val obj = element.asJsonObject
            val path = obj.get("path")?.takeIf { it.isJsonPrimitive }?.asString ?: continue
            val summary = obj.get("summary")?.takeIf { it.isJsonPrimitive }?.asString ?: continue
            byPath[path] = summary
        }

        val missing = requestedPaths.filter { it !in byPath }
        if (missing.isNotEmpty()) {
            throw SummaryParseException("The response is missing a summary for: ${missing.joinToString()}", rawResponseText)
        }
        return requestedPaths.map { FileSummary(it, byPath.getValue(it)) }
    }

    /** The model was told not to wrap its answer in a code fence, but strips one defensively if it did anyway. */
    private fun extractJsonArray(text: String): String {
        val trimmed = text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val start = trimmed.indexOf('[')
        val end = trimmed.lastIndexOf(']')
        if (start < 0 || end < start) throw SummaryParseException("No JSON array found in the response", text)
        return trimmed.substring(start, end + 1)
    }
}

class SummaryParseException(message: String, val rawResponse: String) : Exception(message)
