package dev.jiaming.ai_interview.storage

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "app.storage")
class StorageProperties(
    val endpoint: String?,
    val region: String?,
    val bucket: String?,
    val accessKey: String?,
    val secretKey: String?,
    pendingRetentionHours: Int
) {
    val pendingRetentionHours = if (pendingRetentionHours <= 0) 24 else pendingRetentionHours
}
