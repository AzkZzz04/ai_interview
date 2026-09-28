package dev.jiaming.ai_interview.document

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import java.util.UUID

class ResolvedDocument @JsonCreator constructor(
    @JsonProperty("sourceType") private val source: DocumentSourceType,
    @JsonProperty("resourceId") private val id: UUID,
    @JsonProperty("contentHash") private val hash: String,
    @JsonProperty("normalizedText") private val text: String,
    @JsonProperty("persistedChunks") persistedChunks: List<DocumentChunk>?,
) {
    private val chunks = persistedChunks?.let { java.util.List.copyOf(it) } ?: emptyList()

    @JsonProperty("sourceType") fun sourceType(): DocumentSourceType = source
    @JsonProperty("resourceId") fun resourceId(): UUID = id
    @JsonProperty("contentHash") fun contentHash(): String = hash
    @JsonProperty("normalizedText") fun normalizedText(): String = text
    @JsonProperty("persistedChunks") fun persistedChunks(): List<DocumentChunk> = chunks

    override fun equals(other: Any?): Boolean = other is ResolvedDocument &&
        source == other.source && id == other.id && hash == other.hash && text == other.text && chunks == other.chunks

    override fun hashCode(): Int = listOf(source, id, hash, text, chunks).hashCode()

    override fun toString(): String =
        "ResolvedDocument[sourceType=$source, resourceId=$id, contentHash=$hash, normalizedText=$text, persistedChunks=$chunks]"
}
