package dev.jiaming.ai_interview.jobs;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest;
import software.amazon.awssdk.services.sqs.model.DeleteQueueRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "RUN_LOCALSTACK_TESTS", matches = "true")
class JobQueueServiceLocalStackTests {

	private static SqsClient sqsClient;

	private static JobQueueService queueService;

	private static String queueUrl;

	private static String dlqUrl;

	private static String dlqName;

	@BeforeAll
	static void setUpQueues() {
		String endpoint = System.getenv().getOrDefault("LOCALSTACK_ENDPOINT", "http://127.0.0.1:4566");
		sqsClient = SqsClient.builder()
			.endpointOverride(URI.create(endpoint))
			.region(Region.US_EAST_1)
			.credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
			.build();

		String suffix = UUID.randomUUID().toString();
		dlqName = "ai-interview-jobs-test-dlq-" + suffix;
		dlqUrl = sqsClient.createQueue(CreateQueueRequest.builder()
			.queueName(dlqName)
			.build()).queueUrl();
		String dlqArn = sqsClient.getQueueAttributes(GetQueueAttributesRequest.builder()
			.queueUrl(dlqUrl)
			.attributeNames(QueueAttributeName.QUEUE_ARN)
			.build()).attributes().get(QueueAttributeName.QUEUE_ARN);

		String queueName = "ai-interview-jobs-test-" + suffix;
		queueUrl = sqsClient.createQueue(CreateQueueRequest.builder()
			.queueName(queueName)
			.attributes(Map.of(
				QueueAttributeName.VISIBILITY_TIMEOUT, "30",
				QueueAttributeName.RECEIVE_MESSAGE_WAIT_TIME_SECONDS, "1",
				QueueAttributeName.REDRIVE_POLICY,
				"{\"deadLetterTargetArn\":\"" + dlqArn + "\",\"maxReceiveCount\":\"2\"}"
			))
			.build()).queueUrl();

		JobProperties properties = new JobProperties(
			true, endpoint, "us-east-1", "test", "test", queueName, dlqName, 2,
			1, 1, 30, 10, 3, 1, 300, 5_000, 30_000, 3_600_000, 120, 7
		);
		queueService = new JobQueueService(
			sqsClient,
			properties,
			JsonMapper.builder().findAndAddModules().build()
		);
		queueService.validateConfiguration();
	}

	@AfterAll
	static void tearDownQueues() {
		if (sqsClient == null) {
			return;
		}
		deleteQueue(queueUrl);
		deleteQueue(dlqUrl);
		sqsClient.close();
	}

	@Test
	void publishesRedeliversAndMovesPoisonMessageToDlq() throws Exception {
		UUID normalJobId = UUID.randomUUID();
		queueService.send(normalJobId);
		Message firstDelivery = receiveMain();

		assertThat(queueService.parse(firstDelivery).jobId()).isEqualTo(normalJobId);
		assertThat(queueService.receiveCount(firstDelivery)).isEqualTo(1);

		queueService.changeVisibility(firstDelivery, 0);
		Message secondDelivery = receiveMain();
		assertThat(queueService.parse(secondDelivery).jobId()).isEqualTo(normalJobId);
		assertThat(queueService.receiveCount(secondDelivery)).isGreaterThanOrEqualTo(2);
		queueService.delete(secondDelivery);

		UUID poisonJobId = UUID.randomUUID();
		queueService.send(poisonJobId);
		for (int delivery = 0; delivery < 2; delivery++) {
			Message poison = receiveMain();
			assertThat(queueService.parse(poison).jobId()).isEqualTo(poisonJobId);
			queueService.changeVisibility(poison, 0);
		}

		Message deadLetter = awaitDlqMessage(Duration.ofSeconds(10));
		assertThat(deadLetter).isNotNull();
		assertThat(JsonMapper.builder().build().readTree(deadLetter.body()).get("jobId").asText())
			.isEqualTo(poisonJobId.toString());
		queueService.deleteDeadLetter(deadLetter);
	}

	private static Message receiveMain() {
		var messages = queueService.receive(1);
		assertThat(messages).hasSize(1);
		return messages.getFirst();
	}

	private static Message awaitDlqMessage(Duration timeout) {
		Instant deadline = Instant.now().plus(timeout);
		while (Instant.now().isBefore(deadline)) {
			var messages = queueService.receiveDeadLetters(1);
			if (!messages.isEmpty()) {
				return messages.getFirst();
			}

			// A receive after maxReceiveCount prompts LocalStack to apply redrive immediately.
			sqsClient.receiveMessage(ReceiveMessageRequest.builder()
				.queueUrl(queueUrl)
				.maxNumberOfMessages(1)
				.waitTimeSeconds(0)
				.build());
		}
		return null;
	}

	private static void deleteQueue(String url) {
		if (url != null) {
			sqsClient.deleteQueue(DeleteQueueRequest.builder().queueUrl(url).build());
		}
	}
}
