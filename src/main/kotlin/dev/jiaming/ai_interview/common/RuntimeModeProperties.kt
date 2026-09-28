package dev.jiaming.ai_interview.common

import java.util.Locale
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "app.runtime")
class RuntimeModeProperties(mode: String?) {
    val mode: String = (if (mode.isNullOrBlank()) "all" else mode.trim()).lowercase(Locale.ROOT).also {
        require(it == "all" || it == "api" || it == "worker") {
            "JOB_RUNTIME_MODE must be all, api, or worker"
        }
    }

    fun apiEnabled() = mode != "worker"
    fun workerEnabled() = mode != "api"
}
