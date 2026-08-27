package dev.jiaming.ai_interview.coach;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import dev.jiaming.ai_interview.document.DocumentChunk;
import dev.jiaming.ai_interview.document.DocumentSourceType;
import dev.jiaming.ai_interview.document.ResolvedDocument;

class CoachPromptBuilderSeniorityTests {

	private final CoachPromptBuilder promptBuilder = new CoachPromptBuilder();

	private final CoachRagContext context = new CoachRagContext(
		"test", "[contextId=resume:projects:0] Built a project", List.of("resume:projects:0"), false
	);

	@Test
	void internAssessmentPrioritizesPotentialWithoutSeniorExpectations() {
		String prompt = promptBuilder.buildAssessmentPrompt(analysisInput(), context);

		assertThat(prompt)
			.contains("Seniority: Intern")
			.contains("technical fundamentals", "relevant coursework", "learning potential")
			.contains("Do not penalize missing senior-level architecture");
	}

	@Test
	void internQuestionsUseTheConfiguredDifficultyMixAndBoundedScope() {
		String prompt = promptBuilder.buildQuestionPrompt(analysisInput(), context);

		assertThat(prompt)
			.contains("exactly 4 Warmup, 3 Core, and 1 Deep Dive")
			.contains("project decisions", "debugging", "testing", "collaboration")
			.contains("Keep the Deep Dive scoped");
	}

	@Test
	void internFeedbackRewardsCoachabilityAndFundamentals() {
		CoachFeedbackInput input = new CoachFeedbackInput(
			resume(), Optional.empty(), "Software Engineer", "Intern", "Explain your project", "Projects",
			List.of("clear trade-off"), "I built the API"
		);

		String prompt = promptBuilder.buildFeedbackPrompt(input, context);

		assertThat(prompt)
			.contains("correct fundamentals", "structured reasoning", "coachability")
			.contains("learning-oriented next step");
	}

	private CoachAnalysisInput analysisInput() {
		return new CoachAnalysisInput(resume(), Optional.empty(), "Software Engineer", "Intern");
	}

	private ResolvedDocument resume() {
		return new ResolvedDocument(
			DocumentSourceType.RESUME,
			UUID.randomUUID(),
			"hash",
			"PROJECTS\nBuilt a project",
			List.of(new DocumentChunk(0, "Projects", "Built a project", "resume:projects:0"))
		);
	}
}
