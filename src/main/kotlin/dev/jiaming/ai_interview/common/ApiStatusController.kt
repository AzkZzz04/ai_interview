package dev.jiaming.ai_interview.common

import java.time.Instant
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.info.BuildProperties
import org.springframework.core.env.Environment
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api")
class ApiStatusController(environment: Environment, buildProperties: ObjectProvider<BuildProperties>) {
    private val environment = environment
    private val buildProperties = buildProperties.getIfAvailable()

    @GetMapping("/status")
    fun status(): Map<String, Any> = mapOf(
        "service" to environment.getProperty("spring.application.name", "ai_interview"),
        "build" to (buildProperties?.version ?: "dev"),
        "timestamp" to Instant.now().toString()
    )
}
