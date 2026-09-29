package dev.jiaming.ai_interview.common

import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service

@Service
class LocalUserService(private val jdbcTemplate: JdbcTemplate) {
    fun localUserId(): UUID = jdbcTemplate.queryForObject(
        """
            INSERT INTO ai_interview_app.app_users (id, email, display_name)
            VALUES (?, ?, ?)
            ON CONFLICT (email) DO UPDATE
            SET updated_at = now()
            RETURNING id
            """.trimIndent(),
        UUID::class.java,
        UUID.nameUUIDFromBytes(LOCAL_USER_EMAIL.toByteArray()),
        LOCAL_USER_EMAIL,
        "Local Candidate"
    )!!

    private companion object {
        const val LOCAL_USER_EMAIL = "local@ai-interview.dev"
    }
}
