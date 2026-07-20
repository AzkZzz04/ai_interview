package dev.jiaming.ai_interview.rag;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import dev.jiaming.ai_interview.resume.TextChunk;

class RagContextIdTests {

	@Test
	void includesTheSourceSectionAndChunkIndex() {
		TextChunk chunk = new TextChunk(4, "Professional Experience", "Built a service.");

		assertThat(RagContextId.forChunk("resume", chunk))
			.isEqualTo("resume:professional-experience:4");
	}

	@Test
	void normalizesJobDescriptionSourceNames() {
		assertThat(RagContextId.forChunk("Job Description", "Required Skills", 2))
			.isEqualTo("job_description:required-skills:2");
	}
}
