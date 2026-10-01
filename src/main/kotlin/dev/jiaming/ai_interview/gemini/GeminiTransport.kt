package dev.jiaming.ai_interview.gemini

import java.io.IOException
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

fun interface GeminiTransport {
    @Throws(IOException::class, InterruptedException::class)
    fun send(request: HttpRequest): GeminiTransportResponse
}

@JvmRecord
data class GeminiTransportResponse(val statusCode: Int, val body: String)

/** The JDK HTTP client both chat providers send through. */
fun jdkTransport(): GeminiTransport {
    val httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    return GeminiTransport { request ->
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        GeminiTransportResponse(response.statusCode(), response.body())
    }
}
