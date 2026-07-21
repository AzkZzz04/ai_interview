package dev.jiaming.ai_interview.jobs;

import com.fasterxml.jackson.databind.JsonNode;

public interface JobHandler<P> {

	JobType type();

	Class<P> payloadType();

	JsonNode handle(P payload, JobExecutionContext context);
}
