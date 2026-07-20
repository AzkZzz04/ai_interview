package dev.jiaming.ai_interview.storage;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketLifecycleConfiguration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.ExpirationStatus;
import software.amazon.awssdk.services.s3.model.GetBucketLifecycleConfigurationRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.LifecycleExpiration;
import software.amazon.awssdk.services.s3.model.LifecycleRule;
import software.amazon.awssdk.services.s3.model.LifecycleRuleFilter;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.PutBucketLifecycleConfigurationRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectTaggingRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.Tag;
import software.amazon.awssdk.services.s3.model.Tagging;

@Service
public class S3ObjectStorageService implements ObjectStorageService {

	private static final Logger log = LoggerFactory.getLogger(S3ObjectStorageService.class);

	private static final String PENDING_LIFECYCLE_RULE = "ai-interview-pending-resumes";

	private final S3Client s3Client;

	private final StorageProperties properties;

	private final AtomicBoolean bucketReady = new AtomicBoolean(false);

	private final AtomicBoolean lifecycleReady = new AtomicBoolean(false);

	public S3ObjectStorageService(S3Client s3Client, StorageProperties properties) {
		this.s3Client = s3Client;
		this.properties = properties;
	}

	@Override
	public StoredObject put(
		String key,
		byte[] content,
		String contentType,
		Map<String, String> metadata,
		Map<String, String> tags
	) {
		ensureBucket();
		try {
			PutObjectRequest request = PutObjectRequest.builder()
				.bucket(bucket())
				.key(key)
				.contentType(contentType == null || contentType.isBlank() ? "application/octet-stream" : contentType)
				.metadata(metadata == null ? Map.of() : metadata)
				.tagging(encodedTags(tags))
				.build();
			s3Client.putObject(request, RequestBody.fromBytes(content));
			return new StoredObject(bucket(), key, content.length);
		}
		catch (S3Exception exception) {
			throw storageUnavailable(exception);
		}
	}

	@Override
	public StoredObjectContent get(String key) {
		ensureBucket();
		try {
			var responseBytes = s3Client.getObjectAsBytes(GetObjectRequest.builder()
				.bucket(bucket())
				.key(key)
				.build());
			GetObjectResponse response = responseBytes.response();
			return new StoredObjectContent(responseBytes.asByteArray(), response.contentType(), response.metadata());
		}
		catch (S3Exception exception) {
			throw storageUnavailable(exception);
		}
	}

	@Override
	public void delete(String key) {
		if (key == null || key.isBlank()) {
			return;
		}
		try {
			s3Client.deleteObject(DeleteObjectRequest.builder()
				.bucket(bucket())
				.key(key)
				.build());
		}
		catch (S3Exception exception) {
			throw storageUnavailable(exception);
		}
	}

	@Override
	public void tag(String key, Map<String, String> tags) {
		if (key == null || key.isBlank()) {
			return;
		}
		try {
			s3Client.putObjectTagging(PutObjectTaggingRequest.builder()
				.bucket(bucket())
				.key(key)
				.tagging(Tagging.builder().tagSet(toTags(tags)).build())
				.build());
		}
		catch (S3Exception exception) {
			throw storageUnavailable(exception);
		}
	}

	private void ensureBucket() {
		if (bucketReady.get()) {
			ensurePendingLifecycle();
			return;
		}
		try {
			s3Client.headBucket(HeadBucketRequest.builder().bucket(bucket()).build());
			bucketReady.set(true);
		}
		catch (NoSuchBucketException exception) {
			createBucket();
		}
		catch (S3Exception exception) {
			if (exception.statusCode() == 404) {
				createBucket();
			}
			else {
				throw storageUnavailable(exception);
			}
		}
		ensurePendingLifecycle();
	}

	private void createBucket() {
		try {
			s3Client.createBucket(CreateBucketRequest.builder().bucket(bucket()).build());
			bucketReady.set(true);
		}
		catch (S3Exception exception) {
			throw storageUnavailable(exception);
		}
	}

	private void ensurePendingLifecycle() {
		if (lifecycleReady.get()) {
			return;
		}
		try {
			List<LifecycleRule> rules = new ArrayList<>();
			try {
				rules.addAll(s3Client.getBucketLifecycleConfiguration(
					GetBucketLifecycleConfigurationRequest.builder().bucket(bucket()).build()
				).rules());
			}
			catch (S3Exception exception) {
				if (exception.statusCode() != 404) {
					throw exception;
				}
			}
			rules.removeIf(rule -> PENDING_LIFECYCLE_RULE.equals(rule.id()));
			rules.add(pendingLifecycleRule());
			s3Client.putBucketLifecycleConfiguration(PutBucketLifecycleConfigurationRequest.builder()
				.bucket(bucket())
				.lifecycleConfiguration(BucketLifecycleConfiguration.builder().rules(rules).build())
				.build());
			lifecycleReady.set(true);
		}
		catch (S3Exception exception) {
			if (exception.statusCode() == 400 || exception.statusCode() == 403 || exception.statusCode() == 501) {
				lifecycleReady.set(true);
				log.warn("resume_pending_lifecycle_unavailable bucket={} status={} reason={}",
					bucket(), exception.statusCode(), exception.getMessage());
				return;
			}
			throw storageUnavailable(exception);
		}
	}

	private LifecycleRule pendingLifecycleRule() {
		int days = Math.max(1, (properties.pendingRetentionHours() + 23) / 24);
		return LifecycleRule.builder()
			.id(PENDING_LIFECYCLE_RULE)
			.status(ExpirationStatus.ENABLED)
			.filter(LifecycleRuleFilter.builder()
				.tag(Tag.builder().key("processing-status").value("pending").build())
				.build())
			.expiration(LifecycleExpiration.builder().days(days).build())
			.build();
	}

	private List<Tag> toTags(Map<String, String> tags) {
		if (tags == null || tags.isEmpty()) {
			return List.of();
		}
		return tags.entrySet().stream()
			.sorted(Map.Entry.comparingByKey())
			.map(entry -> Tag.builder().key(entry.getKey()).value(entry.getValue()).build())
			.toList();
	}

	private String encodedTags(Map<String, String> tags) {
		if (tags == null || tags.isEmpty()) {
			return null;
		}
		return tags.entrySet().stream()
			.sorted(Comparator.comparing(Map.Entry::getKey))
			.map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
			.collect(java.util.stream.Collectors.joining("&"));
	}

	private String encode(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}

	private String bucket() {
		String bucket = properties.bucket();
		if (bucket == null || bucket.isBlank()) {
			throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "S3 bucket is not configured");
		}
		return bucket;
	}

	private ResponseStatusException storageUnavailable(Exception exception) {
		return new ResponseStatusException(
			HttpStatus.BAD_GATEWAY,
			"Object storage is not reachable. Start LocalStack S3 or check S3_ENDPOINT/S3 credentials.",
			exception
		);
	}
}
