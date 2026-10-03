package dev.jiaming.ai_interview.targetjob

import com.fasterxml.jackson.databind.ObjectMapper
import dev.jiaming.ai_interview.common.ApiExceptionHandler
import dev.jiaming.ai_interview.common.ApiRequestException
import dev.jiaming.ai_interview.common.RedisRequestGuard
import dev.jiaming.ai_interview.common.RedisUsageProperties
import java.time.Instant
import java.util.UUID
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup

class TargetJobControllerTests {
    private val service = Mockito.mock(TargetJobService::class.java)
    private val requestGuard = RedisRequestGuard(
        Mockito.mock(StringRedisTemplate::class.java),
        RedisUsageProperties(
            RedisUsageProperties.RateLimit(false, 60, 12, 20),
            RedisUsageProperties.Idempotency(false, 86_400),
        ),
        ObjectMapper().findAndRegisterModules(),
    )
    private val mockMvc = standaloneSetup(TargetJobController(service, requestGuard))
        .setControllerAdvice(ApiExceptionHandler())
        .build()

    @Test
    fun createsTargetJobWithTheContractResponse() {
        val detail = detail()
        Mockito.`when`(service.create("Backend role", TEXT)).thenReturn(TargetJobCreateResult(detail, false))

        mockMvc.perform(post("/api/target-jobs").contentType(MediaType.APPLICATION_JSON)
            .content("""{"name":"Backend role","text":"$TEXT"}"""))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.duplicate").value(false))
            .andExpect(jsonPath("$.targetJob.id").value(detail.id.toString()))
            .andExpect(jsonPath("$.targetJob.name").value("Backend role"))
            .andExpect(jsonPath("$.targetJob.text").value(TEXT))
    }

    @Test
    fun normalizedDuplicateReturnsOk() {
        Mockito.`when`(service.create("Backend role", TEXT)).thenReturn(TargetJobCreateResult(detail(), true))

        mockMvc.perform(post("/api/target-jobs").contentType(MediaType.APPLICATION_JSON)
            .content("""{"name":"Backend role","text":"$TEXT"}"""))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.duplicate").value(true))
    }

    @Test
    fun rejectsTextOutsideTheContractLength() {
        mockMvc.perform(post("/api/target-jobs").contentType(MediaType.APPLICATION_JSON)
            .content("""{"name":"Backend role","text":"${"x".repeat(99)}"}"""))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("text")))

        mockMvc.perform(post("/api/target-jobs").contentType(MediaType.APPLICATION_JSON)
            .content("""{"name":"Backend role","text":"${"x".repeat(20_001)}"}"""))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("text")))
    }

    @Test
    fun patchIgnoresTextAndReturnsOnlyTheRenamedSummary() {
        val id = UUID.randomUUID()
        val renamed = TargetJob(id, "Renamed", Instant.parse("2026-09-30T12:00:00Z"), Instant.parse("2026-09-30T12:05:00Z"))
        Mockito.`when`(service.rename(id, "Renamed")).thenReturn(renamed)

        mockMvc.perform(patch("/api/target-jobs/$id").contentType(MediaType.APPLICATION_JSON)
            .content("""{"name":"Renamed","text":"${"z".repeat(100)}"}"""))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.name").value("Renamed"))
            .andExpect(jsonPath("$.text").doesNotExist())
        Mockito.verify(service).rename(id, "Renamed")
    }

    @Test
    fun missingTargetJobUsesTheContractNotFoundCode() {
        val id = UUID.randomUUID()
        Mockito.`when`(service.get(id)).thenThrow(
            ApiRequestException(HttpStatus.NOT_FOUND, "TARGET_JOB_NOT_FOUND", "Target job was not found"),
        )

        mockMvc.perform(get("/api/target-jobs/$id"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("TARGET_JOB_NOT_FOUND"))
    }

    @Test
    fun deleteImpactAndDeleteUseTheContractStatusesAndFields() {
        val id = UUID.randomUUID()
        Mockito.`when`(service.deleteImpact(id)).thenReturn(TargetJobDeleteImpact())

        mockMvc.perform(get("/api/target-jobs/$id/delete-impact"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.scores").value(0))
            .andExpect(jsonPath("$.fits").value(0))
            .andExpect(jsonPath("$.suggestionSets").value(0))
            .andExpect(jsonPath("$.practiceSets").value(0))
            .andExpect(jsonPath("$.attempts").value(0))
            .andExpect(jsonPath("$.staleSuggestionSets").value(0))

        mockMvc.perform(delete("/api/target-jobs/$id")).andExpect(status().isNoContent)
        Mockito.verify(service).delete(id)
    }

    private fun detail() = TargetJobDetail(UUID.randomUUID(), "Backend role", Instant.parse("2026-09-30T12:00:00Z"), Instant.parse("2026-09-30T12:00:00Z"), TEXT)

    companion object {
        private val TEXT = "A".repeat(100)
    }
}
