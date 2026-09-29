package dev.jiaming.ai_interview.supabase

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.logging.LogLevel
import io.github.jan.supabase.postgrest.Postgrest
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.runBlocking
import org.springframework.beans.factory.DisposableBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import java.net.URI
import kotlin.time.Duration.Companion.seconds

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "app.supabase", name = ["enabled"], havingValue = "true")
@ConditionalOnExpression("!'\${app.runtime.mode:all}'.trim().equalsIgnoreCase('worker')")
class SupabaseClientConfig : DisposableBean {
    private var client: SupabaseClient? = null

    @Bean(destroyMethod = "")
    fun supabaseClient(environment: Environment): SupabaseClient = createClient(
        environment.getProperty("app.supabase.url").orEmpty(),
        environment.getProperty("app.supabase.secret-key").orEmpty()
    ).also { client = it }

    override fun destroy() {
        client?.let { runBlocking { it.close() } }
    }

    companion object {
        @OptIn(io.github.jan.supabase.annotations.SupabaseInternal::class)
        internal fun createClient(url: String, secretKey: String, engine: HttpClientEngine? = null): SupabaseClient {
            val uri = try { URI(url) } catch (_: Exception) {
                throw IllegalArgumentException("SUPABASE_URL must be a valid HTTPS project URL")
            }
            require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null &&
                uri.query == null && uri.fragment == null && uri.path.orEmpty() in listOf("", "/")) {
                "SUPABASE_URL must be an HTTPS project origin without credentials, query, or path"
            }
            require(secretKey.startsWith("sb_secret_") && secretKey.length > "sb_secret_".length && secretKey.none { it.isWhitespace() }) {
                "SUPABASE_SECRET_KEY must be a server secret key"
            }
            return createSupabaseClient(url, secretKey) {
                httpEngine = engine ?: CIO.create()
                requestTimeout = 5.seconds
                defaultLogLevel = LogLevel.NONE
                install(Postgrest) {
                    timeout = 5.seconds
                    maxRetries = 0
                }
                httpConfig {
                    followRedirects = false
                    install(createClientPlugin("SupabaseSecretKeyHeaders") {
                        onRequest { request, _ -> request.headers.remove(HttpHeaders.Authorization) }
                    })
                }
            }
        }
    }
}
