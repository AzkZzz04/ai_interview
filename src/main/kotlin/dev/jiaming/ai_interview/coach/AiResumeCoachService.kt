package dev.jiaming.ai_interview.coach

import dev.jiaming.ai_interview.gemini.GeminiErrorCode
import dev.jiaming.ai_interview.gemini.GeminiException
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException

@Service
class AiResumeCoachService(
    private val generationClient: StructuredGenerationClient,
    private val ragContextService: CoachRagContextService,
    private val promptBuilder: CoachPromptBuilder,
    private val responseMapper: CoachResponseMapper,
    private val meterRegistry: MeterRegistry
) {
    fun assess(input: CoachAnalysisInput): AssessmentResponse {
        val context = ragContextService.assessmentContext(input)
        return responseMapper.normalizeAssessment(generateStructured(promptBuilder.buildAssessmentPrompt(input, context), AssessmentResponse::class.java), context.sourceContextIds)
    }

    fun generateQuestions(input: CoachAnalysisInput): InterviewQuestionsResponse {
        val context = ragContextService.questionContext(input)
        return responseMapper.normalizeQuestions(generateStructured(promptBuilder.buildQuestionPrompt(input, context), InterviewQuestionsResponse::class.java), context.sourceContextIds)
    }

    fun scoreAnswer(input: CoachFeedbackInput): AnswerFeedbackResponse {
        if (input.answerText().isNullOrBlank()) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Answer text is required")
        val context = ragContextService.feedbackContext(input)
        return responseMapper.normalizeFeedback(generateStructured(promptBuilder.buildFeedbackPrompt(input, context), AnswerFeedbackResponse::class.java), context.sourceContextIds)
    }

    private fun <T> generateStructured(prompt: String, responseType: Class<T>): T {
        val firstOutput = generationClient.generateJson(prompt)
        try { return responseMapper.parse(firstOutput, responseType) }
        catch (firstFailure: GeminiException) {
            if (firstFailure.code != GeminiErrorCode.INVALID_RESPONSE) throw firstFailure
            meterRegistry.counter("ai.gemini.schema_repair", "outcome", "attempted").increment()
            val parseError = firstFailure.cause?.message ?: firstFailure.message
            val repairedOutput = generationClient.generateJson(promptBuilder.buildRepairPrompt(prompt, firstOutput, parseError))
            try {
                val repaired = responseMapper.parse(repairedOutput, responseType)
                meterRegistry.counter("ai.gemini.schema_repair", "outcome", "succeeded").increment()
                return repaired
            } catch (secondFailure: GeminiException) {
                meterRegistry.counter("ai.gemini.schema_repair", "outcome", "failed").increment()
                throw GeminiException(GeminiErrorCode.INVALID_RESPONSE, "Gemini response remained invalid after one schema repair", secondFailure, false)
            }
        }
    }
}
