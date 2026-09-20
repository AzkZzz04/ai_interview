package dev.jiaming.ai_interview.jobs

import com.fasterxml.jackson.databind.json.JsonMapper
import dev.jiaming.ai_interview.storage.S3ObjectStorageService
import dev.jiaming.ai_interview.storage.StorageProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.testcontainers.containers.localstack.LocalStackContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.GetObjectTaggingRequest
import software.amazon.awssdk.services.sqs.SqsClient
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest
import software.amazon.awssdk.services.sqs.model.Message
import software.amazon.awssdk.services.sqs.model.QueueAttributeName
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.util.UUID

@Testcontainers(disabledWithoutDocker = true)
class QueueAndStorageLocalStackIntegrationTests {
    private lateinit var sqsClient: SqsClient
    private lateinit var queueService: JobQueueService
    private lateinit var dlqUrl: String
    @BeforeEach fun setUpQueues() {
        sqsClient = SqsClient.builder().endpointOverride(LOCALSTACK.getEndpointOverride(LocalStackContainer.Service.SQS)).region(Region.of(LOCALSTACK.region)).credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(LOCALSTACK.accessKey, LOCALSTACK.secretKey))).build()
        val suffix = UUID.randomUUID().toString(); val dlqName = "jobs-dlq-$suffix"; dlqUrl = sqsClient.createQueue(CreateQueueRequest.builder().queueName(dlqName).build()).queueUrl()
        val arn = sqsClient.getQueueAttributes(GetQueueAttributesRequest.builder().queueUrl(dlqUrl).attributeNames(QueueAttributeName.QUEUE_ARN).build()).attributes()[QueueAttributeName.QUEUE_ARN]; val queueName = "jobs-$suffix"
        sqsClient.createQueue(CreateQueueRequest.builder().queueName(queueName).attributes(mapOf(QueueAttributeName.VISIBILITY_TIMEOUT to "30", QueueAttributeName.RECEIVE_MESSAGE_WAIT_TIME_SECONDS to "1", QueueAttributeName.REDRIVE_POLICY to "{\"deadLetterTargetArn\":\"$arn\",\"maxReceiveCount\":\"2\"}")).build())
        val properties = JobProperties(true, LOCALSTACK.getEndpointOverride(LocalStackContainer.Service.SQS).toString(), LOCALSTACK.region, LOCALSTACK.accessKey, LOCALSTACK.secretKey, queueName, dlqName, 2, 1, 1, 30, 10, 5, 1, 300, 5_000, 30_000, 3_600_000, 120, 7)
        queueService = JobQueueService(sqsClient, properties, JsonMapper.builder().findAndAddModules().build()); queueService.validateConfiguration()
    }
    @Test fun queueUsesDynamicRedriveConfigurationAndConsumesItsDlq() { val id = UUID.randomUUID(); queueService.send(id); repeat(2) { val received = queueService.receive(1)[0]; assertThat(queueService.parse(received).jobId()).isEqualTo(id); queueService.changeVisibility(received, 0) }; val deadLetter = awaitDeadLetter(Duration.ofSeconds(10)); assertThat(deadLetter).isNotNull(); assertThat(queueService.parse(deadLetter!!).jobId()).isEqualTo(id); queueService.deleteDeadLetter(deadLetter) }
    @Test fun applicationCanDeadLetterAnExhaustedFreshRetryMessage() { val id = UUID.randomUUID(); queueService.sendDeadLetter(id); val deadLetter = awaitDeadLetter(Duration.ofSeconds(10)); assertThat(deadLetter).isNotNull(); assertThat(queueService.parse(deadLetter!!).jobId()).isEqualTo(id); queueService.deleteDeadLetter(deadLetter) }
    @Test fun storagePersistsPendingTagThenMarksObjectReady() { val client = S3Client.builder().endpointOverride(LOCALSTACK.getEndpointOverride(LocalStackContainer.Service.S3)).region(Region.of(LOCALSTACK.region)).credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(LOCALSTACK.accessKey, LOCALSTACK.secretKey))).forcePathStyle(true).build(); val bucket = "resume-${UUID.randomUUID()}"; val storage = S3ObjectStorageService(client, StorageProperties(LOCALSTACK.getEndpointOverride(LocalStackContainer.Service.S3).toString(), LOCALSTACK.region, bucket, LOCALSTACK.accessKey, LOCALSTACK.secretKey, 24)); val key = "resumes/test/resume.txt"; storage.put(key, "resume".toByteArray(StandardCharsets.UTF_8), "text/plain", mapOf("original-filename" to "resume.txt"), mapOf("processing-status" to "pending")); assertThat(tags(client, bucket, key)).containsEntry("processing-status", "pending"); storage.tag(key, mapOf("processing-status" to "ready")); assertThat(tags(client, bucket, key)).containsEntry("processing-status", "ready") }
    private fun awaitDeadLetter(timeout: Duration): Message? { val deadline = Instant.now().plus(timeout); while (Instant.now().isBefore(deadline)) { val messages = queueService.receiveDeadLetters(1); if (messages.isNotEmpty()) return messages[0]; queueService.receive(1) }; return null }
    private fun tags(client: S3Client, bucket: String, key: String) = client.getObjectTagging(GetObjectTaggingRequest.builder().bucket(bucket).key(key).build()).tagSet().associate { it.key() to it.value() }
    companion object { @Container @JvmField val LOCALSTACK = LocalStackContainer(DockerImageName.parse("localstack/localstack:3")).withServices(LocalStackContainer.Service.SQS, LocalStackContainer.Service.S3) }
}
