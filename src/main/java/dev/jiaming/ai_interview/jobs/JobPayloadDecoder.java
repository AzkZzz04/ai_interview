package dev.jiaming.ai_interview.jobs;

import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import dev.jiaming.ai_interview.coach.AiAnalysisRequest;
import dev.jiaming.ai_interview.coach.AnswerFeedbackRequest;
import dev.jiaming.ai_interview.document.DocumentReferenceResolver;
import dev.jiaming.ai_interview.document.ResolvedJobInputs;
import dev.jiaming.ai_interview.resume.ResumeExtractionJobPayload;

@Component
public class JobPayloadDecoder {

	private final ObjectMapper objectMapper;

	private final BackgroundJobStore jobStore;

	private final DocumentReferenceResolver documentResolver;

	public JobPayloadDecoder(
		ObjectMapper objectMapper,
		BackgroundJobStore jobStore,
		DocumentReferenceResolver documentResolver
	) {
		this.objectMapper = objectMapper;
		this.jobStore = jobStore;
		this.documentResolver = documentResolver;
	}

	public AnalysisJobPayload analysis(BackgroundJob job, UUID leaseToken) {
		if (isCurrent(job.requestPayload())) {
			return convert(job.requestPayload(), AnalysisJobPayload.class);
		}
		AiAnalysisRequest legacy = convert(job.requestPayload(), AiAnalysisRequest.class);
		ResolvedJobInputs inputs = documentResolver.resolveLegacy(
			requireUser(job),
			legacy.resumeId(),
			legacy.resumeText(),
			legacy.jobDescriptionId(),
			legacy.jobDescription()
		);
		AnalysisJobPayload upgraded = new AnalysisJobPayload(
			inputs.resume().resourceId(),
			inputs.jobDescription().map(document -> document.resourceId()).orElse(null),
			legacy.targetRole(),
			legacy.seniority()
		);
		jobStore.replaceRequestPayload(job.id(), leaseToken, objectMapper.valueToTree(upgraded));
		return upgraded;
	}

	public FeedbackJobPayload feedback(BackgroundJob job, UUID leaseToken) {
		if (isCurrent(job.requestPayload())) {
			return convert(job.requestPayload(), FeedbackJobPayload.class);
		}
		AnswerFeedbackRequest legacy = convert(job.requestPayload(), AnswerFeedbackRequest.class);
		ResolvedJobInputs inputs = documentResolver.resolveLegacy(
			requireUser(job),
			legacy.resumeId(),
			legacy.resumeText(),
			legacy.jobDescriptionId(),
			legacy.jobDescription()
		);
		FeedbackJobPayload upgraded = new FeedbackJobPayload(
			inputs.resume().resourceId(),
			inputs.jobDescription().map(document -> document.resourceId()).orElse(null),
			legacy.targetRole(),
			legacy.seniority(),
			legacy.questionText(),
			legacy.category(),
			legacy.expectedSignals(),
			legacy.answerText()
		);
		jobStore.replaceRequestPayload(job.id(), leaseToken, objectMapper.valueToTree(upgraded));
		return upgraded;
	}

	public Object decode(BackgroundJob job, UUID leaseToken, Class<?> payloadType) {
		Object payload = switch (job.jobType()) {
			case RESUME_EXTRACTION -> convert(job.requestPayload(), ResumeExtractionJobPayload.class);
			case ANALYSIS -> analysis(job, leaseToken);
			case ANSWER_FEEDBACK -> feedback(job, leaseToken);
		};
		if (!payloadType.isInstance(payload)) {
			throw new IllegalArgumentException(
				"Decoded payload for " + job.jobType() + " is not " + payloadType.getSimpleName()
			);
		}
		return payload;
	}

	private boolean isCurrent(JsonNode payload) {
		return payload != null
			&& payload.path("payloadVersion").asInt(0) == AnalysisJobPayload.CURRENT_VERSION;
	}

	private UUID requireUser(BackgroundJob job) {
		if (job.userId() == null) {
			throw new IllegalArgumentException("Background job has no user: " + job.id());
		}
		return job.userId();
	}

	private <T> T convert(JsonNode node, Class<T> type) {
		try {
			return objectMapper.treeToValue(node, type);
		}
		catch (JsonProcessingException exception) {
			throw new IllegalArgumentException("Invalid " + type.getSimpleName() + " job payload", exception);
		}
	}
}
