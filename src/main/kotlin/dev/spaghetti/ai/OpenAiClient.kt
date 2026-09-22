package dev.spaghetti.ai

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * The only place that actually talks to the network. Everything else in `dev.spaghetti.ai` is pure
 * text-in/data-out and testable without this; this class exists so a test never accidentally makes a
 * real API call.
 */
object OpenAiClient {

    private const val MODEL = "gpt-4o-mini"
    private const val ENDPOINT = "https://api.openai.com/v1/chat/completions"
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()

    /**
     * @param expectedSummaryCount Pins the response schema's `summaries` array to exactly this many
     *   entries via OpenAI's structured-output strict mode, so the API itself -- not just the prompt
     *   wording -- guarantees the model can't silently drop or duplicate a file's summary.
     * @throws OpenAiApiException on any non-2xx response, a network failure, or a response with no text content.
     */
    fun complete(apiKey: String, systemPrompt: String, userPrompt: String, expectedSummaryCount: Int): String {
        val body = JsonObject().apply {
            addProperty("model", MODEL)
            add(
                "messages",
                JsonArray().apply {
                    add(JsonObject().apply { addProperty("role", "system"); addProperty("content", systemPrompt) })
                    add(JsonObject().apply { addProperty("role", "user"); addProperty("content", userPrompt) })
                },
            )
            add(
                "response_format",
                JsonObject().apply {
                    addProperty("type", "json_schema")
                    add(
                        "json_schema",
                        JsonObject().apply {
                            addProperty("name", "file_summaries")
                            addProperty("strict", true)
                            add("schema", SummaryPrompt.responseSchema(expectedSummaryCount))
                        },
                    )
                },
            )
        }

        val request = HttpRequest.newBuilder(URI.create(ENDPOINT))
            .timeout(Duration.ofSeconds(60))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build()

        val response = try {
            client.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (e: Exception) {
            throw OpenAiApiException("Couldn't reach the OpenAI API: ${e.message}", cause = e)
        }

        if (response.statusCode() !in 200..299) {
            throw OpenAiApiException(describeError(response.statusCode(), response.body()))
        }

        return try {
            val parsed = JsonParser.parseString(response.body()).asJsonObject
            val text = parsed.getAsJsonArray("choices")
                ?.firstOrNull()?.asJsonObject
                ?.getAsJsonObject("message")
                ?.get("content")?.asString
            text ?: throw OpenAiApiException("The API response had no message content: ${response.body().take(300)}")
        } catch (e: OpenAiApiException) {
            throw e
        } catch (e: Exception) {
            throw OpenAiApiException("Couldn't parse the API's response: ${e.message}", cause = e)
        }
    }

    private fun describeError(status: Int, body: String): String = when (status) {
        401 -> "That API key was rejected (401). Check it's correct and active."
        429 -> "Rate limited (429) -- try again in a moment."
        in 500..599 -> "The API had a server error ($status) -- try again in a moment."
        else -> "The API returned $status: ${body.take(300)}"
    }
}

class OpenAiApiException(message: String, cause: Throwable? = null) : Exception(message, cause)
