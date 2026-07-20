package dev.jiaming.ai_interview.jobs;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.ChangeMessageVisibilityRequest;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

@Service
public class JobQueueService {

	private final SqsClient sqsClient;

	private final JobProperties properties;

	private final ObjectMapper objectMapper;

	private final AtomicReference<String> queueUrl = new AtomicReference<>();

	private final AtomicReference<String> dlqUrl = new AtomicReference<>();

	public JobQueueService(SqsClient sqsClient, JobProperties properties, ObjectMapper objectMapper) {
		this.sqsClient = sqsClient;
		this.properties = properties;
		this.objectMapper = objectMapper;
	}

	public void send(UUID jobId) {
		sqsClient.sendMessage(SendMessageRequest.builder()
			.queueUrl(mainQueueUrl())
			.messageBody(messageBody(jobId))
			.build());
	}

	public void sendDeadLetter(UUID jobId) {
		sqsClient.sendMessage(SendMessageRequest.builder()
			.queueUrl(deadLetterQueueUrl())
			.messageBody(messageBody(jobId))
			.build());
	}

	public List<Message> receive(int maximumMessages) {
		return receiveFrom(mainQueueUrl(), maximumMessages);
	}

	public List<Message> receiveDeadLetters(int maximumMessages) {
		return receiveFrom(deadLetterQueueUrl(), maximumMessages);
	}

	public JobMessage parse(Message message) {
		try {
			return objectMapper.readValue(message.body(), JobMessage.class);
		}
		catch (JsonProcessingException exception) {
			throw new IllegalArgumentException("Invalid background job queue message", exception);
		}
	}

	public int receiveCount(Message message) {
		String value = message.attributes().get(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT);
		if (value == null) {
			return 1;
		}
		try {
			return Integer.parseInt(value);
		}
		catch (NumberFormatException exception) {
			return 1;
		}
	}

	public void delete(Message message) {
		deleteFrom(mainQueueUrl(), message);
	}

	public void deleteDeadLetter(Message message) {
		deleteFrom(deadLetterQueueUrl(), message);
	}

	public void changeVisibility(Message message, int seconds) {
		changeVisibility(mainQueueUrl(), message, seconds);
	}

	public void changeDeadLetterVisibility(Message message, int seconds) {
		changeVisibility(deadLetterQueueUrl(), message, seconds);
	}

	public void validateConfiguration() {
		String mainUrl = mainQueueUrl();
		String deadLetterUrl = deadLetterQueueUrl();
		Map<QueueAttributeName, String> mainAttributes = attributes(
			mainUrl,
			QueueAttributeName.REDRIVE_POLICY,
			QueueAttributeName.VISIBILITY_TIMEOUT
		);
		Map<QueueAttributeName, String> deadLetterAttributes = attributes(
			deadLetterUrl,
			QueueAttributeName.QUEUE_ARN
		);

		String redrivePolicy = mainAttributes.get(QueueAttributeName.REDRIVE_POLICY);
		String deadLetterArn = deadLetterAttributes.get(QueueAttributeName.QUEUE_ARN);
		if (redrivePolicy == null || deadLetterArn == null) {
			throw new IllegalStateException("SQS queue redrive policy is not configured");
		}
		try {
			JsonNode policy = objectMapper.readTree(redrivePolicy);
			if (!deadLetterArn.equals(policy.path("deadLetterTargetArn").asText())) {
				throw new IllegalStateException("SQS queue redrive policy targets the wrong DLQ");
			}
			if (policy.path("maxReceiveCount").asInt(-1) != properties.maxReceiveCount()) {
				throw new IllegalStateException(
					"SQS maxReceiveCount does not match SQS_MAX_RECEIVE_COUNT=" + properties.maxReceiveCount()
				);
			}
		}
		catch (JsonProcessingException exception) {
			throw new IllegalStateException("SQS queue has an invalid redrive policy", exception);
		}

		int visibility = integerAttribute(mainAttributes, QueueAttributeName.VISIBILITY_TIMEOUT);
		if (visibility != properties.visibilityTimeoutSeconds()) {
			throw new IllegalStateException(
				"SQS visibility timeout does not match JOB_VISIBILITY_TIMEOUT_SECONDS="
					+ properties.visibilityTimeoutSeconds()
			);
		}
	}

	String mainQueueUrl() {
		return resolveQueueUrl(queueUrl, properties.queueName());
	}

	String deadLetterQueueUrl() {
		return resolveQueueUrl(dlqUrl, properties.dlqName());
	}

	private List<Message> receiveFrom(String url, int maximumMessages) {
		if (maximumMessages <= 0) {
			return List.of();
		}
		return sqsClient.receiveMessage(ReceiveMessageRequest.builder()
			.queueUrl(url)
			.maxNumberOfMessages(Math.min(10, maximumMessages))
			.waitTimeSeconds(properties.longPollSeconds())
			.visibilityTimeout(properties.visibilityTimeoutSeconds())
			.messageSystemAttributeNames(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT)
			.build())
			.messages();
	}

	private void deleteFrom(String url, Message message) {
		sqsClient.deleteMessage(DeleteMessageRequest.builder()
			.queueUrl(url)
			.receiptHandle(message.receiptHandle())
			.build());
	}

	private void changeVisibility(String url, Message message, int seconds) {
		sqsClient.changeMessageVisibility(ChangeMessageVisibilityRequest.builder()
			.queueUrl(url)
			.receiptHandle(message.receiptHandle())
			.visibilityTimeout(Math.max(0, seconds))
			.build());
	}

	private String resolveQueueUrl(AtomicReference<String> reference, String name) {
		String existing = reference.get();
		if (existing != null) {
			return existing;
		}
		String resolved = sqsClient.getQueueUrl(GetQueueUrlRequest.builder()
			.queueName(name)
			.build())
			.queueUrl();
		reference.compareAndSet(null, resolved);
		return reference.get();
	}

	private Map<QueueAttributeName, String> attributes(String url, QueueAttributeName... names) {
		return sqsClient.getQueueAttributes(GetQueueAttributesRequest.builder()
			.queueUrl(url)
			.attributeNames(names)
			.build())
			.attributes();
	}

	private int integerAttribute(Map<QueueAttributeName, String> attributes, QueueAttributeName name) {
		String value = attributes.get(name);
		try {
			return Integer.parseInt(value);
		}
		catch (NumberFormatException exception) {
			throw new IllegalStateException("SQS queue attribute " + name + " is invalid: " + value, exception);
		}
	}

	private String messageBody(UUID jobId) {
		try {
			return objectMapper.writeValueAsString(new JobMessage(jobId));
		}
		catch (JsonProcessingException exception) {
			throw new IllegalStateException("Could not serialize background job message", exception);
		}
	}
}
