package dev.jiaming.ai_interview.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class JobProcessorTests {

	private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

	@Test
	void decodesPayloadAndDispatchesToRegisteredHandler() {
		BackgroundJobStore store = mock(BackgroundJobStore.class);
		JobEffectMaterializationService materialization = mock(JobEffectMaterializationService.class);
		JobMetrics metrics = mock(JobMetrics.class);
		JobPayloadDecoder decoder = mock(JobPayloadDecoder.class);
		AnalysisJobPayload payload = new AnalysisJobPayload(UUID.randomUUID(), null, "Backend", "Mid-level");
		JsonNode expected = objectMapper.createObjectNode().put("ok", true);
		TestHandler handler = new TestHandler(expected);
		JobHandlerRegistry registry = new JobHandlerRegistry(List.of(
			new NoOpHandler<>(JobType.RESUME_EXTRACTION, Object.class),
			handler,
			new NoOpHandler<>(JobType.ANSWER_FEEDBACK, Object.class)
		));
		JobProcessor processor = new JobProcessor(
			decoder, registry, store, materialization, metrics, objectMapper
		);
		BackgroundJob job = job();
		UUID leaseToken = UUID.randomUUID();
		when(decoder.decode(job, leaseToken, AnalysisJobPayload.class)).thenReturn(payload);

		JsonNode result = processor.process(job, leaseToken);

		assertThat(result).isSameAs(expected);
		assertThat(handler.payload).isSameAs(payload);
		assertThat(handler.context.job()).isSameAs(job);
		verify(decoder).decode(job, leaseToken, AnalysisJobPayload.class);
	}

	private BackgroundJob job() {
		Instant now = Instant.now();
		return new BackgroundJob(
			UUID.randomUUID(), UUID.randomUUID(), JobType.ANALYSIS, "resume", UUID.randomUUID(),
			JobStatus.PROCESSING, JobStage.QUEUED, objectMapper.createObjectNode(), null, "fingerprint", 1, 3,
			null, null, null, now, now, now, now, now, null, UUID.randomUUID(), now.plusSeconds(300)
		);
	}

	private static final class TestHandler implements JobHandler<AnalysisJobPayload> {

		private final JsonNode result;

		private AnalysisJobPayload payload;

		private JobExecutionContext context;

		private TestHandler(JsonNode result) {
			this.result = result;
		}

		@Override
		public JobType type() {
			return JobType.ANALYSIS;
		}

		@Override
		public Class<AnalysisJobPayload> payloadType() {
			return AnalysisJobPayload.class;
		}

		@Override
		public JsonNode handle(AnalysisJobPayload payload, JobExecutionContext context) {
			this.payload = payload;
			this.context = context;
			return result;
		}
	}

	private record NoOpHandler<P>(JobType type, Class<P> payloadType) implements JobHandler<P> {

		@Override
		public JsonNode handle(P payload, JobExecutionContext context) {
			return null;
		}
	}
}
