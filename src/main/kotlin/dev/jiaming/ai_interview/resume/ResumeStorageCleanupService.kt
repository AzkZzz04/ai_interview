package dev.jiaming.ai_interview.resume

import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service

@Service
class ResumeStorageCleanupService(
    private val jdbcTemplate: JdbcTemplate,
    private val storageService: ResumeStorageService
) {
    /** Runs after the caller's resource transaction has settled, so this insert auto-commits before the S3 delete. */
    fun scheduleAndDelete(storageKey: String) {
        try {
            record(storageKey)
            deleteAndAcknowledge(storageKey)
        } catch (exception: RuntimeException) {
            log.warn("resume_storage_cleanup_schedule_failed storageKey={} reason={}", storageKey, exception.message)
        }
    }

    private fun record(storageKey: String) {
        jdbcTemplate.update(
            "INSERT INTO ai_interview_app.storage_cleanup (storage_key) VALUES (?) ON CONFLICT (storage_key) DO NOTHING",
            storageKey
        )
    }

    /** Idempotent S3 deletion runs outside the JDBC transaction; the durable intent is acknowledged only on success. */
    fun deleteAndAcknowledge(storageKey: String): Boolean {
        try {
            storageService.delete(storageKey)
        } catch (exception: RuntimeException) {
            log.warn("resume_storage_cleanup_delete_failed storageKey={} reason={}", storageKey, exception.message)
            return false
        }
        jdbcTemplate.update("DELETE FROM ai_interview_app.storage_cleanup WHERE storage_key = ?", storageKey)
        return true
    }

    fun retryPending(limit: Int): Int {
        val keys = jdbcTemplate.query(
            "SELECT storage_key FROM ai_interview_app.storage_cleanup ORDER BY created_at LIMIT ?",
            { rs, _ -> rs.getString("storage_key") }, limit
        )
        return keys.count { deleteAndAcknowledge(it) }
    }

    private companion object {
        val log = LoggerFactory.getLogger(ResumeStorageCleanupService::class.java)
    }
}
