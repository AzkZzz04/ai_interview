package dev.jiaming.ai_interview.jobs

import com.fasterxml.jackson.databind.ObjectMapper
import dev.jiaming.ai_interview.common.RuntimeModeProperties
import dev.jiaming.ai_interview.supabase.SupabaseJobStatusReader
import io.github.jan.supabase.SupabaseClient
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import java.util.UUID

fun interface JobStatusReader {
    fun findForUser(jobId: UUID, userId: UUID): JobStatusResponse?
}

@Configuration(proxyBeanMethods = false)
class JobStatusReaderConfiguration {
    @Bean
    @Profile("!supabase")
    fun localJobStatusReader(jobStore: BackgroundJobStore): JobStatusReader = jdbcReader(jobStore)

    @Bean
    @Profile("supabase")
    fun supabaseProfileJobStatusReader(
        jobStore: BackgroundJobStore,
        runtimeMode: RuntimeModeProperties,
        clientProvider: ObjectProvider<SupabaseClient>,
        objectMapper: ObjectMapper
    ): JobStatusReader {
        if (!runtimeMode.apiEnabled()) return jdbcReader(jobStore)
        val client = clientProvider.ifAvailable
            ?: throw IllegalStateException("Supabase client is required for API job status reads")
        return SupabaseJobStatusReader(client, objectMapper)
    }

    private fun jdbcReader(jobStore: BackgroundJobStore) = JobStatusReader { jobId, userId ->
        jobStore.findForUser(jobId, userId).map(JobStatusResponse::from).orElse(null)
    }
}
