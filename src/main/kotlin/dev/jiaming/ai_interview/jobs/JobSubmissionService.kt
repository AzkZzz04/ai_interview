package dev.jiaming.ai_interview.jobs

import java.util.Locale
import java.util.Objects
import java.util.Optional
import java.util.UUID
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionOperations
import org.springframework.web.server.ResponseStatusException
import dev.jiaming.ai_interview.coach.AiAnalysisRequest
import dev.jiaming.ai_interview.common.LocalUserService
import dev.jiaming.ai_interview.common.RedisRequestGuard
import dev.jiaming.ai_interview.common.RuntimeModeProperties
import dev.jiaming.ai_interview.document.DocumentReferenceResolver

@Service
class JobSubmissionService @Autowired constructor(
    private val jobStore: BackgroundJobStore, private val dispatcher: JobDispatcher,
    private val fingerprintService: RequestFingerprintService, private val localUserService: LocalUserService,
    private val requestGuard: RedisRequestGuard, private val documentResolver: DocumentReferenceResolver,
    private val properties: JobProperties, private val runtimeMode: RuntimeModeProperties,
    private val metrics: JobMetrics, private val objectMapper: ObjectMapper,
    private val transactionOperations: TransactionOperations
) {
    constructor(jobStore: BackgroundJobStore, dispatcher: JobDispatcher, fingerprintService: RequestFingerprintService,
                localUserService: LocalUserService, requestGuard: RedisRequestGuard, documentResolver: DocumentReferenceResolver,
                properties: JobProperties, runtimeMode: RuntimeModeProperties, metrics: JobMetrics, objectMapper: ObjectMapper) :
        this(jobStore, dispatcher, fingerprintService, localUserService, requestGuard, documentResolver, properties, runtimeMode,
            metrics, objectMapper, TransactionOperations.withoutTransaction())

    fun submit(type: JobType, resourceType: String?, resourceId: UUID?, payload: Any): JobAcceptedResponse {
        assertApiAvailable()
        if (type != JobType.RESUME_EXTRACTION) requestGuard.assertAiAllowed(AI_JOB_ACTION)
        return createOrReuse(type, resourceType, resourceId, payload, resourceId?.let { fingerprint(type.name, it) })
    }
    fun submitAnalysis(request: AiAnalysisRequest): JobAcceptedResponse {
        assertApiAvailable()
        return submitWithHttpProtection("analysis", request) {
            requestGuard.assertAiAllowed(AI_JOB_ACTION)
            inTransaction { submitAnalysisTransaction(request) }
        }
    }
    private fun submitAnalysisTransaction(request: AiAnalysisRequest): JobAcceptedResponse {
        val userId = localUserService.localUserId()
        val inputs = documentResolver.resolveForSubmission(userId, request.resumeId, request.resumeText, request.jobDescriptionId, request.jobDescription)
        val payload = AnalysisJobPayload(inputs.resume().resourceId(), inputs.jobDescription().map { it.resourceId() }.orElse(null), trim(request.targetRole), trim(request.seniority))
        val source = AnalysisFingerprint(inputs.resume().contentHash(), inputs.jobDescription().map { it.contentHash() }.orElse(""), normalizeLabel(request.targetRole), normalizeLabel(request.seniority))
        return createOrReuse(JobType.ANALYSIS, "resume", inputs.resume().resourceId(), payload, fingerprint("analysis", source))
    }
    fun fingerprint(action: String, source: Any): String = fingerprintService.fingerprint(action, source)
    fun assertApiAvailable() {
        if (!properties.enabled || !runtimeMode.apiEnabled()) throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
            "This process is running in worker-only mode and does not accept background jobs")
    }
    fun findReusable(type: JobType, fingerprint: String?): Optional<JobAcceptedResponse> = if (fingerprint == null) Optional.empty()
        else jobStore.findReusable(localUserService.localUserId(), type, fingerprint).map { JobAcceptedResponse.from(it, true) }
    fun createOrReuse(type: JobType, resourceType: String?, resourceId: UUID?, requestPayload: Any, fingerprint: String?): JobAcceptedResponse {
        val existing = findReusable(type, fingerprint)
        if (existing.isPresent) return existing.get()
        val created = jobStore.createIfAbsent(localUserService.localUserId(), type, resourceType, resourceId,
            objectMapper.valueToTree(requestPayload), fingerprint, properties.maxAttempts)
        if (created.isEmpty) return findReusable(type, fingerprint).orElseThrow { IllegalStateException("A matching active job won the submission race but could not be loaded") }
        val job = created.get()
        metrics.submitted(type)
        log.info("job_submitted jobId={} type={} userId={} resourceType={} resourceId={}", job.id, type, job.userId, resourceType, resourceId)
        dispatchAfterCommit(job.id)
        return JobAcceptedResponse.from(job, false)
    }
    fun <T> withIdempotency(action: String, fingerprintSource: Any, type: Class<T>, work: java.util.function.Supplier<T>): T =
        requestGuard.withIdempotentRetryCache(action, fingerprintSource, type, work)
    private fun submitWithHttpProtection(action: String, fingerprintSource: Any,
                                         work: java.util.function.Supplier<JobAcceptedResponse>): JobAcceptedResponse =
        requestGuard.withIdempotentRetryCache(action, fingerprintSource, JobAcceptedResponse::class.java, work)
    private fun dispatchAfterCommit(jobId: UUID) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) { tryDispatch(jobId); return }
        TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
            override fun afterCommit() = tryDispatch(jobId)
        })
    }
    private fun tryDispatch(jobId: UUID) { try { dispatcher.dispatch(jobId) } catch (exception: RuntimeException) { log.warn("job_initial_dispatch_failed jobId={} reason={}", jobId, exception.message) } }
    private fun <T> inTransaction(work: java.util.function.Supplier<T>): T = Objects.requireNonNull(transactionOperations.execute { work.get() })
    private fun normalizeLabel(value: String?) = trim(value).replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)
    private fun trim(value: String?) = value?.trim() ?: ""
    private data class AnalysisFingerprint(val resumeHash: String, val jobDescriptionHash: String, val targetRole: String, val seniority: String)
    companion object {
        const val AI_JOB_ACTION = "ai-job"
        private val log = LoggerFactory.getLogger(JobSubmissionService::class.java)
    }
}
