package dev.jiaming.ai_interview.jobs;

import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

@Service
public class JobProcessor {

	private final JobPayloadDecoder payloadDecoder;

	private final JobHandlerRegistry handlerRegistry;

	private final BackgroundJobStore jobStore;

	private final JobEffectMaterializationService materializationService;

	private final JobMetrics metrics;

	private final ObjectMapper objectMapper;

	public JobProcessor(
		JobPayloadDecoder payloadDecoder,
		JobHandlerRegistry handlerRegistry,
		BackgroundJobStore jobStore,
		JobEffectMaterializationService materializationService,
		JobMetrics metrics,
		ObjectMapper objectMapper
	) {
		this.payloadDecoder = payloadDecoder;
		this.handlerRegistry = handlerRegistry;
		this.jobStore = jobStore;
		this.materializationService = materializationService;
		this.metrics = metrics;
		this.objectMapper = objectMapper;
	}

	public JsonNode process(BackgroundJob job, UUID leaseToken) {
		JobHandler<?> handler = handlerRegistry.require(job.jobType());
		Object payload = payloadDecoder.decode(job, leaseToken, handler.payloadType());
		JobExecutionContext context = new JobExecutionContext(
			job,
			leaseToken,
			jobStore,
			materializationService,
			metrics,
			objectMapper
		);
		try {
			return invoke(handler, payload, context);
		}
		finally {
			context.finish();
		}
	}

	private <P> JsonNode invoke(JobHandler<P> handler, Object payload, JobExecutionContext context) {
		return handler.handle(handler.payloadType().cast(payload), context);
	}
}
