package dev.jiaming.ai_interview.jobs

import java.util.UUID
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Service

@Service
class JobProcessor(
    private val payloadDecoder: JobPayloadDecoder,
    private val handlerRegistry: JobHandlerRegistry,
    private val jobStore: BackgroundJobStore,
    private val materializationService: JobEffectMaterializationService,
    private val metrics: JobMetrics,
    private val objectMapper: ObjectMapper
) {
    fun process(job: BackgroundJob, leaseToken: UUID): JsonNode? {
        val handler = handlerRegistry.require(job.jobType)
        val payload = payloadDecoder.decode(job, handler.payloadType())
        val context = JobExecutionContext(job, leaseToken, jobStore, materializationService, metrics, objectMapper)
        try { return invoke(handler, payload, context) } finally { context.finish() }
    }

    @Suppress("UNCHECKED_CAST")
    private fun invoke(handler: JobHandler<*>, payload: Any, context: JobExecutionContext): JsonNode? =
        invokeTyped(handler as JobHandler<Any>, payload, context)

    private fun <P> invokeTyped(handler: JobHandler<P>, payload: Any, context: JobExecutionContext): JsonNode? =
        handler.handle(handler.payloadType().cast(payload), context)
}
