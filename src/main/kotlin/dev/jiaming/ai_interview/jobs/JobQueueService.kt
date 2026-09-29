package dev.jiaming.ai_interview.jobs

import java.util.concurrent.atomic.AtomicReference
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Service
import software.amazon.awssdk.services.sqs.SqsClient
import software.amazon.awssdk.services.sqs.model.*

@Service
class JobQueueService(private val sqsClient: SqsClient, private val properties: JobProperties, private val objectMapper: ObjectMapper) {
    private val queueUrl = AtomicReference<String?>()
    private val dlqUrl = AtomicReference<String?>()
    fun send(jobId: java.util.UUID) { sqsClient.sendMessage(SendMessageRequest.builder().queueUrl(mainQueueUrl()).messageBody(messageBody(jobId)).build()) }
    fun sendDeadLetter(jobId: java.util.UUID) { sqsClient.sendMessage(SendMessageRequest.builder().queueUrl(deadLetterQueueUrl()).messageBody(messageBody(jobId)).build()) }
    fun receive(maximumMessages: Int): List<Message> = receiveFrom(mainQueueUrl(), maximumMessages)
    fun receiveDeadLetters(maximumMessages: Int): List<Message> = receiveFrom(deadLetterQueueUrl(), maximumMessages)
    fun parse(message: Message): JobMessage = try { objectMapper.readValue(message.body(), JobMessage::class.java) }
        catch (exception: JsonProcessingException) { throw IllegalArgumentException("Invalid background job queue message", exception) }
    fun receiveCount(message: Message): Int {
        val value = message.attributes()[MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT] ?: return 1
        return value.toIntOrNull() ?: 1
    }
    fun delete(message: Message) = deleteFrom(mainQueueUrl(), message)
    fun deleteDeadLetter(message: Message) = deleteFrom(deadLetterQueueUrl(), message)
    fun changeVisibility(message: Message, seconds: Int) = changeVisibility(mainQueueUrl(), message, seconds)
    fun changeDeadLetterVisibility(message: Message, seconds: Int) = changeVisibility(deadLetterQueueUrl(), message, seconds)

    fun validateConfiguration() {
        val mainUrl = mainQueueUrl()
        val deadLetterUrl = deadLetterQueueUrl()
        val mainAttributes = attributes(mainUrl, QueueAttributeName.REDRIVE_POLICY, QueueAttributeName.VISIBILITY_TIMEOUT)
        val deadLetterAttributes = attributes(deadLetterUrl, QueueAttributeName.QUEUE_ARN)
        val redrivePolicy = mainAttributes[QueueAttributeName.REDRIVE_POLICY]
        val deadLetterArn = deadLetterAttributes[QueueAttributeName.QUEUE_ARN]
        if (redrivePolicy == null || deadLetterArn == null) throw IllegalStateException("SQS queue redrive policy is not configured")
        try {
            val policy = objectMapper.readTree(redrivePolicy)
            if (deadLetterArn != policy.path("deadLetterTargetArn").asText()) throw IllegalStateException("SQS queue redrive policy targets the wrong DLQ")
            if (policy.path("maxReceiveCount").asInt(-1) != properties.maxReceiveCount) throw IllegalStateException("SQS maxReceiveCount does not match SQS_MAX_RECEIVE_COUNT=${properties.maxReceiveCount}")
        } catch (exception: JsonProcessingException) { throw IllegalStateException("SQS queue has an invalid redrive policy", exception) }
        if (integerAttribute(mainAttributes, QueueAttributeName.VISIBILITY_TIMEOUT) != properties.visibilityTimeoutSeconds)
            throw IllegalStateException("SQS visibility timeout does not match JOB_VISIBILITY_TIMEOUT_SECONDS=${properties.visibilityTimeoutSeconds}")
    }

    internal fun mainQueueUrl(): String = resolveQueueUrl(queueUrl, properties.queueName())
    internal fun deadLetterQueueUrl(): String = resolveQueueUrl(dlqUrl, properties.dlqName())
    private fun receiveFrom(url: String, maximumMessages: Int): List<Message> {
        if (maximumMessages <= 0) return emptyList()
        return sqsClient.receiveMessage(ReceiveMessageRequest.builder().queueUrl(url)
            .maxNumberOfMessages(minOf(10, maximumMessages)).waitTimeSeconds(properties.longPollSeconds)
            .visibilityTimeout(properties.visibilityTimeoutSeconds)
            .messageSystemAttributeNames(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT).build()).messages()
    }
    private fun deleteFrom(url: String, message: Message) { sqsClient.deleteMessage(DeleteMessageRequest.builder().queueUrl(url).receiptHandle(message.receiptHandle()).build()) }
    private fun changeVisibility(url: String, message: Message, seconds: Int) {
        sqsClient.changeMessageVisibility(ChangeMessageVisibilityRequest.builder().queueUrl(url).receiptHandle(message.receiptHandle()).visibilityTimeout(maxOf(0, seconds)).build())
    }
    private fun resolveQueueUrl(reference: AtomicReference<String?>, name: String): String {
        reference.get()?.let { return it }
        val resolved = sqsClient.getQueueUrl(GetQueueUrlRequest.builder().queueName(name).build()).queueUrl()
        reference.compareAndSet(null, resolved)
        return reference.get()!!
    }
    private fun attributes(url: String, vararg names: QueueAttributeName): Map<QueueAttributeName, String> =
        sqsClient.getQueueAttributes(GetQueueAttributesRequest.builder().queueUrl(url).attributeNames(*names).build()).attributes()
    private fun integerAttribute(attributes: Map<QueueAttributeName, String>, name: QueueAttributeName): Int {
        val value = attributes[name]
        return value?.toIntOrNull() ?: throw IllegalStateException("SQS queue attribute $name is invalid: $value")
    }
    private fun messageBody(jobId: java.util.UUID): String = try { objectMapper.writeValueAsString(JobMessage(jobId)) }
        catch (exception: JsonProcessingException) { throw IllegalStateException("Could not serialize background job message", exception) }
}
