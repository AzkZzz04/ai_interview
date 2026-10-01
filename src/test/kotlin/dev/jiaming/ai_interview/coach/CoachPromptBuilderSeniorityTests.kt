package dev.jiaming.ai_interview.coach

import dev.jiaming.ai_interview.document.DocumentChunk
import dev.jiaming.ai_interview.document.DocumentSourceType
import dev.jiaming.ai_interview.document.ResolvedDocument
import java.util.Optional
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CoachPromptBuilderSeniorityTests {
	private val promptBuilder = CoachPromptBuilder()
	private val context = CoachRagContext("test", "[contextId=resume:projects:0] Built a project", listOf("resume:projects:0"), false)

	@Test
	fun internAssessmentPrioritizesPotentialWithoutSeniorExpectations() {
		val prompt = promptBuilder.buildAssessmentPrompt(analysisInput(), context)
		assertThat(prompt).contains("Seniority: Intern").contains("technical fundamentals", "relevant coursework", "learning potential").contains("Do not penalize missing senior-level architecture")
	}

	@Test
	fun internQuestionsUseTheConfiguredDifficultyMixAndBoundedScope() {
		val prompt = promptBuilder.buildQuestionPrompt(analysisInput(), context)
		assertThat(prompt).contains("exactly 4 Warmup, 3 Core, and 1 Deep Dive").contains("project decisions", "debugging", "testing", "collaboration").contains("Keep the Deep Dive scoped")
	}

	@Test
	fun internFeedbackRewardsCoachabilityAndFundamentals() {
		val input = CoachFeedbackInput(resume(), Optional.empty(), "Software Engineer", "Intern", "Explain your project", "Projects", listOf("clear trade-off"), "I built the API")
		val prompt = promptBuilder.buildFeedbackPrompt(input, context)
		assertThat(prompt).contains("correct fundamentals", "structured reasoning", "coachability").contains("learning-oriented next step")
	}

	@Test
	fun jobFitPromptRequiresEvidenceAndUsesTheJobDescriptionAsRequirements() {
		val jobDescription = ResolvedDocument(DocumentSourceType.JOB_DESCRIPTION, UUID.randomUUID(), "job-hash", "REQUIREMENTS\nKotlin and Kafka", emptyList())
		val prompt = promptBuilder.buildJobFitPrompt(
			CoachAnalysisInput(resume(), Optional.of(jobDescription), "Platform Engineer", null),
			CoachRagContext("fit", "Resume: Kotlin services\nJob: Kafka required", listOf("resume:projects:0"), false),
		)

		assertThat(prompt).contains("only the retrieved resume and job-description context", "specific supporting evidence", "missingRequirements")
		assertThat(prompt).contains("fitScore", "matchedRequirements", "feedback", "Platform Engineer", "Kafka required")
		assertThat(prompt).doesNotContain("Seniority")
	}

	@Test
	fun practiceQuestionPromptAsksForThreeToEightRationalesWithoutSeniority() {
		val jobDescription = ResolvedDocument(DocumentSourceType.JOB_DESCRIPTION, UUID.randomUUID(), "job-hash", "REQUIREMENTS\nKafka", emptyList())
		val prompt = promptBuilder.buildPracticeQuestionPrompt(
			CoachAnalysisInput(resume(), Optional.of(jobDescription), null, null),
			CoachRagContext("questions", "Job: Kafka required", listOf("job_description:requirements:0"), false),
		)

		assertThat(prompt).contains("between 3 and 8 questions", "rationale", "questionText", "expectedSignals", "Kafka required")
		assertThat(prompt).doesNotContain("Seniority", "Mid-level", "exactly 8")
	}

	private fun analysisInput() = CoachAnalysisInput(resume(), Optional.empty(), "Software Engineer", "Intern")
	private fun resume() = ResolvedDocument(DocumentSourceType.RESUME, UUID.randomUUID(), "hash", "PROJECTS\nBuilt a project", listOf(DocumentChunk(0, "Projects", "Built a project", "resume:projects:0")))
}
