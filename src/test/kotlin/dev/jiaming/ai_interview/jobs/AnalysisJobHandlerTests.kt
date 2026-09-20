package dev.jiaming.ai_interview.jobs

import dev.jiaming.ai_interview.coach.AiResumeCoachService
import dev.jiaming.ai_interview.coach.AssessmentResponse
import dev.jiaming.ai_interview.coach.AssessmentScores
import dev.jiaming.ai_interview.coach.CoachAnalysisInput
import dev.jiaming.ai_interview.coach.InterviewQuestionResponse
import dev.jiaming.ai_interview.coach.InterviewQuestionsResponse
import dev.jiaming.ai_interview.document.DocumentReferenceResolver
import dev.jiaming.ai_interview.document.DocumentSourceType
import dev.jiaming.ai_interview.document.ResolvedDocument
import dev.jiaming.ai_interview.document.ResolvedJobInputs
import dev.jiaming.ai_interview.interview.AnalysisPersistenceInput
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito
import java.util.Optional
import java.util.UUID

class AnalysisJobHandlerTests {

    private val coach = Mockito.mock(AiResumeCoachService::class.java)
    private val resolver = Mockito.mock(DocumentReferenceResolver::class.java)
    private val handler = AnalysisJobHandler(coach, resolver)
    private val context = Mockito.mock(JobExecutionContext::class.java)

    @Test
    fun checkpointsAssessmentBeforeGeneratingQuestions() {
        val payload = payload()
        val documents = documents(payload)
        val input = coachInput(documents, payload)
        val assessment = assessment()
        val questions = questions()
        Mockito.`when`(context.userId()).thenReturn(UUID.randomUUID())
        Mockito.`when`(resolver.resolveStrict(context.userId(), payload.resumeId(), payload.jobDescriptionId()))
            .thenReturn(documents)
        Mockito.`when`(coach.assess(input)).thenReturn(assessment)
        Mockito.`when`(coach.generateQuestions(input)).thenReturn(questions)

        handler.handle(payload, context)

        val order = Mockito.inOrder(context, coach)
        order.verify(context).stage(JobStage.ASSESSING_RESUME)
        order.verify(coach).assess(input)
        order.verify(context).saveCheckpoint("assessment", assessment)
        order.verify(context).materializeAssessment(any(AnalysisPersistenceInput::class.java), eq(assessment))
        order.verify(context).stage(JobStage.GENERATING_QUESTIONS)
        order.verify(coach).generateQuestions(input)
        order.verify(context).saveCheckpoint("questions", questions)
        order.verify(context).materializeQuestions(any(AnalysisPersistenceInput::class.java), eq(questions))
    }

    @Test
    fun retryUsesAssessmentCheckpointAndDoesNotRepeatAssessment() {
        val payload = payload()
        val documents = documents(payload)
        val assessment = assessment()
        val questions = questions()
        Mockito.`when`(context.userId()).thenReturn(UUID.randomUUID())
        Mockito.`when`(resolver.resolveStrict(context.userId(), payload.resumeId(), payload.jobDescriptionId()))
            .thenReturn(documents)
        Mockito.`when`(context.checkpoint("assessment", AssessmentResponse::class.java)).thenReturn(assessment)
        Mockito.`when`(context.checkpoint("questions", InterviewQuestionsResponse::class.java)).thenReturn(questions)

        handler.handle(payload, context)

        Mockito.verify(coach, Mockito.never()).assess(any(CoachAnalysisInput::class.java))
        Mockito.verify(coach, Mockito.never()).generateQuestions(any(CoachAnalysisInput::class.java))
        Mockito.verify(context).materializeAssessment(any(), eq(assessment))
        Mockito.verify(context).materializeQuestions(any(), eq(questions))
    }

    @Test
    fun questionFailureLeavesSavedAssessmentCheckpointForPartialResult() {
        val payload = payload()
        val documents = documents(payload)
        val input = coachInput(documents, payload)
        val assessment = assessment()
        Mockito.`when`(context.userId()).thenReturn(UUID.randomUUID())
        Mockito.`when`(resolver.resolveStrict(context.userId(), payload.resumeId(), payload.jobDescriptionId()))
            .thenReturn(documents)
        Mockito.`when`(coach.assess(input)).thenReturn(assessment)
        Mockito.`when`(coach.generateQuestions(input)).thenThrow(RuntimeException("timeout"))

        assertThatThrownBy { handler.handle(payload, context) }.hasMessage("timeout")

        Mockito.verify(context).saveCheckpoint("assessment", assessment)
        Mockito.verify(context).materializeAssessment(any(), eq(assessment))
        Mockito.verify(context, Mockito.never()).saveCheckpoint(eq("questions"), any())
        Mockito.verify(context, Mockito.never()).materializeQuestions(any(), any())
    }

    private fun payload() = AnalysisJobPayload(UUID.randomUUID(), UUID.randomUUID(), "Backend", "Mid-level")

    private fun documents(payload: AnalysisJobPayload) = ResolvedJobInputs(
        ResolvedDocument(DocumentSourceType.RESUME, payload.resumeId(), "resume-hash", "resume", emptyList()),
        Optional.of(
            ResolvedDocument(
                DocumentSourceType.JOB_DESCRIPTION,
                payload.jobDescriptionId(),
                "jd-hash",
                "job",
                emptyList()
            )
        )
    )

    private fun coachInput(documents: ResolvedJobInputs, payload: AnalysisJobPayload) = CoachAnalysisInput(
        documents.resume(), documents.jobDescription(), payload.targetRole(), payload.seniority()
    )

    private fun assessment() = AssessmentResponse(
        82,
        AssessmentScores(84, 80, 82, 83, 81),
        listOf("Clear backend experience"),
        listOf("Add metrics"),
        emptyList(),
        "Gemini",
        listOf("resume:experience:0")
    )

    private fun questions() = InterviewQuestionsResponse(
        listOf(
            InterviewQuestionResponse(
                "spring-design", "System Design", "Core", "How would you design it?",
                listOf("Retries"), listOf("resume:experience:0")
            )
        ),
        "gemini"
    )
}
