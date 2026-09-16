package dev.kbt117.keyproxy.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsValidatorTest {

    // ------------------------------------------------------------ base URL

    @Test
    fun `accepts a standard https api`() {
        assertEquals(
            Validation.Valid,
            SettingsValidator.validateBaseUrl("https://api.openai.com"),
        )
    }

    @Test
    fun `accepts an https api with a path prefix`() {
        assertEquals(
            Validation.Valid,
            SettingsValidator.validateBaseUrl("https://openrouter.ai/api/v1"),
        )
    }

    @Test
    fun `accepts localhost with an explicit port`() {
        // Regression: the host check must not compare against the port suffix.
        assertEquals(
            Validation.Valid,
            SettingsValidator.validateBaseUrl("http://localhost:8080"),
        )
    }

    @Test
    fun `accepts loopback ip with a port`() {
        assertEquals(
            Validation.Valid,
            SettingsValidator.validateBaseUrl("http://127.0.0.1:11434/v1"),
        )
    }

    @Test
    fun `accepts an ipv6 loopback literal`() {
        assertEquals(
            Validation.Valid,
            SettingsValidator.validateBaseUrl("http://[::1]:11434/v1"),
        )
    }

    @Test
    fun `rejects an empty value`() {
        assertTrue(SettingsValidator.validateBaseUrl("   ") is Validation.Invalid)
    }

    @Test
    fun `rejects a url with no scheme`() {
        assertTrue(SettingsValidator.validateBaseUrl("api.openai.com") is Validation.Invalid)
    }

    @Test
    fun `rejects a dotless non-loopback host as a likely typo`() {
        assertTrue(SettingsValidator.validateBaseUrl("https://apiopenai") is Validation.Invalid)
    }

    @Test
    fun `rejects a url with no host at all`() {
        assertTrue(SettingsValidator.validateBaseUrl("https:///v1") is Validation.Invalid)
    }

    // ---------------------------------------------------------------- port

    @Test
    fun `accepts a normal high port`() {
        assertEquals(Validation.Valid, SettingsValidator.validatePort("8080"))
    }

    @Test
    fun `rejects a privileged port`() {
        assertTrue(SettingsValidator.validatePort("80") is Validation.Invalid)
    }

    @Test
    fun `rejects a port above the maximum`() {
        assertTrue(SettingsValidator.validatePort("70000") is Validation.Invalid)
    }

    @Test
    fun `rejects a non-numeric port`() {
        assertTrue(SettingsValidator.validatePort("eighty") is Validation.Invalid)
    }

    @Test
    fun `rejects an empty port`() {
        assertTrue(SettingsValidator.validatePort("") is Validation.Invalid)
    }

    // --------------------------------------------------------- normalising

    @Test
    fun `normalising removes trailing slashes and whitespace`() {
        assertEquals(
            "https://api.openai.com",
            SettingsValidator.normalizeBaseUrl("  https://api.openai.com//  "),
        )
    }

    // -------------------------------------------------------------- masking

    @Test
    fun `masking reveals only a prefix and suffix of a long key`() {
        val masked = SettingsValidator.maskApiKey("sk-abcdefghijklmnop")
        assertTrue("expected a prefix, got $masked", masked.startsWith("sk-abc"))
        assertTrue("expected a suffix, got $masked", masked.endsWith("mnop"))
        assertFalse("masked value must not contain the middle", masked.contains("ghijkl"))
    }

    @Test
    fun `masking never partially echoes a short key`() {
        // For a short string a prefix *is* the secret, so it must be hidden
        // entirely rather than shown with an ellipsis.
        assertEquals("•".repeat(8), SettingsValidator.maskApiKey("sk-1234"))
    }

    @Test
    fun `masking reports an unset key`() {
        assertEquals("not set", SettingsValidator.maskApiKey(""))
    }
}
