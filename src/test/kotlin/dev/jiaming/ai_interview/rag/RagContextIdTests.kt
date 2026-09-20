package dev.jiaming.ai_interview.rag

import dev.jiaming.ai_interview.resume.TextChunk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class RagContextIdTests {
	@Test
	fun includesTheSourceSectionAndChunkIndex() {
		val chunk = TextChunk(4, "Professional Experience", "Built a service.")
		assertThat(RagContextId.forChunk("resume", chunk)).isEqualTo("resume:professional-experience:4")
	}

	@Test
	fun normalizesJobDescriptionSourceNames() {
		assertThat(RagContextId.forChunk("Job Description", "Required Skills", 2))
			.isEqualTo("job_description:required-skills:2")
	}
}
