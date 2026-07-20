package dev.jiaming.ai_interview.resume;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

class ResumeTextExtractorTests {

	@Test
	void rejectsImmediatelyWhenAllWorkersAndQueueSlotsAreOccupiedThenRecovers() throws Exception {
		CountDownLatch parserStarted = new CountDownLatch(2);
		CountDownLatch releaseParser = new CountDownLatch(1);
		ResumeTextExtractor extractor = new ResumeTextExtractor(
			new ResumeExtractionProperties(2, 5, 250_000, 50, 20),
			file -> {
				parserStarted.countDown();
				try {
					releaseParser.await();
					return "Java Spring Boot";
				}
				catch (InterruptedException exception) {
					Thread.currentThread().interrupt();
					throw new ResumeExtractionException("interrupted", exception);
				}
			}
		);
		ExecutorService callers = Executors.newFixedThreadPool(4);
		List<Future<String>> pending = new ArrayList<>();
		try {
			for (int index = 0; index < 4; index++) {
				pending.add(callers.submit(() -> extractor.extract(textFile())));
			}
			assertThat(parserStarted.await(1, TimeUnit.SECONDS)).isTrue();
			await().atMost(Duration.ofSeconds(1)).until(() -> extractor.queuedExtractions() == 2);

			long started = System.nanoTime();
			assertThatThrownBy(() -> extractor.extract(textFile()))
				.isInstanceOf(ResumeParserBusyException.class)
				.hasMessageContaining("capacity");
			assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(500));

			releaseParser.countDown();
			for (Future<String> extraction : pending) {
				assertThat(extraction.get(2, TimeUnit.SECONDS)).isEqualTo("Java Spring Boot");
			}
			assertThat(extractor.extract(textFile())).isEqualTo("Java Spring Boot");
		}
		finally {
			releaseParser.countDown();
			callers.shutdownNow();
			extractor.shutdownExtractionExecutor();
		}
	}

	@Test
	void timeoutInterruptsSubmittedParserTask() throws Exception {
		CountDownLatch interrupted = new CountDownLatch(1);
		ResumeTextExtractor extractor = new ResumeTextExtractor(
			new ResumeExtractionProperties(2, 1, 250_000, 50, 20),
			file -> {
				try {
					new CountDownLatch(1).await();
					return "unreachable";
				}
				catch (InterruptedException exception) {
					interrupted.countDown();
					Thread.currentThread().interrupt();
					throw new ResumeExtractionException("interrupted", exception);
				}
			}
		);
		try {
			assertThatThrownBy(() -> extractor.extract(textFile()))
				.isInstanceOf(ResumeExtractionException.class)
				.hasMessageContaining("timed out after 1 seconds");
			assertThat(interrupted.await(1, TimeUnit.SECONDS)).isTrue();
		}
		finally {
			extractor.shutdownExtractionExecutor();
		}
	}

	private ResumeFileContent textFile() {
		byte[] bytes = "Java Spring Boot".getBytes(StandardCharsets.UTF_8);
		return new ResumeFileContent("resume.txt", "text/plain", bytes.length, bytes, "text/plain", "txt");
	}
}
