package dev.jiaming.ai_interview.coach

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CoachResumeScorePromptTests {
    @Test
    fun resumeScorePromptContainsOnlyTheProvidedResumeAndOptionalTitle() {
        val resumeText = "EXPERIENCE\nBuilt a payment API used by 20 services."

        val prompt = CoachPromptBuilder().buildResumeScorePrompt(resumeText, "Backend Engineer")

        assertThat(prompt).contains(resumeText, "Backend Engineer", "[X%]")
        assertThat(prompt).doesNotContain("job description", "seniority", "retrieved context")
    }
}
