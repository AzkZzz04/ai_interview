package dev.jiaming.ai_interview.common

import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate

// Writers lock the owner row before any of the owner's resume, target-job, fit, suggestion or practice rows: the order
// deletion locks them in, so a writer and a delete cannot deadlock.

/** For submissions: they do not block each other, but a delete holding the row FOR UPDATE makes them wait. */
fun JdbcTemplate.lockOwnerShared(userId: UUID) = lockOwner(userId, "FOR KEY SHARE")

/** For deletes and content saves that must exclude every other writer of the same owner. */
fun JdbcTemplate.lockOwnerExclusive(userId: UUID) = lockOwner(userId, "FOR UPDATE")

private fun JdbcTemplate.lockOwner(userId: UUID, lock: String) =
    check(queryForList("SELECT id FROM ai_interview_app.app_users WHERE id = ? $lock", userId).isNotEmpty()) { "Owner $userId does not exist" }
