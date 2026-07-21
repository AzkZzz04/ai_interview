package dev.jiaming.ai_interview.gemini;

import java.io.IOException;
import java.net.http.HttpRequest;

@FunctionalInterface
interface GeminiTransport {

	GeminiTransportResponse send(HttpRequest request) throws IOException, InterruptedException;
}

record GeminiTransportResponse(int statusCode, String body) {
}
