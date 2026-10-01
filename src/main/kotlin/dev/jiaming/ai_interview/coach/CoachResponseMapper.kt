package dev.jiaming.ai_interview.coach

import com.fasterxml.jackson.databind.ObjectMapper
import dev.jiaming.ai_interview.gemini.GeminiErrorCode
import dev.jiaming.ai_interview.gemini.GeminiException
import dev.jiaming.ai_interview.experience.ExperienceSplitItem
import dev.jiaming.ai_interview.experience.ExperienceSplitResult
import org.springframework.stereotype.Component
import java.io.IOException
import java.util.Locale
import java.util.UUID

@Component
class CoachResponseMapper(private val objectMapper: ObjectMapper) {
    fun <T> parse(json: String, responseType: Class<T>): T = try { objectMapper.readValue(json, responseType) }
    catch (exception: IOException) {
        throw GeminiException(GeminiErrorCode.INVALID_RESPONSE, "Gemini returned JSON that did not match the expected AI contract", exception, false)
    }

    fun normalizeExperienceSplit(response: ExperienceSplitResponse): ExperienceSplitResult {
        val items = response.items.orEmpty().filterNotNull().map { item ->
            val title = experienceText(item.title, "title", 1, 120)
            val description = experienceText(item.description, "description", 1, 4_000)
            val organization = item.organization?.let(::normalizeExperienceText)?.takeIf(String::isNotEmpty)?.also {
                if (it.length > 120) invalidExperience("organization")
            }
            ExperienceSplitItem(
                title,
                organization,
                experienceMonth(item.startDate, "startDate"),
                experienceMonth(item.endDate, "endDate"),
                description,
                null
            )
        }
        return ExperienceSplitResult(items)
    }

    fun normalizeAssessment(response: AssessmentResponse, fallbackSourceContextIds: List<String>): AssessmentResponse {
        val scores = response.scores ?: AssessmentScores(0, 0, 0, 0, 0)
        val normalized = AssessmentScores(clampScore(scores.technicalDepth), clampScore(scores.impact), clampScore(scores.clarity), clampScore(scores.relevance), clampScore(scores.ats))
        val overall = if (response.overallScore > 0) clampScore(response.overallScore) else average(normalized)
        return AssessmentResponse(overall, normalized, nonEmpty(response.strengths), nonEmpty(response.weaknesses),
            nonEmptyRecommendations(response.recommendations), "gemini", sourceContextIds(response.sourceContextIds, fallbackSourceContextIds))
    }

    fun normalizeQuestions(response: InterviewQuestionsResponse, fallbackSourceContextIds: List<String>): InterviewQuestionsResponse {
        val questions = response.questions.orEmpty().filterNotNull().filter { !it.questionText.isNullOrBlank() }.take(12).map { question ->
            InterviewQuestionResponse(fallback(question.id, slug(question.category + "-" + question.questionText)),
                fallback(question.category, "Interview"), normalizeDifficulty(question.difficulty), question.questionText,
                nonEmpty(question.expectedSignals), sourceContextIds(question.sourceContextIds, fallbackSourceContextIds))
        }
        if (questions.isEmpty()) throw GeminiException(GeminiErrorCode.INVALID_RESPONSE, "Gemini returned no usable interview questions", false)
        return InterviewQuestionsResponse(questions, "gemini")
    }

    fun normalizeFeedback(response: AnswerFeedbackResponse, fallbackSourceContextIds: List<String>): AnswerFeedbackResponse = AnswerFeedbackResponse(
        clampScore(response.score), fallback(response.summary, "The answer was scored, but Gemini did not provide a summary."),
        fallback(response.nextStep, "Add clearer structure, technical detail, and measurable outcomes."), nonEmpty(response.strengths),
        nonEmpty(response.gaps), nonEmpty(response.betterAnswerOutline), fallback(response.followUpQuestion, ""), "gemini",
        sourceContextIds(response.sourceContextIds, fallbackSourceContextIds)
    )

    private fun nonEmpty(values: List<String>?): List<String> = values.orEmpty().filterNotNull().map(String::trim).filter(String::isNotBlank).take(6)
        .ifEmpty { listOf("No specific evidence returned") }
    private fun sourceContextIds(responseIds: List<String>?, fallbackIds: List<String>): List<String> {
        val available = cleanContextIds(fallbackIds)
        val supplied = cleanContextIds(responseIds)
        if (available.isEmpty()) return supplied
        val retrieved = supplied.filter(available::contains)
        return retrieved.ifEmpty { available }
    }
    private fun cleanContextIds(values: List<String>?): List<String> = values.orEmpty().filterNotNull().map(String::trim).filter(String::isNotBlank).distinct().take(12)
    private fun nonEmptyRecommendations(values: List<RecommendationResponse>?): List<RecommendationResponse> {
        val cleaned = values.orEmpty().filterNotNull().filter { !it.message.isNullOrBlank() }.take(6).map {
            RecommendationResponse(fallback(it.section, "Resume"), normalizePriority(it.priority), it.message)
        }
        return cleaned.ifEmpty { listOf(RecommendationResponse("Resume", "high", "Add more specific evidence, scope, and measurable outcomes.")) }
    }
    private fun normalizePriority(value: String?) = fallback(value, "medium").lowercase(Locale.ROOT).let { if (it in setOf("high", "medium", "low")) it else "medium" }
    private fun normalizeDifficulty(value: String?) = when {
        fallback(value, "Core").lowercase(Locale.ROOT).contains("warm") -> "Warmup"
        fallback(value, "Core").lowercase(Locale.ROOT).contains("deep") -> "Deep Dive"
        else -> "Core"
    }
    private fun average(scores: AssessmentScores) = Math.round((scores.technicalDepth + scores.impact + scores.clarity + scores.relevance + scores.ats) / 5.0f)
    private fun clampScore(value: Int) = value.coerceIn(0, 100)
    private fun experienceText(value: String?, field: String, min: Int, max: Int): String = value?.let(::normalizeExperienceText)
        ?.takeIf { it.length in min..max } ?: invalidExperience(field)
    private fun normalizeExperienceText(value: String) = value.trim().replace(Regex("\\s+"), " ")
    private fun experienceMonth(value: String?, field: String): String? {
        val month = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
        if (!Regex("\\d{4}-(0[1-9]|1[0-2])").matches(month)) invalidExperience(field)
        return month
    }
    private fun invalidExperience(field: String): Nothing = throw GeminiException(
        GeminiErrorCode.INVALID_RESPONSE,
        "Gemini returned an experience with an invalid $field field",
        false
    )
    private fun slug(value: String?): String {
        val slug = fallback(value, "question").lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "-").replace(Regex("(^-|-$)"), "")
        if (slug.isBlank()) return UUID.randomUUID().toString()
        return slug.take(44)
    }
    private fun fallback(value: String?, default: String) = if (value.isNullOrBlank()) default else value.trim()
}
