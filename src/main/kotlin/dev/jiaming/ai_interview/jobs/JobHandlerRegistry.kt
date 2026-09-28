package dev.jiaming.ai_interview.jobs

import java.util.EnumMap
import java.util.EnumSet
import org.springframework.stereotype.Component

@Component
class JobHandlerRegistry(registeredHandlers: List<JobHandler<*>>) {
    private val handlers: Map<JobType, JobHandler<*>>

    init {
        val indexed = EnumMap<JobType, JobHandler<*>>(JobType::class.java)
        for (handler in registeredHandlers) {
            val duplicate = indexed.putIfAbsent(handler.type(), handler)
            if (duplicate != null) throw IllegalStateException("Multiple job handlers registered for ${handler.type()}")
        }
        val missing = EnumSet.allOf(JobType::class.java).apply { removeAll(indexed.keys) }
        if (missing.isNotEmpty()) throw IllegalStateException("Missing job handlers for $missing")
        handlers = java.util.Collections.unmodifiableMap(indexed)
    }

    fun require(type: JobType): JobHandler<*> = handlers[type]
        ?: throw IllegalArgumentException("No job handler registered for $type")
}
