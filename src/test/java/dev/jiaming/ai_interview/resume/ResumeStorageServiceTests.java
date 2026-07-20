package dev.jiaming.ai_interview.resume;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import dev.jiaming.ai_interview.storage.ObjectStorageService;
import dev.jiaming.ai_interview.storage.StoredObject;

class ResumeStorageServiceTests {

	@Test
	void storesNewResumeWithPendingTagAndMarksItReadyAfterExtraction() {
		ObjectStorageService objectStorage = mock(ObjectStorageService.class);
		@SuppressWarnings("unchecked")
		ObjectProvider<ObjectStorageService> provider = mock(ObjectProvider.class);
		when(provider.getIfAvailable()).thenReturn(objectStorage);
		when(objectStorage.put(
			any(), any(), eq("text/plain"),
			eq(Map.of("original-filename", "resume.txt")),
			eq(Map.of("processing-status", "pending"))
		)).thenAnswer(invocation -> {
			String key = invocation.getArgument(0);
			return new StoredObject("bucket", key, 6);
		});
		ResumeStorageService service = new ResumeStorageService(provider);
		byte[] bytes = "resume".getBytes(StandardCharsets.UTF_8);
		ResumeFileContent content = new ResumeFileContent(
			"resume.txt", "text/plain", bytes.length, bytes, "text/plain", "txt"
		);

		String key = service.store(content);
		service.markReady(key);

		assertThat(key).startsWith("resumes/").endsWith("/resume.txt");
		verify(objectStorage).tag(key, Map.of("processing-status", "ready"));
	}
}
