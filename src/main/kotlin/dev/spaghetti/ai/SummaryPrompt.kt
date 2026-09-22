package dev.spaghetti.ai

import com.google.gson.JsonArray
import com.google.gson.JsonObject
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
        Respond with ONLY a JSON object, no markdown code fence, no commentary before or after it:
        {"summaries": ["...", "...", ...]}
        The array must have exactly one string per file, in the exact same order the files were given.
        Do not repeat the file path -- just the sentence.
    """.trimIndent()

    fun userPrompt(files: List<Pair<String, String>>): String =
        files.joinToString("\n\n") { (path, content) ->
            "=== $path ===\n" + content.take(MAX_CHARS_PER_FILE)
        }

    /**
     * Builds the JSON schema for OpenAI's structured-output strict mode: an object with a `summaries`
     * array pinned to exactly [fileCount] strings. This is what actually prevents the model from
     * dropping or duplicating an entry -- the API enforces the count, not just the prompt wording.
     */
    fun responseSchema(fileCount: Int): JsonObject {
        val summariesSchema = JsonObject().apply {
            addProperty("type", "array")
            add("items", JsonObject().apply { addProperty("type", "string") })
            addProperty("minItems", fileCount)
            addProperty("maxItems", fileCount)
        }
        return JsonObject().apply {
            addProperty("type", "object")
            add("properties", JsonObject().apply { add("summaries", summariesSchema) })
            add("required", JsonArray().apply { add("summaries") })
            addProperty("additionalProperties", false)
        }
    }

    /** Parses the model's raw text reply into one [FileSummary] per requested path, matched by position. */
    fun parse(rawResponseText: String, requestedPaths: List<String>): List<FileSummary> {
        val jsonText = extractJsonObject(rawResponseText)
        val root = try {
            JsonParser.parseString(jsonText)
        } catch (e: JsonSyntaxException) {
            throw SummaryParseException("The response wasn't valid JSON: ${e.message}", rawResponseText)
        }
        if (!root.isJsonObject) throw SummaryParseException("Expected a JSON object, got: ${root.javaClass.simpleName}", rawResponseText)

        val summariesElement = root.asJsonObject.get("summaries")
        if (summariesElement == null || !summariesElement.isJsonArray) {
            throw SummaryParseException("The response has no \"summaries\" array", rawResponseText)
        }

        val summaries = summariesElement.asJsonArray
            .filter { it.isJsonPrimitive }
            .map { it.asString }

        if (summaries.size != requestedPaths.size) {
            throw SummaryParseException(
                "Expected ${requestedPaths.size} summaries, got ${summaries.size}",
                rawResponseText,
            )
        }
        return requestedPaths.zip(summaries) { path, summary -> FileSummary(path, summary) }
    }

    /** The model was told not to wrap its answer in a code fence, but strips one defensively if it did anyway. */
    private fun extractJsonObject(text: String): String {
        val trimmed = text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        if (start < 0 || end < start) throw SummaryParseException("No JSON object found in the response", text)
        return trimmed.substring(start, end + 1)
    }
}

class SummaryParseException(message: String, val rawResponse: String) : Exception(message)
