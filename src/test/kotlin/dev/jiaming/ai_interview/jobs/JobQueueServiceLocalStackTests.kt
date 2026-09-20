package dev.jiaming.ai_interview.jobs

import com.fasterxml.jackson.databind.json.JsonMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.sqs.SqsClient
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest
import software.amazon.awssdk.services.sqs.model.DeleteQueueRequest
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest
import software.amazon.awssdk.services.sqs.model.Message
import software.amazon.awssdk.services.sqs.model.QueueAttributeName
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest
import java.net.URI
import java.time.Duration
import java.time.Instant
import java.util.UUID

@EnabledIfEnvironmentVariable(named = "RUN_LOCALSTACK_TESTS", matches = "true")
class JobQueueServiceLocalStackTests {

    @Test
    fun publishesRedeliversAndMovesPoisonMessageToDlq() {
        val normalJobId = UUID.randomUUID()
        queueService.send(normalJobId)
        val firstDelivery = receiveMain()

        assertThat(queueService.parse(firstDelivery).jobId()).isEqualTo(normalJobId)
        assertThat(queueService.receiveCount(firstDelivery)).isEqualTo(1)

        queueService.changeVisibility(firstDelivery, 0)
        val secondDelivery = receiveMain()
        assertThat(queueService.parse(secondDelivery).jobId()).isEqualTo(normalJobId)
        assertThat(queueService.receiveCount(secondDelivery)).isGreaterThanOrEqualTo(2)
        queueService.delete(secondDelivery)

        val poisonJobId = UUID.randomUUID()
        queueService.send(poisonJobId)
        repeat(2) {
            val poison = receiveMain()
            assertThat(queueService.parse(poison).jobId()).isEqualTo(poisonJobId)
            queueService.changeVisibility(poison, 0)
        }

        val deadLetter = awaitDlqMessage(Duration.ofSeconds(10))
        assertThat(deadLetter).isNotNull()
        assertThat(JsonMapper.builder().build().readTree(deadLetter!!.body()).get("jobId").asText())
            .isEqualTo(poisonJobId.toString())
        queueService.deleteDeadLetter(deadLetter)
    }

    companion object {
        private lateinit var sqsClient: SqsClient
        private lateinit var queueService: JobQueueService
        private lateinit var queueUrl: String
        private lateinit var dlqUrl: String
        private lateinit var dlqName: String

        @BeforeAll
        @JvmStatic
        fun setUpQueues() {
            val endpoint = System.getenv().getOrDefault("LOCALSTACK_ENDPOINT", "http://127.0.0.1:4566")
            sqsClient = SqsClient.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .build()

            val suffix = UUID.randomUUID().toString()
            dlqName = "ai-interview-jobs-test-dlq-$suffix"
            dlqUrl = sqsClient.createQueue(CreateQueueRequest.builder().queueName(dlqName).build()).queueUrl()
            val dlqArn = sqsClient.getQueueAttributes(
                GetQueueAttributesRequest.builder()
                    .queueUrl(dlqUrl)
                    .attributeNames(QueueAttributeName.QUEUE_ARN)
                    .build()
            ).attributes()[QueueAttributeName.QUEUE_ARN]

            val queueName = "ai-interview-jobs-test-$suffix"
            queueUrl = sqsClient.createQueue(
                CreateQueueRequest.builder()
                    .queueName(queueName)
                    .attributes(
                        mapOf(
                            QueueAttributeName.VISIBILITY_TIMEOUT to "30",
                            QueueAttributeName.RECEIVE_MESSAGE_WAIT_TIME_SECONDS to "1",
                            QueueAttributeName.REDRIVE_POLICY to
                                "{\"deadLetterTargetArn\":\"$dlqArn\",\"maxReceiveCount\":\"2\"}"
                        )
                    )
                    .build()
            ).queueUrl()

            val properties = JobProperties(
                true, endpoint, "us-east-1", "test", "test", queueName, dlqName, 2,
                1, 1, 30, 10, 3, 1, 300, 5_000, 30_000, 3_600_000, 120, 7
            )
            queueService = JobQueueService(
                sqsClient,
                properties,
                JsonMapper.builder().findAndAddModules().build()
            )
            queueService.validateConfiguration()
        }

        @AfterAll
        @JvmStatic
        fun tearDownQueues() {
            if (!::sqsClient.isInitialized) {
                return
            }
            deleteQueue(if (::queueUrl.isInitialized) queueUrl else null)
            deleteQueue(if (::dlqUrl.isInitialized) dlqUrl else null)
            sqsClient.close()
        }

        private fun receiveMain(): Message {
            val messages = queueService.receive(1)
            assertThat(messages).hasSize(1)
            return messages[0]
        }

        private fun awaitDlqMessage(timeout: Duration): Message? {
            val deadline = Instant.now().plus(timeout)
            while (Instant.now().isBefore(deadline)) {
                val messages = queueService.receiveDeadLetters(1)
                if (messages.isNotEmpty()) {
                    return messages[0]
                }

                sqsClient.receiveMessage(
                    ReceiveMessageRequest.builder()
                        .queueUrl(queueUrl)
                        .maxNumberOfMessages(1)
                        .waitTimeSeconds(0)
                        .build()
                )
            }
            return null
        }

        private fun deleteQueue(url: String?) {
            if (url != null) {
                sqsClient.deleteQueue(DeleteQueueRequest.builder().queueUrl(url).build())
            }
        }
    }
}
