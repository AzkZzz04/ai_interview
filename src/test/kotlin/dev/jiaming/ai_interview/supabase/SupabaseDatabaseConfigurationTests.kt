package dev.jiaming.ai_interview.supabase

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.mock.env.MockEnvironment
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SupabaseDatabaseConfigurationTests {
    @TempDir lateinit var directory: Path

    @Test
    fun configuresBoundedPoolAndCertificateWithoutConnecting() {
        val certificate = Files.writeString(directory.resolve("root.crt"), "test certificate").toString()
        SupabaseDatabaseConfiguration().dataSource(environment(certificate)).use { source ->
            assertEquals(4, source.maximumPoolSize)
            assertEquals(0, source.minimumIdle)
            assertEquals(5_000, source.connectionTimeout)
            assertEquals(certificate, source.dataSourceProperties.getProperty("sslrootcert"))
            assertEquals("ai_interview_runtime.projectref", source.username)
        }
    }

    @Test
    fun rejectsMissingCredentialsAndUnsafeConnectionOverrides() {
        val certificate = Files.writeString(directory.resolve("root.crt"), "test certificate").toString()
        val config = SupabaseDatabaseConfiguration()
        assertFailsWith<IllegalArgumentException> { config.dataSource(environment(certificate).withProperty("spring.datasource.password", "")) }
        assertFailsWith<IllegalArgumentException> { config.dataSource(environment(certificate).withProperty("spring.datasource.username", "postgres")) }
        assertFailsWith<IllegalArgumentException> { config.dataSource(environment(directory.resolve("missing.crt").toString())) }
        for (url in listOf(
            URL.replace(":5432/", ":6543/"),
            URL.replace("aws-0-us-east-1.pooler.supabase.com", "db.projectref.supabase.co"),
            URL.replace("/postgres?", "/other?"),
            URL.replace("verify-full", "require"),
            URL.replace("public,extensions", "public"),
            "$URL&sslmode=disable",
            "$URL&sslfactory=org.postgresql.ssl.NonValidatingFactory",
            "$URL&user=postgres",
            "$URL&options=-csearch_path=public",
            "jdbc:postgresql://user:password@localhost:5432/postgres?sslmode=verify-full&currentSchema=public,extensions"
        )) {
            assertFailsWith<IllegalArgumentException> { config.dataSource(environment(certificate).withProperty("spring.datasource.url", url)) }
        }
    }

    private fun environment(certificate: String) = MockEnvironment()
        .withProperty("spring.datasource.url", URL)
        .withProperty("spring.datasource.username", "ai_interview_runtime.projectref")
        .withProperty("spring.datasource.password", "test-password")
        .withProperty("app.supabase.ssl-root-cert", certificate)

    companion object {
        private const val URL = "jdbc:postgresql://aws-0-us-east-1.pooler.supabase.com:5432/postgres?sslmode=verify-full&currentSchema=public,extensions"
    }
}
