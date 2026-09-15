package dev.kbt117.keyproxy.server

import java.io.IOException
import java.net.BindException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [ServerErrors].
 *
 * These assert on user-facing wording, because the whole point of the mapping is
 * that "java.net.BindException: bind failed" becomes something the user can act
 * on without reading a stack trace.
 */
class ServerErrorsTest {

    @Test
    fun `a bind exception names the port and suggests a fix`() {
        val message = ServerErrors.describeBindFailure(
            BindException("bind failed: EADDRINUSE"),
            port = 8080,
        )
        assertTrue("message should name the port: $message", message.contains("8080"))
        assertTrue("message should mention the port is taken: $message", message.contains("already in use"))
    }

    @Test
    fun `detects address-in-use even when it is only in the message`() {
        val message = ServerErrors.describeBindFailure(
            IOException("Address already in use"),
            port = 9000,
        )
        assertTrue(message.contains("already in use"))
    }

    @Test
    fun `unwraps a nested cause`() {
        // Netty commonly wraps the real failure in an engine-specific exception.
        val wrapped = IllegalStateException(
            "Failed to start",
            BindException("Address already in use"),
        )
        val message = ServerErrors.describeBindFailure(wrapped, port = 8080)
        assertTrue(message.contains("already in use"))
    }

    @Test
    fun `a security exception is reported distinctly`() {
        val message = ServerErrors.describeBindFailure(
            SecurityException("Permission denied"),
            port = 80,
        )
        assertTrue(message.contains("refused to bind"))
    }

    @Test
    fun `an unknown failure still mentions the port`() {
        val message = ServerErrors.describeBindFailure(
            IOException("something odd"),
            port = 12345,
        )
        assertEquals("Could not start on port 12345: something odd", message)
    }

    @Test
    fun `a failure with no message falls back to the exception name`() {
        val message = ServerErrors.describeBindFailure(
            IOException(),
            port = 8080,
        )
        assertTrue("message should name the exception: $message", message.contains("IOException"))
    }
}
