package dev.jiaming.ai_interview.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class JobExecutionContextTests {

	@Test
	void laterCheckpointRetainsEarlierAssessmentEnvelope() {
		ObjectMapper objectMapper = new ObjectMapper();
		BackgroundJobStore store = mock(BackgroundJobStore.class);
		UUID leaseToken = UUID.randomUUID();
		BackgroundJob job = job(objectMapper, null);
		JobExecutionContext context = new JobExecutionContext(
			job,
			leaseToken,
			store,
			mock(JobEffectMaterializationService.class),
			mock(JobMetrics.class),
			objectMapper
		);

		context.saveCheckpoint("assessment", objectMapper.createObjectNode().put("overallScore", 80));
		context.saveCheckpoint("questions", objectMapper.createArrayNode().add("question"));

		ArgumentCaptor<JsonNode> checkpoints = ArgumentCaptor.forClass(JsonNode.class);
		verify(store, org.mockito.Mockito.times(2)).checkpointResult(
			org.mockito.ArgumentMatchers.eq(job.id()),
			org.mockito.ArgumentMatchers.eq(leaseToken),
			checkpoints.capture()
		);
		JsonNode latest = checkpoints.getAllValues().getLast();
		assertThat(latest.hasNonNull("assessment")).isTrue();
		assertThat(latest.hasNonNull("questions")).isTrue();
	}

	@Test
	void stageChangesUpdateLeaseOwnedRowAndRecordPreviousStageDuration() {
		ObjectMapper objectMapper = new ObjectMapper();
		BackgroundJobStore store = mock(BackgroundJobStore.class);
		JobMetrics metrics = mock(JobMetrics.class);
		UUID leaseToken = UUID.randomUUID();
		BackgroundJob job = job(objectMapper, objectMapper.createObjectNode());
		JobExecutionContext context = new JobExecutionContext(
			job,
			leaseToken,
			store,
			mock(JobEffectMaterializationService.class),
			metrics,
			objectMapper
		);

		context.stage(JobStage.ASSESSING_RESUME);
		context.stage(JobStage.GENERATING_QUESTIONS);
		context.finish();

		verify(store).updateStage(job.id(), leaseToken, JobStage.ASSESSING_RESUME);
		verify(store).updateStage(job.id(), leaseToken, JobStage.GENERATING_QUESTIONS);
		verify(metrics).stageDuration(
			org.mockito.ArgumentMatchers.eq(JobType.ANALYSIS),
			org.mockito.ArgumentMatchers.eq(JobStage.ASSESSING_RESUME),
			any()
		);
		verify(metrics).stageDuration(
			org.mockito.ArgumentMatchers.eq(JobType.ANALYSIS),
			org.mockito.ArgumentMatchers.eq(JobStage.GENERATING_QUESTIONS),
			any()
		);
	}

	private BackgroundJob job(ObjectMapper objectMapper, JsonNode result) {
		Instant now = Instant.now();
		return new BackgroundJob(
			UUID.randomUUID(), UUID.randomUUID(), JobType.ANALYSIS, "resume", UUID.randomUUID(),
			JobStatus.PROCESSING, JobStage.QUEUED, objectMapper.createObjectNode(), result, "fingerprint", 1, 3,
			null, null, null, now, now, now, now, now, null, UUID.randomUUID(), now.plusSeconds(300)
		);
	}
}
