package dev.jiaming.ai_interview

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class AiInterviewApplication {
    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            runApplication<AiInterviewApplication>(*args)
        }
    }
}
