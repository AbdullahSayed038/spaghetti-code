package dev.spaghetti.ai

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe

/**
 * Where the OpenAI API key comes from, checked in this order, and never touched unless the user clicks
 * Summarize: the `OPENAI_API_KEY` environment variable (so anyone who already has it set for other
 * tools needs to do nothing extra), then IntelliJ's own encrypted [PasswordSafe] (never a plain file on
 * disk, never logged), which is where a key entered through the prompt dialog is saved for next time.
 */
object ApiKeyStore {

    private val attributes = CredentialAttributes(generateServiceName("Spaghetti Code", "OpenAiApiKey"))

    fun get(): String? =
        System.getenv("OPENAI_API_KEY")?.takeIf { it.isNotBlank() }
            ?: PasswordSafe.instance.getPassword(attributes)?.takeIf { it.isNotBlank() }

    fun save(apiKey: String) {
        PasswordSafe.instance.set(attributes, Credentials("api-key", apiKey))
    }
}
