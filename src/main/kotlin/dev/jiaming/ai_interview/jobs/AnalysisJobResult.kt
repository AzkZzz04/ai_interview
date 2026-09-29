package dev.jiaming.ai_interview.jobs

import dev.jiaming.ai_interview.coach.AssessmentResponse
import dev.jiaming.ai_interview.coach.InterviewQuestionsResponse

@JvmRecord
data class AnalysisJobResult(val assessment: AssessmentResponse, val questions: InterviewQuestionsResponse)
