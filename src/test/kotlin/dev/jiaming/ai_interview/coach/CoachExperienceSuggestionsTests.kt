package dev.jiaming.ai_interview.coach

import com.fasterxml.jackson.databind.ObjectMapper
import dev.jiaming.ai_interview.document.DocumentSourceType
import dev.jiaming.ai_interview.document.ResolvedDocument
import dev.jiaming.ai_interview.suggestions.ExperienceSuggestionItem
import dev.jiaming.ai_interview.suggestions.SuggestionSource
import dev.jiaming.ai_interview.suggestions.SuggestionSourceInput
import dev.jiaming.ai_interview.suggestions.SuggestionSourceType
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito
import org.mockito.kotlin.argumentCaptor

class CoachExperienceSuggestionsTests {
    private val mapper = CoachResponseMapper(ObjectMapper().findAndRegisterModules())
    private val experience = SuggestionSource(SuggestionSourceType.EXPERIENCE, UUID.randomUUID(), "Ledger rewrite")
    private val resume = SuggestionSource(SuggestionSourceType.RESUME, UUID.randomUUID(), "Platform resume")

    @Test
    fun keepsOnlyCompleteItemsCitingProvidedSourcesAndNamesThemFromTheSource() {
        val response = mapper.parse("""{"items":[
            {"requirement":" Event-driven systems ","sourceId":"${experience.id.toString().uppercase()}","match":"Outbox pipeline","whyItFits":"Asked for.","guidance":"Add it under Acme."},
            {"requirement":"Kafka","sourceId":"${UUID.randomUUID()}","match":"m","whyItFits":"w","guidance":"g"},
            {"requirement":"Ownership","sourceId":"${resume.id}","match":"Led migration","whyItFits":"","guidance":"g"},
            {"requirement":"Ownership","sourceId":null,"match":"m","whyItFits":"w","guidance":"g"},
            null
        ]}""", ExperienceSuggestionsResponse::class.java)

        val normalized = mapper.normalizeExperienceSuggestions(response, listOf(experience, resume))

        assertThat(normalized.items).containsExactly(
            ExperienceSuggestionItem("Event-driven systems", experience, "Outbox pipeline", "Asked for.", "Add it under Acme.")
        )
    }

    @Test
    fun missingItemsNormalizeToNoStrongMatches() {
        assertThat(mapper.normalizeExperienceSuggestions(mapper.parse("{}", ExperienceSuggestionsResponse::class.java), listOf(experience)).items).isEmpty()
    }

    @Test
    fun promptLabelsEverySourceAndAsksForGuidanceInsteadOfBullets() {
        val prompt = CoachPromptBuilder().buildExperienceSuggestionsPrompt(
            "Selected resume text", "Kafka is required",
            listOf(experience to "Ledger rewrite\nBuilt an outbox", resume to "[contextId=resume:experience:0]\nRan Kafka"),
        )

        assertThat(prompt).contains("sourceId=${experience.id} type=EXPERIENCE name=Ledger rewrite", "Built an outbox")
        assertThat(prompt).contains("sourceId=${resume.id} type=RESUME name=Platform resume", "Ran Kafka")
        assertThat(prompt).contains("Kafka is required", "Selected resume text", "Give guidance, not finished resume bullets", "\"items\": []")
    }

    @Test
    fun narrowsOtherResumesAgainstTheJobDescriptionAndSendsExperiencesWhole() {
        val client = Mockito.mock(StructuredGenerationClient::class.java)
        val rag = Mockito.mock(CoachRagContextService::class.java)
        val service = AiResumeCoachService(client, rag, CoachPromptBuilder(), mapper, SimpleMeterRegistry())
        val selected = document(DocumentSourceType.RESUME, "Selected resume")
        val jobDescription = document(DocumentSourceType.JOB_DESCRIPTION, "Kafka is required")
        val other = document(DocumentSourceType.RESUME, "Long other resume")
        val description = "Designed an outbox-based ledger. ".repeat(120)
        Mockito.`when`(rag.suggestionSourceText(selected, jobDescription)).thenReturn("Selected resume")
        Mockito.`when`(rag.suggestionSourceText(other, jobDescription)).thenReturn("Narrowed: ran Kafka")
        Mockito.`when`(client.generateJson(anyString())).thenReturn("""{"items":[]}""")

        val result = service.suggestExperiences(selected, jobDescription, listOf(
            SuggestionSourceInput.Resume(resume, other), SuggestionSourceInput.Experience(experience, description),
        ))

        val prompt = argumentCaptor<String>()
        Mockito.verify(client).generateJson(prompt.capture())
        assertThat(prompt.firstValue).contains("Narrowed: ran Kafka", description).doesNotContain("Long other resume")
        assertThat(result.items).isEmpty()
    }

    private fun document(type: DocumentSourceType, text: String) = ResolvedDocument(type, UUID.randomUUID(), "hash-$text", text, emptyList())
}
