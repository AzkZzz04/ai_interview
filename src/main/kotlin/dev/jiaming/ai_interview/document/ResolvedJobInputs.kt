package dev.jiaming.ai_interview.document

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import java.util.Optional

class ResolvedJobInputs @JsonCreator constructor(
    @JsonProperty("resume") private val resolvedResume: ResolvedDocument,
    @JsonProperty("jobDescription") jobDescription: Optional<ResolvedDocument>?,
) {
    private val resolvedJobDescription = jobDescription ?: Optional.empty()

    @JsonProperty("resume") fun resume(): ResolvedDocument = resolvedResume
    @JsonProperty("jobDescription") fun jobDescription(): Optional<ResolvedDocument> = resolvedJobDescription

    override fun equals(other: Any?): Boolean = other is ResolvedJobInputs &&
        resolvedResume == other.resolvedResume && resolvedJobDescription == other.resolvedJobDescription

    override fun hashCode(): Int = listOf(resolvedResume, resolvedJobDescription).hashCode()

    override fun toString(): String =
        "ResolvedJobInputs[resume=$resolvedResume, jobDescription=$resolvedJobDescription]"
}
