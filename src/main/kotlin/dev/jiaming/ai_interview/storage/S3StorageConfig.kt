package dev.jiaming.ai_interview.storage

import java.net.URI
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration

@Configuration
class S3StorageConfig {
    @Bean
    fun s3Client(properties: StorageProperties): S3Client {
        val builder = S3Client.builder()
            .region(Region.of(nonBlank(properties.region, "us-east-1")))
            .credentialsProvider(
                StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(nonBlank(properties.accessKey, "test"), nonBlank(properties.secretKey, "test"))
                )
            )
            .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
        properties.endpoint?.takeIf(String::isNotBlank)?.let { builder.endpointOverride(URI.create(it)) }
        return builder.build()
    }

    private fun nonBlank(value: String?, fallback: String) = value?.takeIf(String::isNotBlank) ?: fallback
}
