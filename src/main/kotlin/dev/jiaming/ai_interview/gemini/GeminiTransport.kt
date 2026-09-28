package dev.jiaming.ai_interview.gemini

import java.io.IOException
import java.net.http.HttpRequest

fun interface GeminiTransport {
    @Throws(IOException::class, InterruptedException::class)
    fun send(request: HttpRequest): GeminiTransportResponse
}

@JvmRecord
data class GeminiTransportResponse(val statusCode: Int, val body: String)
