package dev.jiaming.ai_interview.supabase

import com.fasterxml.jackson.databind.ObjectMapper
import dev.jiaming.ai_interview.common.RuntimeModeProperties
import dev.jiaming.ai_interview.jobs.BackgroundJobStore
import dev.jiaming.ai_interview.jobs.JobStatusReader
import dev.jiaming.ai_interview.jobs.JobStatusReaderConfiguration
import io.github.jan.supabase.SupabaseClient
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.ApplicationContextInitializer
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class JobStatusReaderConfigurationTests {
    private val runner = ApplicationContextRunner()
        .withUserConfiguration(JobStatusReaderConfiguration::class.java, TestInfrastructure::class.java)

    @Test
    fun `uses JDBC locally and for Supabase worker but SDK for Supabase API and all`() {
        runner.run {
            assertNull(it.startupFailure)
            assertFalse(it.getBean(JobStatusReader::class.java) is SupabaseJobStatusReader)
        }
        runner.withInitializer(supabaseProfile).withPropertyValues("app.runtime.mode=worker").run {
            assertNull(it.startupFailure)
            assertTrue(it.getBeansOfType(SupabaseClient::class.java).isEmpty())
            assertFalse(it.getBean(JobStatusReader::class.java) is SupabaseJobStatusReader)
        }
        listOf("api", "all").forEach { mode ->
            runner.withInitializer(supabaseProfile)
                .withBean(SupabaseClient::class.java, java.util.function.Supplier { Mockito.mock(SupabaseClient::class.java) })
                .withPropertyValues("app.runtime.mode=$mode").run {
                    assertNull(it.startupFailure)
                    assertIs<SupabaseJobStatusReader>(it.getBean(JobStatusReader::class.java))
                }
        }
    }

    @Configuration(proxyBeanMethods = false)
    class TestInfrastructure {
        @Bean fun jobStore() = Mockito.mock(BackgroundJobStore::class.java)
        @Bean fun runtimeModeProperties(environment: Environment) = RuntimeModeProperties(environment.getProperty("app.runtime.mode"))
        @Bean fun objectMapper() = ObjectMapper()
    }

    private companion object {
        val supabaseProfile = ApplicationContextInitializer<ConfigurableApplicationContext> {
            it.environment.setActiveProfiles("supabase")
        }
    }
}
