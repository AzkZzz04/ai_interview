package dev.jiaming.ai_interview.common

import jakarta.servlet.http.HttpServletRequest
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.boot.tomcat.autoconfigure.servlet.TomcatServletWebServerAutoConfiguration
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Boots only embedded Tomcat and a remote-address echo, with the real application.yaml on the classpath,
 * so the `server.forward-headers-strategy` the app ships with decides what `RedisRequestGuard` sees as the client.
 */
@SpringBootTest(classes = [ForwardedClientIntegrationTests.EchoServer::class], webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ForwardedClientIntegrationTests(
    @LocalServerPort private val port: Int,
    @Value("\${server.forward-headers-strategy:}") private val forwardHeadersStrategy: String
) {
    private val client = HttpClient.newHttpClient()

    @Test
    fun applicationYamlTrustsForwardedHeadersNatively() {
        assertThat(forwardHeadersStrategy).isEqualTo("native")
    }

    @Test
    fun loopbackProxyForwardsTheClientAddress() {
        assertThat(remoteAddrFor("203.0.113.7")).isEqualTo("203.0.113.7")
    }

    @Test
    fun loopbackProxyUsesTheLastUntrustedAddress() {
        assertThat(remoteAddrFor("198.51.100.9, 203.0.113.7")).isEqualTo("203.0.113.7")
    }

    private fun remoteAddrFor(forwardedFor: String): String {
        val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/remote-addr"))
            .header("X-Forwarded-For", forwardedFor)
            .build()
        return client.send(request, HttpResponse.BodyHandlers.ofString()).body()
    }

    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration(TomcatServletWebServerAutoConfiguration::class, DispatcherServletAutoConfiguration::class)
    @Import(RemoteAddrController::class)
    class EchoServer

    @RestController
    class RemoteAddrController {
        @GetMapping("/remote-addr")
        fun remoteAddr(request: HttpServletRequest): String = request.remoteAddr
    }
}
