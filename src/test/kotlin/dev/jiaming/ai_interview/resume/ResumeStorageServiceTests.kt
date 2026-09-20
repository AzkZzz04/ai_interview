package dev.jiaming.ai_interview.resume

import dev.jiaming.ai_interview.storage.ObjectStorageService
import dev.jiaming.ai_interview.storage.StoredObject
import java.nio.charset.StandardCharsets
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito
import org.springframework.beans.factory.ObjectProvider

class ResumeStorageServiceTests {
	@Test
	@Suppress("UNCHECKED_CAST")
	fun storesNewResumeWithPendingTagAndMarksItReadyAfterExtraction() {
		val objectStorage = Mockito.mock(ObjectStorageService::class.java)
		val provider = Mockito.mock(ObjectProvider::class.java) as ObjectProvider<ObjectStorageService>
		Mockito.`when`(provider.ifAvailable).thenReturn(objectStorage)
		Mockito.`when`(
			objectStorage.put(
				any(), any(), eq("text/plain"),
				eq(mapOf("original-filename" to "resume.txt")),
				eq(mapOf("processing-status" to "pending"))
			)
		).thenAnswer { invocation ->
			StoredObject("bucket", invocation.getArgument<String>(0), 6)
		}
		val service = ResumeStorageService(provider)
		val bytes = "resume".toByteArray(StandardCharsets.UTF_8)
		val content = ResumeFileContent("resume.txt", "text/plain", bytes.size.toLong(), bytes, "text/plain", "txt")

		val key = service.store(content)
		service.markReady(key)

		assertThat(key).startsWith("resumes/").endsWith("/resume.txt")
		Mockito.verify(objectStorage).tag(key, mapOf("processing-status" to "ready"))
	}
}
