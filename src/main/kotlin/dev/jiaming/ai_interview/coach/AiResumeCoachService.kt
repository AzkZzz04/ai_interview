package dev.jiaming.ai_interview.coach

import dev.jiaming.ai_interview.experience.ExperienceSplitResult
import dev.jiaming.ai_interview.gemini.GeminiErrorCode
import dev.jiaming.ai_interview.gemini.GeminiException
import dev.jiaming.ai_interview.practice.PracticeQuestionDrafts
import dev.jiaming.ai_interview.score.ResumeScoreResult
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

    fun assessJobFit(input: CoachAnalysisInput): dev.jiaming.ai_interview.fit.JobFitResult {
        val context = ragContextService.jobFitContext(input)
        return responseMapper.normalizeJobFit(generateStructured(promptBuilder.buildJobFitPrompt(input, context), JobFitResponse::class.java))
    }

    fun generateQuestions(input: CoachAnalysisInput): InterviewQuestionsResponse {
        val context = ragContextService.questionContext(input)
        return responseMapper.normalizeQuestions(generateStructured(promptBuilder.buildQuestionPrompt(input, context), InterviewQuestionsResponse::class.java), context.sourceContextIds)
    }

    fun generatePracticeQuestions(input: CoachAnalysisInput): PracticeQuestionDrafts {
        val context = ragContextService.practiceQuestionContext(input)
        return generateStructured(promptBuilder.buildPracticeQuestionPrompt(input, context), PracticeQuestionsResponse::class.java,
            responseMapper::normalizePracticeQuestions)
    }

    fun scoreAnswer(input: CoachFeedbackInput): AnswerFeedbackResponse {
        if (input.answerText().isNullOrBlank()) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Answer text is required")
        val context = ragContextService.feedbackContext(input)
        return responseMapper.normalizeFeedback(generateStructured(promptBuilder.buildFeedbackPrompt(input, context), AnswerFeedbackResponse::class.java), context.sourceContextIds)
    }

    fun scoreResume(resumeText: String, jobTitle: String?): ResumeScoreResult = responseMapper.normalizeResumeScore(
        generateStructured(promptBuilder.buildResumeScorePrompt(resumeText, jobTitle), ResumeScoreDraftResponse::class.java),
        jobTitle
    )

    fun splitExperience(text: String): ExperienceSplitResult = generateStructured(
        promptBuilder.buildExperienceSplitPrompt(text),
        ExperienceSplitResponse::class.java,
        responseMapper::normalizeExperienceSplit
    )

    private fun <T> generateStructured(prompt: String, responseType: Class<T>): T = generateStructured(prompt, responseType) { it }

    private fun <T, R> generateStructured(prompt: String, responseType: Class<T>, normalize: (T) -> R): R {
        val firstOutput = generationClient.generateJson(prompt)
        try { return normalize(responseMapper.parse(firstOutput, responseType)) }
        catch (firstFailure: GeminiException) {
            if (firstFailure.code != GeminiErrorCode.INVALID_RESPONSE) throw firstFailure
            meterRegistry.counter("ai.gemini.schema_repair", "outcome", "attempted").increment()
            val parseError = firstFailure.cause?.message ?: firstFailure.message
            val repairedOutput = generationClient.generateJson(promptBuilder.buildRepairPrompt(prompt, firstOutput, parseError))
            try {
                val repaired = normalize(responseMapper.parse(repairedOutput, responseType))
                meterRegistry.counter("ai.gemini.schema_repair", "outcome", "succeeded").increment()
                return repaired
            } catch (secondFailure: GeminiException) {
                meterRegistry.counter("ai.gemini.schema_repair", "outcome", "failed").increment()
                throw GeminiException(GeminiErrorCode.INVALID_RESPONSE, "Gemini response remained invalid after one schema repair", secondFailure, false)
            }
        }
    }
}
