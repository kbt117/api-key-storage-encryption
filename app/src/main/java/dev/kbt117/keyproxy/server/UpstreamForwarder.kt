package dev.kbt117.keyproxy.server

import android.os.SystemClock
import dev.kbt117.keyproxy.di.IoDispatcher
import dev.kbt117.keyproxy.domain.repository.SettingsRepository
import dev.kbt117.keyproxy.logging.ProxyLogBuffer
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.contentLength
import io.ktor.server.request.contentType
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.queryString
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondText
import io.ktor.utils.io.writeFully
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody

/**
 * Forwards a request that arrived on the loopback listener to the configured
 * upstream API, injecting the stored key, and streams the reply back.
 *
 * Design notes that are easy to get wrong:
 *
 * 1. **The response is streamed, not buffered.** Chat completions with
 *    `"stream": true` are server-sent events; buffering would defeat the point
 *    and would also blow the heap on a long generation. Chunks are copied to the
 *    Ktor channel and flushed immediately.
 * 2. **The request body is buffered, but bounded.** Requests are small (a
 *    prompt), so reading them fully is simpler and avoids OkHttp's duplex
 *    request-body requirement, which needs HTTP/2. A hard 8 MiB cap turns a
 *    hostile or buggy client into a clean `413` instead of an OOM.
 * 3. **Bodies are never parsed.** Treating the payload as opaque bytes means any
 *    OpenAI-compatible schema - including fields this app has never heard of -
 *    passes through untouched, and no JSON library is needed.
 * 4. **`Authorization` is always replaced.** Whatever the client sent is
 *    dropped, so the caller cannot make this proxy talk to an arbitrary host
 *    with its own credentials, and cannot exfiltrate the stored key by
 *    supplying a different one.
 */
@Singleton
class UpstreamForwarder @Inject constructor(
    private val client: OkHttpClient,
    private val settingsRepository: SettingsRepository,
    private val logBuffer: ProxyLogBuffer,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    suspend fun forward(call: ApplicationCall) {
        val startedAt = SystemClock.elapsedRealtime()
        val method = call.request.httpMethod.value
        val path = call.request.path()

        var upstreamHost = UNRESOLVED_HOST
        var statusCode: Int? = null
        var failure: String? = null

        try {
            val settings = settingsRepository.current()

            if (!settings.hasApiKey) {
                statusCode = HttpStatusCode.Unauthorized.value
                failure = "No API key stored. Open Settings and add one."
                respondError(call, HttpStatusCode.Unauthorized, "missing_api_key", failure)
                return
            }

            val upstreamUrl = try {
                UpstreamUrlBuilder.resolve(
                    baseUrl = settings.baseUrl,
                    requestPath = path,
                    rawQuery = call.request.queryString(),
                )
            } catch (e: IllegalArgumentException) {
                statusCode = HttpStatusCode.BadRequest.value
                failure = e.message ?: "Invalid base URL."
                respondError(call, HttpStatusCode.BadRequest, "invalid_base_url", failure)
                return
            }
            upstreamHost = UpstreamUrlBuilder.hostForLogging(upstreamUrl)

            val bodyBytes = try {
                readRequestBody(call, method)
            } catch (e: RequestTooLargeException) {
                statusCode = HttpStatusCode.PayloadTooLarge.value
                failure = e.message
                respondError(call, HttpStatusCode.PayloadTooLarge, "request_too_large", e.message)
                return
            } catch (e: Exception) {
                statusCode = HttpStatusCode.BadRequest.value
                failure = "Could not read the request body: ${e.message}"
                respondError(call, HttpStatusCode.BadRequest, "unreadable_body", failure)
                return
            }

            val upstreamRequest = buildUpstreamRequest(
                call = call,
                method = method,
                url = upstreamUrl,
                apiKey = settings.apiKey,
                bodyBytes = bodyBytes,
            )

            // `execute()` and the response-body reads are blocking socket I/O.
            // Ktor dispatches call handling on its own event loop, so blocking
            // there would stall every other in-flight request sharing that
            // thread - and with streaming responses that stall can last minutes.
            // Both are therefore moved to the IO dispatcher.
            val relayed = withContext(ioDispatcher) {
                val upstreamResponse = try {
                    client.newCall(upstreamRequest).execute()
                } catch (e: IOException) {
                    return@withContext RelayOutcome.TransportFailure(
                        describeTransportFailure(e),
                    )
                }

                upstreamResponse.use { response ->
                    runCatching { relayResponse(call, response, method) }
                        .fold(
                            onSuccess = {
                                RelayOutcome.Completed(
                                    response.code,
                                    if (response.code >= 400) {
                                        "Upstream returned ${response.code}."
                                    } else {
                                        null
                                    },
                                )
                            },
                            // A client hang-up mid-stream surfaces here. Let
                            // cancellation propagate; report everything else.
                            onFailure = { throw it },
                        )
                }
            }

            statusCode = relayed.statusCode
            failure = relayed.message
            if (relayed is RelayOutcome.TransportFailure) {
                respondError(
                    call,
                    HttpStatusCode.BadGateway,
                    "upstream_unreachable",
                    relayed.message,
                )
            }
        } catch (cancelled: CancellationException) {
            // The client hung up mid-stream. Not an error, and must be rethrown
            // so structured cancellation keeps working.
            failure = "Client disconnected."
            throw cancelled
        } catch (e: Exception) {
            failure = "Unexpected proxy failure: ${e.message}"
            runCatching {
                respondError(call, HttpStatusCode.InternalServerError, "proxy_error", failure)
            }
        } finally {
            logBuffer.record(
                method = method,
                path = path,
                upstreamHost = upstreamHost,
                statusCode = statusCode,
                durationMs = SystemClock.elapsedRealtime() - startedAt,
                message = failure,
            )
        }
    }

    // ---------------------------------------------------------------- bodies

    /**
     * Reads the request body, enforcing [MAX_REQUEST_BODY_BYTES].
     *
     * @return `null` when the method carries no body.
     */
    private suspend fun readRequestBody(call: ApplicationCall, method: String): ByteArray? {
        val declared = call.request.contentLength()
        if (declared != null && declared > MAX_REQUEST_BODY_BYTES) {
            throw RequestTooLargeException(
                "Request body of $declared bytes exceeds the ${MAX_REQUEST_BODY_BYTES} byte limit.",
            )
        }

        val methodCarriesBody = method.equals("POST", true) ||
            method.equals("PUT", true) ||
            method.equals("PATCH", true) ||
            (method.equals("DELETE", true) && declared != null && declared > 0L)

        if (!methodCarriesBody) return null
        if (declared == 0L) return ByteArray(0)

        val bytes = call.receive<ByteArray>()
        if (bytes.size > MAX_REQUEST_BODY_BYTES) {
            // Chunked transfer without an honest Content-Length.
            throw RequestTooLargeException(
                "Request body exceeds the ${MAX_REQUEST_BODY_BYTES} byte limit.",
            )
        }
        return bytes
    }

    // --------------------------------------------------------------- request

    private fun buildUpstreamRequest(
        call: ApplicationCall,
        method: String,
        url: String,
        apiKey: String,
        bodyBytes: ByteArray?,
    ): Request {
        val mediaType = call.request.contentType().toString().toMediaTypeOrNull()

        val requestBody: RequestBody? = when {
            bodyBytes != null -> bodyBytes.toRequestBody(mediaType)
            // OkHttp insists on a (possibly empty) body for these verbs.
            method.equals("POST", true) ||
                method.equals("PUT", true) ||
                method.equals("PATCH", true) -> ByteArray(0).toRequestBody(null)
            else -> null
        }

        return Request.Builder()
            .url(url)
            .method(method, requestBody)
            .apply { copyRequestHeaders(call) }
            // Replace, never merge: this is the whole point of the proxy.
            .header("Authorization", "Bearer $apiKey")
            .build()
    }

    private fun Request.Builder.copyRequestHeaders(call: ApplicationCall) {
        for ((name, values) in call.request.headers.entries()) {
            if (name.lowercase() in REQUEST_HEADERS_DROPPED) continue
            values.forEach { addHeader(name, it) }
        }
    }

    // -------------------------------------------------------------- response

    /**
     * Copies upstream headers and streams the body chunk by chunk.
     */
    private suspend fun relayResponse(
        call: ApplicationCall,
        response: okhttp3.Response,
        method: String,
    ) {
        val status = HttpStatusCode.fromValue(response.code)
        val upstreamContentType = response.header("Content-Type")
            ?.let { runCatching { ContentType.parse(it) }.getOrNull() }

        // Headers must be appended before the first byte is written, because
        // starting the response commits them.
        for ((name, value) in response.headers) {
            if (name.lowercase() in RESPONSE_HEADERS_DROPPED) continue
            call.response.header(name, value)
        }

        // HEAD must not produce a body even if upstream sent one.
        if (method.equals(HttpMethod.Head.value, ignoreCase = true)) {
            call.respond(status)
            return
        }

        call.respondBytesWriter(contentType = upstreamContentType, status = status) {
            // Widen to a nullable local so this compiles against both OkHttp 4
            // (nullable body) and OkHttp 5 (non-null body).
            val body: ResponseBody? = response.body
            val source = body?.source() ?: return@respondBytesWriter
            val buffer = ByteArray(STREAM_BUFFER_SIZE)
            try {
                while (true) {
                    val read = source.read(buffer, 0, buffer.size)
                    if (read == -1) break
                    writeFully(buffer, 0, read)
                    // Flush per chunk: this is what makes SSE arrive live
                    // instead of in one blob when the stream closes.
                    flush()
                }
            } finally {
                runCatching { source.close() }
            }
        }
    }

    // ---------------------------------------------------------------- errors

    private suspend fun respondError(
        call: ApplicationCall,
        status: HttpStatusCode,
        code: String,
        message: String?,
    ) {
        // Mirrors OpenAI's error envelope so existing client libraries surface
        // the message instead of failing to parse the body.
        val payload = """{"error":{"message":${jsonString(message ?: status.description)},""" +
            """"type":"keyproxy_error","code":"$code"}}"""
        runCatching {
            call.respondText(payload, ContentType.Application.Json, status)
        }
    }

    /** Minimal JSON string escaping; avoids pulling in a JSON dependency. */
    private fun jsonString(raw: String): String = buildString {
        append('"')
        raw.forEach { ch ->
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (ch < ' ') append("\\u%04x".format(ch.code)) else append(ch)
            }
        }
        append('"')
    }

    /**
     * Turns an [IOException] into something a user can act on, without echoing
     * the URL (which for a misconfigured base URL could contain a key).
     */
    private fun describeTransportFailure(e: IOException): String = when {
        e is java.net.UnknownHostException ->
            "Could not resolve the upstream host. Check the Base API URL in Settings."
        e is java.net.SocketTimeoutException ->
            "Timed out connecting to the upstream host."
        e is javax.net.ssl.SSLException ->
            "TLS handshake with the upstream host failed: ${e.message}"
        else -> "Upstream request failed: ${e.message}"
    }

    private class RequestTooLargeException(message: String) : IOException(message)

    /** Result of the upstream exchange, carried back off the IO dispatcher. */
    private sealed interface RelayOutcome {
        val statusCode: Int?
        val message: String?

        data class Completed(override val statusCode: Int, override val message: String?) :
            RelayOutcome

        /** Never reached the upstream; rendered as a 502 by the caller. */
        data class TransportFailure(override val message: String) : RelayOutcome {
            override val statusCode: Int? = null
        }
    }

    private companion object {
        const val UNRESOLVED_HOST = "unresolved"

        /** 8 MiB. Prompts are small; this only exists to bound memory. */
        const val MAX_REQUEST_BODY_BYTES = 8L * 1024 * 1024

        /** 8 KiB per streamed chunk. */
        const val STREAM_BUFFER_SIZE = 8 * 1024

        /**
         * RFC 9110 §7.6.1 hop-by-hop headers, plus the end-to-end headers that
         * either describe the *client's* connection or are recomputed for the
         * upstream one.
         */
        val REQUEST_HEADERS_DROPPED = setOf(
            "connection",
            "keep-alive",
            "proxy-authenticate",
            "proxy-authorization",
            "te",
            "trailer",
            "transfer-encoding",
            "upgrade",
            "host",
            "content-length",
            "authorization",
        )

        /**
         * `content-length` is dropped because the streamed reply is sent
         * chunked. `content-encoding` is deliberately kept: we forward the
         * client's `Accept-Encoding` verbatim, so OkHttp does not transparently
         * decompress and the client must be told what encoding it is getting.
         */
        val RESPONSE_HEADERS_DROPPED = setOf(
            "connection",
            "keep-alive",
            "proxy-authenticate",
            "proxy-authorization",
            "te",
            "trailer",
            "transfer-encoding",
            "upgrade",
            "content-length",
        )
    }
}
