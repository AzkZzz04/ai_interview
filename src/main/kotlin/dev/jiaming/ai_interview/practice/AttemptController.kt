package dev.jiaming.ai_interview.practice

import dev.jiaming.ai_interview.common.RedisRequestGuard
import dev.jiaming.ai_interview.common.RequestValidation
import java.util.UUID
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

@RestController
class AttemptController(
    private val practiceService: PracticeService,
    private val requestGuard: RedisRequestGuard,
) {
    /** §7.5. A replayed Idempotency-Key returns the first committed 201, never ANSWER_UNCHANGED for its own attempt. */
    @PostMapping("/api/practice-sets/{setId}/questions/{questionId}/attempts")
    fun submit(@PathVariable setId: UUID, @PathVariable questionId: UUID, @RequestBody request: SubmitAttemptRequest): ResponseEntity<AttemptView> {
        val text = RequestValidation.answer(request.text)
        return requestGuard.withIdempotentHttpCache("attempt-submit", listOf(setId, questionId, text), AttemptView::class.java) {
            ResponseEntity.status(HttpStatus.CREATED).body(practiceService.submitAttempt(setId, questionId, text))
        }
    }

    /** §7.6. */
    @PostMapping("/api/attempts/{attemptId}/retry")
    fun retry(@PathVariable attemptId: UUID): ResponseEntity<AttemptView> = ResponseEntity.status(HttpStatus.ACCEPTED).body(
        requestGuard.withIdempotentRetryCache("attempt-retry", attemptId, AttemptView::class.java) { practiceService.retryAttempt(attemptId) }
    )
}
