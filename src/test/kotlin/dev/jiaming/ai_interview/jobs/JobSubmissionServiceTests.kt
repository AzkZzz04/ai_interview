package dev.jiaming.ai_interview.jobs

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.jiaming.ai_interview.coach.AiAnalysisRequest
import dev.jiaming.ai_interview.common.LocalUserService
import dev.jiaming.ai_interview.common.RedisRequestGuard
import dev.jiaming.ai_interview.common.RuntimeModeProperties
import dev.jiaming.ai_interview.document.DocumentReferenceResolver
import dev.jiaming.ai_interview.document.DocumentSourceType
import dev.jiaming.ai_interview.document.ResolvedDocument
import dev.jiaming.ai_interview.document.ResolvedJobInputs
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.support.TransactionCallback
import org.springframework.transaction.support.TransactionOperations
import java.time.Duration
import java.time.Instant
import java.util.Optional
import java.util.UUID
import java.util.function.Supplier

class JobSubmissionServiceTests {

    private val jobStore = Mockito.mock(BackgroundJobStore::class.java)
    private val dispatcher = Mockito.mock(JobDispatcher::class.java)
    private val localUserService = Mockito.mock(LocalUserService::class.java)
    private val metrics = Mockito.mock(JobMetrics::class.java)
    private val requestGuard = Mockito.mock(RedisRequestGuard::class.java)
    private val documentResolver = Mockito.mock(DocumentReferenceResolver::class.java)
    private val userId = UUID.randomUUID()
    private val properties = JobProperties(
        true, "http://localhost:4566", "us-east-1", "test", "test", "jobs", "jobs-dlq", 3,
        2, 20, 300, 60, 3, 15, 300, 5_000, 30_000, 3_600_000, 120, 7
    )
    private val service = JobSubmissionService(
        jobStore,
        dispatcher,
        RequestFingerprintService(ObjectMapper()),
        localUserService,
        requestGuard,
        documentResolver,
        properties,
        RuntimeModeProperties("all"),
        metrics,
        ObjectMapper()
    )

    @BeforeEach
    fun passThroughIdempotencyGuard() {
        Mockito.doAnswer { invocation -> invocation.getArgument<Supplier<*>>(3).get() }
            .`when`(requestGuard)
            .withIdempotentRetryCache(
                any<String>(), any<Any>(), any<Class<JobAcceptedResponse>>(), any<Supplier<JobAcceptedResponse>>()
            )
    }

    @Test
    fun reusesMatchingJobWithinFiveMinuteWindow() {
        val existing = job(JobStatus.SUCCEEDED)
        Mockito.`when`(localUserService.localUserId()).thenReturn(userId)
        Mockito.`when`(jobStore.findReusable(userId, JobType.ANALYSIS, "same", Duration.ofMinutes(5)))
            .thenReturn(Optional.of(existing))

        val response = service.createOrReuse(
            JobType.ANALYSIS, "resume", null, mapOf("resume" to "same"), "same"
        )

        assertThat(response.reused()).isTrue()
        assertThat(response.jobId()).isEqualTo(existing.id())
        Mockito.verifyNoInteractions(dispatcher)
    }

    @Test
    fun createsAndDispatchesNewJobWhenNoReusableJobExists() {
        val created = job(JobStatus.QUEUED)
        Mockito.`when`(localUserService.localUserId()).thenReturn(userId)
        Mockito.`when`(jobStore.findReusable(userId, JobType.ANALYSIS, "new", Duration.ofMinutes(5)))
            .thenReturn(Optional.empty())
        Mockito.`when`(
            jobStore.createIfAbsent(eq(userId), eq(JobType.ANALYSIS), eq("resume"), eq(null), any(), eq("new"), eq(3))
        ).thenReturn(Optional.of(created))

        val response = service.createOrReuse(
            JobType.ANALYSIS, "resume", null, mapOf("resume" to "new"), "new"
        )

        assertThat(response.reused()).isFalse()
        Mockito.verify(dispatcher).dispatch(created.id())
        Mockito.verify(metrics).submitted(JobType.ANALYSIS)
    }

    @Test
    fun reusesWinningJobWhenConcurrentInsertDoesNotCreateARow() {
        val winner = job(JobStatus.QUEUED)
        Mockito.`when`(localUserService.localUserId()).thenReturn(userId)
        Mockito.`when`(jobStore.findReusable(userId, JobType.ANALYSIS, "race", Duration.ofMinutes(5)))
            .thenReturn(Optional.empty(), Optional.of(winner))
        Mockito.`when`(
            jobStore.createIfAbsent(
                eq(userId), eq(JobType.ANALYSIS), eq("resume"), eq(null), any(), eq("race"), eq(3)
            )
        ).thenReturn(Optional.empty())

        val response = service.createOrReuse(
            JobType.ANALYSIS, "resume", null, mapOf("resume" to "same"), "race"
        )

        assertThat(response.reused()).isTrue()
        assertThat(response.jobId()).isEqualTo(winner.id())
        Mockito.verifyNoInteractions(dispatcher)
    }

    @Test
    fun analysisJobPayloadContainsReferencesButNotResumeOrJobDescriptionText() {
        val resumeMarker = "PII_RESUME_MARKER_91F4"
        val jobMarker = "PII_JOB_MARKER_A23C"
        val resumeId = UUID.randomUUID()
        val jobDescriptionId = UUID.randomUUID()
        val resolved = resolved(resumeId, jobDescriptionId, resumeMarker, jobMarker)
        val request = AiAnalysisRequest(null, resumeMarker, null, jobMarker, "Backend Engineer", "Mid-level")
        val created = job(JobStatus.QUEUED)
        Mockito.`when`(localUserService.localUserId()).thenReturn(userId)
        Mockito.`when`(documentResolver.resolveForSubmission(userId, null, resumeMarker, null, jobMarker))
            .thenReturn(resolved)
        Mockito.`when`(jobStore.findReusable(eq(userId), eq(JobType.ANALYSIS), any(), eq(Duration.ofMinutes(5))))
            .thenReturn(Optional.empty())
        Mockito.`when`(
            jobStore.createIfAbsent(eq(userId), eq(JobType.ANALYSIS), eq("resume"), eq(resumeId), any(), any(), eq(3))
        ).thenReturn(Optional.of(created))

        service.submitAnalysis(request)

        val payload = ArgumentCaptor.forClass(JsonNode::class.java)
        Mockito.verify(jobStore).createIfAbsent(
            eq(userId), eq(JobType.ANALYSIS), eq("resume"), eq(resumeId), payload.capture(), any(), eq(3)
        )
        assertThat(payload.value.path("payloadVersion").asInt()).isEqualTo(2)
        assertThat(payload.value.path("resumeId").asText()).isEqualTo(resumeId.toString())
        assertThat(payload.value.path("jobDescriptionId").asText()).isEqualTo(jobDescriptionId.toString())
        assertThat(payload.value.toString()).doesNotContain(resumeMarker, jobMarker)
    }

    @Test
    fun idempotencyResponseIsStoredOnlyAfterTheSubmissionTransactionCompletes() {
        val events = mutableListOf<String>()
        val transactions = object : TransactionOperations {
            override fun <T : Any?> execute(action: TransactionCallback<T>): T {
                events += "transaction-started"
                val result = action.doInTransaction(Mockito.mock(TransactionStatus::class.java))
                events += "transaction-committed"
                return result
            }
        }
        val transactionalService = JobSubmissionService(
            jobStore,
            dispatcher,
            RequestFingerprintService(ObjectMapper()),
            localUserService,
            requestGuard,
            documentResolver,
            properties,
            RuntimeModeProperties("all"),
            metrics,
            ObjectMapper(),
            transactions
        )
        val resumeId = UUID.randomUUID()
        val resolved = ResolvedJobInputs(
            ResolvedDocument(DocumentSourceType.RESUME, resumeId, "resume-hash", "resume", emptyList()),
            Optional.empty()
        )
        val request = AiAnalysisRequest(resumeId, null, null, null, "Backend Engineer", "Mid-level")
        val created = job(JobStatus.QUEUED)
        Mockito.`when`(localUserService.localUserId()).thenReturn(userId)
        Mockito.`when`(documentResolver.resolveForSubmission(userId, resumeId, null, null, null)).thenReturn(resolved)
        Mockito.`when`(jobStore.findReusable(eq(userId), eq(JobType.ANALYSIS), any(), eq(Duration.ofMinutes(5))))
            .thenReturn(Optional.empty())
        Mockito.`when`(
            jobStore.createIfAbsent(eq(userId), eq(JobType.ANALYSIS), eq("resume"), eq(resumeId), any(), any(), eq(3))
        ).thenReturn(Optional.of(created))
        Mockito.doAnswer { invocation ->
            events += "idempotency-started"
            val response = invocation.getArgument<Supplier<*>>(3).get()
            events += "idempotency-response-stored"
            response
        }.`when`(requestGuard).withIdempotentRetryCache(
            any<String>(), any<Any>(), any<Class<JobAcceptedResponse>>(), any<Supplier<JobAcceptedResponse>>()
        )

        transactionalService.submitAnalysis(request)

        assertThat(events).containsExactly(
            "idempotency-started",
            "transaction-started",
            "transaction-committed",
            "idempotency-response-stored"
        )
    }

    private fun resolved(
        resumeId: UUID,
        jobDescriptionId: UUID,
        resumeText: String,
        jobDescriptionText: String
    ) = ResolvedJobInputs(
        ResolvedDocument(DocumentSourceType.RESUME, resumeId, "same-resume-hash", resumeText, emptyList()),
        Optional.of(
            ResolvedDocument(
                DocumentSourceType.JOB_DESCRIPTION,
                jobDescriptionId,
                "same-job-hash",
                jobDescriptionText,
                emptyList()
            )
        )
    )

    private fun job(status: JobStatus): BackgroundJob {
        val now = Instant.now()
        return BackgroundJob(
            UUID.randomUUID(), userId, JobType.ANALYSIS, "resume", null, status, JobStage.QUEUED,
            ObjectMapper().createObjectNode(), null, if (status == JobStatus.QUEUED) "new" else "same", 0, 3,
            null, null, null, now, now, now, if (status == JobStatus.QUEUED) null else now, null,
            if (status == JobStatus.SUCCEEDED) now else null, null, null
        )
    }
}
