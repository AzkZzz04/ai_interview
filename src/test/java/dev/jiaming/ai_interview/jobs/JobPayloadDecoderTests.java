package dev.jiaming.ai_interview.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import dev.jiaming.ai_interview.coach.AiAnalysisRequest;
import dev.jiaming.ai_interview.document.DocumentReferenceResolver;
import dev.jiaming.ai_interview.document.DocumentSourceType;
import dev.jiaming.ai_interview.document.ResolvedDocument;
import dev.jiaming.ai_interview.document.ResolvedJobInputs;

class JobPayloadDecoderTests {

	private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

	private final BackgroundJobStore jobStore = mock(BackgroundJobStore.class);

	private final DocumentReferenceResolver resolver = mock(DocumentReferenceResolver.class);

	private final JobPayloadDecoder decoder = new JobPayloadDecoder(objectMapper, jobStore, resolver);

	@Test
	void readsCurrentPayloadWithoutMaterializingDocuments() {
		AnalysisJobPayload payload = new AnalysisJobPayload(
			UUID.randomUUID(), UUID.randomUUID(), "Backend", "Mid-level"
		);
		BackgroundJob job = job(objectMapper.valueToTree(payload));

		AnalysisJobPayload decoded = (AnalysisJobPayload) decoder.decode(
			job, UUID.randomUUID(), AnalysisJobPayload.class
		);

		assertThat(decoded).isEqualTo(payload);
		verifyNoInteractions(resolver);
		verify(jobStore, never()).replaceRequestPayload(org.mockito.ArgumentMatchers.any(),
			org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
	}

	@Test
	void upgradesLegacyTextPayloadToStableResourceReferences() {
		String resumeMarker = "LEGACY_RESUME_PII_04C";
		String jobMarker = "LEGACY_JOB_PII_88A";
		AiAnalysisRequest legacy = new AiAnalysisRequest(
			resumeMarker, jobMarker, "Backend", "Mid-level"
		);
		BackgroundJob job = job(objectMapper.valueToTree(legacy));
		UUID leaseToken = UUID.randomUUID();
		UUID resumeId = UUID.randomUUID();
		UUID jobDescriptionId = UUID.randomUUID();
		ResolvedJobInputs resolved = new ResolvedJobInputs(
			new ResolvedDocument(DocumentSourceType.RESUME, resumeId, "resume-hash", resumeMarker, List.of()),
			Optional.of(new ResolvedDocument(
				DocumentSourceType.JOB_DESCRIPTION, jobDescriptionId, "jd-hash", jobMarker, List.of()
			))
		);
		when(resolver.resolveLegacy(job.userId(), null, resumeMarker, null, jobMarker)).thenReturn(resolved);

		AnalysisJobPayload decoded = decoder.analysis(job, leaseToken);

		assertThat(decoded.resumeId()).isEqualTo(resumeId);
		assertThat(decoded.jobDescriptionId()).isEqualTo(jobDescriptionId);
		var upgradedJson = objectMapper.valueToTree(decoded);
		assertThat(upgradedJson.toString()).doesNotContain(resumeMarker, jobMarker);
		verify(jobStore).replaceRequestPayload(job.id(), leaseToken, upgradedJson);
	}

	private BackgroundJob job(com.fasterxml.jackson.databind.JsonNode payload) {
		Instant now = Instant.now();
		return new BackgroundJob(
			UUID.randomUUID(), UUID.randomUUID(), JobType.ANALYSIS, "resume", null,
			JobStatus.PROCESSING, JobStage.QUEUED, payload, null, "fingerprint", 1, 3,
			null, null, null, now, now, now, now, now, null, UUID.randomUUID(), now.plusSeconds(300)
		);
	}
}
