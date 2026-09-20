package dev.jiaming.ai_interview.resume

import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test

class ResumeTextExtractorTests {
	@Test
	fun rejectsImmediatelyWhenAllWorkersAndQueueSlotsAreOccupiedThenRecovers() {
		val parserStarted = CountDownLatch(2)
		val releaseParser = CountDownLatch(1)
		val extractor = ResumeTextExtractor(ResumeExtractionProperties(2, 5, 250_000, 50, 20)) {
			parserStarted.countDown()
			try {
				releaseParser.await()
				"Java Spring Boot"
			} catch (exception: InterruptedException) {
				Thread.currentThread().interrupt()
				throw ResumeExtractionException("interrupted", exception)
			}
		}
		val callers = Executors.newFixedThreadPool(4)
		val pending = mutableListOf<java.util.concurrent.Future<String>>()
		try {
			repeat(4) { pending += callers.submit<String> { extractor.extract(textFile()) } }
			assertThat(parserStarted.await(1, TimeUnit.SECONDS)).isTrue()
			await().atMost(Duration.ofSeconds(1)).until { extractor.queuedExtractions() == 2 }
			val started = System.nanoTime()
			assertThatThrownBy { extractor.extract(textFile()) }.isInstanceOf(ResumeParserBusyException::class.java).hasMessageContaining("capacity")
			assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(500))
			releaseParser.countDown()
			pending.forEach { assertThat(it.get(2, TimeUnit.SECONDS)).isEqualTo("Java Spring Boot") }
			assertThat(extractor.extract(textFile())).isEqualTo("Java Spring Boot")
		} finally {
			releaseParser.countDown(); callers.shutdownNow(); extractor.shutdownExtractionExecutor()
		}
	}

	@Test
	fun timeoutInterruptsSubmittedParserTask() {
		val interrupted = CountDownLatch(1)
		val extractor = ResumeTextExtractor(ResumeExtractionProperties(2, 1, 250_000, 50, 20)) {
			try {
				CountDownLatch(1).await(); "unreachable"
			} catch (exception: InterruptedException) {
				interrupted.countDown(); Thread.currentThread().interrupt(); throw ResumeExtractionException("interrupted", exception)
			}
		}
		try {
			assertThatThrownBy { extractor.extract(textFile()) }.isInstanceOf(ResumeExtractionException::class.java).hasMessageContaining("timed out after 1 seconds")
			assertThat(interrupted.await(1, TimeUnit.SECONDS)).isTrue()
		} finally { extractor.shutdownExtractionExecutor() }
	}

	private fun textFile(): ResumeFileContent {
		val bytes = "Java Spring Boot".toByteArray(StandardCharsets.UTF_8)
		return ResumeFileContent("resume.txt", "text/plain", bytes.size.toLong(), bytes, "text/plain", "txt")
	}
}
