package dev.jiaming.ai_interview.resume

import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.whenever
import org.springframework.jdbc.core.JdbcTemplate

class ResumeStorageCleanupServiceTests {
    private val jdbc = Mockito.mock(JdbcTemplate::class.java)
    private val storage = Mockito.mock(ResumeStorageService::class.java)
    private val service = ResumeStorageCleanupService(jdbc, storage)

    @Test
    fun commitsCleanupIntentBeforeDeletingAndAcknowledgesOnlyAfterSuccess() {
        whenever(jdbc.update(Mockito.anyString(), Mockito.any())).thenReturn(1)

        service.scheduleAndDelete("resumes/orphan.pdf")

        val order = inOrder(jdbc, storage)
        order.verify(jdbc).update(
            "INSERT INTO ai_interview_app.storage_cleanup (storage_key) VALUES (?) ON CONFLICT (storage_key) DO NOTHING",
            "resumes/orphan.pdf"
        )
        order.verify(storage).delete("resumes/orphan.pdf")
        order.verify(jdbc).update("DELETE FROM ai_interview_app.storage_cleanup WHERE storage_key = ?", "resumes/orphan.pdf")
    }

    @Test
    fun leavesDurableIntentWhenObjectDeleteFails() {
        whenever(jdbc.update(Mockito.anyString(), Mockito.any())).thenReturn(1)
        Mockito.doThrow(IllegalStateException("offline")).`when`(storage).delete("resumes/orphan.pdf")

        service.scheduleAndDelete("resumes/orphan.pdf")

        Mockito.verify(jdbc, Mockito.never()).update(
            "DELETE FROM ai_interview_app.storage_cleanup WHERE storage_key = ?", "resumes/orphan.pdf"
        )
    }
}
