package dev.jiaming.ai_interview.jobs

import com.fasterxml.jackson.databind.JsonNode

interface JobHandler<P> {
    fun type(): JobType
    fun payloadType(): Class<P>
    fun handle(payload: P, context: JobExecutionContext): JsonNode?
}
