package dev.jiaming.ai_interview.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import dev.jiaming.ai_interview.storage.S3ObjectStorageService;
import dev.jiaming.ai_interview.storage.StorageProperties;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectTaggingRequest;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

@Testcontainers(disabledWithoutDocker = true)
class QueueAndStorageLocalStackIntegrationTests {

	@Container
	private static final LocalStackContainer LOCALSTACK = new LocalStackContainer(
		DockerImageName.parse("localstack/localstack:3")
	).withServices(LocalStackContainer.Service.SQS, LocalStackContainer.Service.S3);

	private SqsClient sqsClient;

	private JobQueueService queueService;

	private String dlqUrl;

	@BeforeEach
	void setUpQueues() {
		sqsClient = SqsClient.builder()
			.endpointOverride(LOCALSTACK.getEndpointOverride(LocalStackContainer.Service.SQS))
			.region(Region.of(LOCALSTACK.getRegion()))
			.credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(
				LOCALSTACK.getAccessKey(), LOCALSTACK.getSecretKey()
			)))
			.build();
		String suffix = UUID.randomUUID().toString();
		String dlqName = "jobs-dlq-" + suffix;
		dlqUrl = sqsClient.createQueue(CreateQueueRequest.builder().queueName(dlqName).build()).queueUrl();
		String dlqArn = sqsClient.getQueueAttributes(GetQueueAttributesRequest.builder()
			.queueUrl(dlqUrl)
			.attributeNames(QueueAttributeName.QUEUE_ARN)
			.build()).attributes().get(QueueAttributeName.QUEUE_ARN);
		String queueName = "jobs-" + suffix;
		sqsClient.createQueue(CreateQueueRequest.builder()
			.queueName(queueName)
			.attributes(Map.of(
				QueueAttributeName.VISIBILITY_TIMEOUT, "30",
				QueueAttributeName.RECEIVE_MESSAGE_WAIT_TIME_SECONDS, "1",
				QueueAttributeName.REDRIVE_POLICY,
				"{\"deadLetterTargetArn\":\"" + dlqArn + "\",\"maxReceiveCount\":\"2\"}"
			))
			.build());
		JobProperties properties = new JobProperties(
			true, "all", LOCALSTACK.getEndpointOverride(LocalStackContainer.Service.SQS).toString(),
			LOCALSTACK.getRegion(), LOCALSTACK.getAccessKey(), LOCALSTACK.getSecretKey(),
			queueName, dlqName, 2, 1, 1, 30, 10, 5,
			1, 300, 5_000, 30_000, 3_600_000, 120, 7
		);
		queueService = new JobQueueService(
			sqsClient, properties, JsonMapper.builder().findAndAddModules().build()
		);
		queueService.validateConfiguration();
	}

	@Test
	void queueUsesDynamicRedriveConfigurationAndConsumesItsDlq() {
		UUID jobId = UUID.randomUUID();
		queueService.send(jobId);
		for (int delivery = 0; delivery < 2; delivery++) {
			Message received = queueService.receive(1).getFirst();
			assertThat(queueService.parse(received).jobId()).isEqualTo(jobId);
			queueService.changeVisibility(received, 0);
		}

		Message deadLetter = awaitDeadLetter(Duration.ofSeconds(10));

		assertThat(deadLetter).isNotNull();
		assertThat(queueService.parse(deadLetter).jobId()).isEqualTo(jobId);
		queueService.deleteDeadLetter(deadLetter);
	}

	@Test
	void applicationCanDeadLetterAnExhaustedFreshRetryMessage() {
		UUID jobId = UUID.randomUUID();

		queueService.sendDeadLetter(jobId);
		Message deadLetter = awaitDeadLetter(Duration.ofSeconds(10));

		assertThat(deadLetter).isNotNull();
		assertThat(queueService.parse(deadLetter).jobId()).isEqualTo(jobId);
		queueService.deleteDeadLetter(deadLetter);
	}

	@Test
	void storagePersistsPendingTagThenMarksObjectReady() {
		S3Client s3Client = S3Client.builder()
			.endpointOverride(LOCALSTACK.getEndpointOverride(LocalStackContainer.Service.S3))
			.region(Region.of(LOCALSTACK.getRegion()))
			.credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(
				LOCALSTACK.getAccessKey(), LOCALSTACK.getSecretKey()
			)))
			.forcePathStyle(true)
			.build();
		String bucket = "resume-" + UUID.randomUUID();
		S3ObjectStorageService storage = new S3ObjectStorageService(
			s3Client,
			new StorageProperties(
				LOCALSTACK.getEndpointOverride(LocalStackContainer.Service.S3).toString(),
				LOCALSTACK.getRegion(), bucket, LOCALSTACK.getAccessKey(), LOCALSTACK.getSecretKey(), 24
			)
		);
		String key = "resumes/test/resume.txt";

		storage.put(
			key, "resume".getBytes(java.nio.charset.StandardCharsets.UTF_8), "text/plain",
			Map.of("original-filename", "resume.txt"), Map.of("processing-status", "pending")
		);
		assertThat(tags(s3Client, bucket, key)).containsEntry("processing-status", "pending");

		storage.tag(key, Map.of("processing-status", "ready"));
		assertThat(tags(s3Client, bucket, key)).containsEntry("processing-status", "ready");
	}

	private Message awaitDeadLetter(Duration timeout) {
		Instant deadline = Instant.now().plus(timeout);
		while (Instant.now().isBefore(deadline)) {
			var messages = queueService.receiveDeadLetters(1);
			if (!messages.isEmpty()) {
				return messages.getFirst();
			}
			queueService.receive(1);
		}
		return null;
	}

	private Map<String, String> tags(S3Client s3Client, String bucket, String key) {
		return s3Client.getObjectTagging(GetObjectTaggingRequest.builder()
			.bucket(bucket)
			.key(key)
			.build()).tagSet().stream()
			.collect(java.util.stream.Collectors.toMap(tag -> tag.key(), tag -> tag.value()));
	}
}
