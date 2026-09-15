package dev.kbt117.keyproxy.server

import java.net.BindException

/**
 * Maps engine startup failures to messages a user can act on.
 *
 * Kept free of Ktor and Android types so it can be unit tested on the JVM
 * without dragging the server stack onto the test classpath.
 */
object ServerErrors {

    /**
     * @param error The throwable thrown while binding. Netty often wraps the
     *   real cause, so the chain is walked to the root.
     * @param port The port that was requested, for the message text.
     */
    fun describeBindFailure(error: Throwable, port: Int): String {
        val root = generateSequence(error) { it.cause }.last()
        val message = root.message

        return when {
            root is BindException ||
                message?.contains("Address already in use") == true ||
                message?.contains("EADDRINUSE") == true ->
                "Port $port is already in use. Pick a different port in Settings."

            root is SecurityException ->
                "Android refused to bind port $port."

            root is java.net.UnknownServiceException ->
                "Unsupported protocol while starting the server."

            else -> "Could not start on port $port: ${message ?: root.javaClass.simpleName}"
        }
    }
}
