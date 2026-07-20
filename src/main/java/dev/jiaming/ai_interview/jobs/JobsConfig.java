package dev.jiaming.ai_interview.jobs;

import java.net.URI;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;

@Configuration
@EnableScheduling
public class JobsConfig {

	@Bean
	SqsClient sqsClient(JobProperties properties) {
		var builder = SqsClient.builder().region(Region.of(properties.region()));
		if (blank(properties.accessKey()) || blank(properties.secretKey())) {
			builder.credentialsProvider(DefaultCredentialsProvider.create());
		}
		else {
			builder.credentialsProvider(StaticCredentialsProvider.create(
				AwsBasicCredentials.create(properties.accessKey(), properties.secretKey())
			));
		}
		if (!blank(properties.endpoint())) {
			builder.endpointOverride(URI.create(properties.endpoint()));
		}
		return builder.build();
	}

	private boolean blank(String value) {
		return value == null || value.isBlank();
	}
}
