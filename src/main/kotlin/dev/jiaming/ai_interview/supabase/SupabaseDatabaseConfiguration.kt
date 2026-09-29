package dev.jiaming.ai_interview.supabase

import com.zaxxer.hikari.HikariDataSource
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.core.env.Environment
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

@Configuration(proxyBeanMethods = false)
@Profile("supabase")
class SupabaseDatabaseConfiguration {
    @Bean
    fun dataSource(environment: Environment): HikariDataSource {
        val url = required(environment, "spring.datasource.url")
        val username = required(environment, "spring.datasource.username")
        val password = required(environment, "spring.datasource.password")
        val certificate = required(environment, "app.supabase.ssl-root-cert")
        validateConnection(url, username, certificate)
        return HikariDataSource().apply {
            jdbcUrl = url
            this.username = username
            this.password = password
            maximumPoolSize = environment.getProperty("spring.datasource.hikari.maximum-pool-size", Int::class.java, 4)
            minimumIdle = 0
            connectionTimeout = 5_000
            addDataSourceProperty("sslrootcert", certificate)
        }
    }

    companion object {
        internal fun validateConnection(url: String, username: String, certificate: String) {
            require(url.startsWith("jdbc:postgresql://")) { "DATABASE_URL must be a PostgreSQL JDBC URL" }
            val uri = try { URI(url.removePrefix("jdbc:")) } catch (_: Exception) {
                throw IllegalArgumentException("DATABASE_URL is invalid")
            }
            require(!uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null) {
                "DATABASE_URL must contain a host and must not embed credentials"
            }
            val pairs = try {
                uri.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }.map { part ->
                    val entry = part.split('=', limit = 2)
                    URLDecoder.decode(entry[0], StandardCharsets.UTF_8) to
                        URLDecoder.decode(entry.getOrElse(1) { "" }, StandardCharsets.UTF_8)
                }
            } catch (_: Exception) { throw IllegalArgumentException("DATABASE_URL parameters are invalid") }
            require(pairs.map { it.first.lowercase() }.distinct().size == pairs.size) { "Duplicate JDBC URL parameters are not allowed" }
            val properties = pairs.toMap()
            require(properties["sslmode"] == "verify-full") { "DATABASE_URL must set sslmode=verify-full" }
            require(properties["currentSchema"] == "public,extensions") { "DATABASE_URL must set currentSchema=public,extensions" }
            require(properties.keys.none { it.lowercase() in setOf("user", "password", "sslfactory", "sslhostnameverifier", "sslrootcert", "ssl", "options") }) {
                "DATABASE_URL must not override credentials, certificate verification, or session settings"
            }
            require(username.matches(Regex("ai_interview_runtime(?:\\.[a-z0-9]+)?"))) {
                "DATABASE_USERNAME must use the dedicated ai_interview_runtime role"
            }
            require(Files.isRegularFile(Path.of(certificate)) && Files.isReadable(Path.of(certificate))) {
                "SUPABASE_SSL_ROOT_CERT must point to a readable CA certificate file"
            }
        }

        private fun required(environment: Environment, property: String): String =
            environment.getProperty(property)?.takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException("Missing required Supabase property: $property")
    }
}
