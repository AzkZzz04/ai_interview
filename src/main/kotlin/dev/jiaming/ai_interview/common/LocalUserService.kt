package dev.jiaming.ai_interview.common

import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service

@Service
class LocalUserService(private val jdbcTemplate: JdbcTemplate) {
    fun localUserId(): UUID {
        val id = UUID.nameUUIDFromBytes(LOCAL_USER_EMAIL.toByteArray())
        jdbcTemplate.update(
            """
                INSERT INTO ai_interview_app.app_users (id, email, display_name)
                VALUES (?, ?, ?)
                ON CONFLICT DO NOTHING
                """.trimIndent(),
            id, LOCAL_USER_EMAIL, "Local Candidate"
        )
        return jdbcTemplate.queryForObject(
            "SELECT id FROM ai_interview_app.app_users WHERE email = ?",
            UUID::class.java,
            LOCAL_USER_EMAIL
        ) ?: throw IllegalStateException("Local user could not be loaded")
    }

    private companion object {
        const val LOCAL_USER_EMAIL = "local@ai-interview.dev"
    }
}
